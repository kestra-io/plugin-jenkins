package io.kestra.plugin.jenkins;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.configurations.BasicAuthConfiguration;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.*;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.storages.kv.KVMetadata;
import io.kestra.core.storages.kv.KVValueAndMetadata;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@Getter
@NoArgsConstructor
@ToString
@EqualsAndHashCode(callSuper = true)
@Schema(
    title = "Wait for a Jenkins build to complete",
    description = """
        Periodically polls Jenkins for completed builds on a specific job, advancing a watermark in the namespace KV store to guarantee exactly-once firing.
        Flat output properties describe the lowest matching completed build, while `builds` delivers newly completed matching builds in ascending order.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "React when a Jenkins build finishes",
            full = true,
            code = """
                id: jenkins_build_finished
                namespace: company.team

                triggers:
                  - id: on_build_completed
                    type: io.kestra.plugin.jenkins.Trigger
                    serverUrl: https://jenkins.example.com
                    username: "{{ secret('JENKINS_USER') }}"
                    apiToken: "{{ secret('JENKINS_API_TOKEN') }}"
                    jobName: folder/my-job
                    interval: PT1M

                tasks:
                  - id: handle_event
                    type: io.kestra.plugin.core.log.Log
                    message: "Build #{{ trigger.buildNumber }} ended with {{ trigger.result }}"
                """
        )
    }
)
public class Trigger extends AbstractTrigger implements PollingTriggerInterface, TriggerOutput<Trigger.Output> {

    private static final ObjectMapper MAPPER = JacksonMapper.ofJson();

    @NotNull
    @Schema(
        title = "Jenkins base URL",
        description = """
            Base HTTP(S) endpoint for the Jenkins instance (e.g., `http://jenkins.example.com:8080`).
            """
    )
    @PluginProperty(group = "main")
    private Property<String> serverUrl;

    @Schema(
        title = "Jenkins username",
        description = """
            Optional username used for Basic Auth when Jenkins is secured.
            """
    )
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @PluginProperty(group = "connection", secret = true)
    private Property<String> username;

    @Schema(
        title = "API token",
        description = """
            Jenkins API token or password paired with the username for Basic Auth.
            """
    )
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @PluginProperty(group = "connection", secret = true)
    private Property<String> apiToken;

    @Schema(
        title = "HTTP client options",
        description = """
            Optional overrides for HTTP timeouts, proxies, or headers.
            """
    )
    @PluginProperty(group = "advanced")
    private HttpConfiguration options;

    @NotNull
    @Schema(
        title = "Jenkins job path",
        description = """
            Job name or foldered path using `/`; each segment is URL-encoded.
            """
    )
    @PluginProperty(group = "main")
    private Property<String> jobName;

    @Schema(
        title = "Build result filter",
        description = """
            Optional filter by build result: `SUCCESS`, `UNSTABLE`, `FAILURE`, `NOT_BUILT`, or `ABORTED`.
            """
    )
    @PluginProperty(group = "processing")
    private Property<BuildResult> result;

    @Builder.Default
    @Schema(
        title = "Polling interval",
        description = """
            Interval between trigger evaluations. Defaults to 1 minute.
            """
    )
    @PluginProperty(group = "execution")
    private final Duration interval = Duration.ofMinutes(1);

    @Schema(
        title = "Initial build number",
        description = """
            Optional build number to start monitoring from. When omitted on first poll, initialized to the current highest build number so existing past builds are not re-fired.
            """
    )
    @PluginProperty(group = "advanced")
    private Property<Integer> initialBuildNumber;

    @Override
    @SuppressWarnings("unchecked")
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        var runContext = conditionContext.getRunContext();
        var logger = runContext.logger();

        var rServerUrl = runContext.render(serverUrl).as(String.class).orElseThrow(() -> new IllegalArgumentException("serverUrl is required"));
        var rBase = rServerUrl.endsWith("/") ? rServerUrl : rServerUrl + "/";

        var rJobName = runContext.render(jobName).as(String.class).orElseThrow(() -> new IllegalArgumentException("jobName is required"));
        var rResult = result != null ? runContext.render(result).as(BuildResult.class).orElse(null) : null;
        var rInitialBuild = initialBuildNumber != null ? runContext.render(initialBuildNumber).as(Integer.class).orElse(null) : null;

        var jobPath = AbstractJenkins.encodeJobPath(rJobName);
        var kvStore = runContext.namespaceKv(context.getNamespace());

        var jobHash = sha256Hex(rJobName);
        var flowId = context.getFlowId() != null ? context.getFlowId() : "default";
        var triggerId = this.getId() != null ? this.getId() : "jenkins";
        var stateKey = "jenkins_watermark_" + flowId.length() + "_" + flowId + "_" + triggerId.length() + "_" + triggerId + "_" + jobHash;

        Integer watermark = null;
        try {
            var kvVal = kvStore.getValue(stateKey);
            if (kvVal.isPresent() && kvVal.get().value() != null) {
                var val = kvVal.get().value();
                if (val instanceof Number n) {
                    watermark = n.intValue();
                } else if (val instanceof byte[] bytes && bytes.length > 0) {
                    watermark = Integer.parseInt(new String(bytes, StandardCharsets.UTF_8));
                } else {
                    watermark = Integer.parseInt(val.toString());
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to read Jenkins watermark from KV store for key '{}': {}", stateKey, e.getMessage());
            return Optional.empty();
        }

        var uri = URI.create(rBase + jobPath + "/api/json?tree=builds[number,url,result,building,duration,timestamp,fullDisplayName]");
        var jobData = fetchJobData(runContext, uri);

        List<Map<String, Object>> rawBuilds = Collections.emptyList();
        if (jobData != null && jobData.get("builds") instanceof List<?> list) {
            rawBuilds = (List<Map<String, Object>>) list;
        }

        if (watermark == null) {
            if (rInitialBuild != null) {
                watermark = rInitialBuild - 1;
            } else {
                int maxBuildNumber = rawBuilds.stream()
                    .map(b -> b.get("number"))
                    .filter(Number.class::isInstance)
                    .mapToInt(n -> ((Number) n).intValue())
                    .max()
                    .orElse(0);

                kvStore.put(
                    stateKey, new KVValueAndMetadata(
                        new KVMetadata("Jenkins trigger watermark", (Duration) null),
                        maxBuildNumber
                    )
                );

                logger.info("Initialized Jenkins trigger watermark for job '{}' to build #{}", rJobName, maxBuildNumber);
                return Optional.empty();
            }
        }

        final int currentWatermark = watermark;

        var summaries = rawBuilds.stream()
            .map(ListBuilds.BuildSummary::from)
            .filter(Objects::nonNull)
            .toList();

        Integer lowestBuildingNumber = summaries.stream()
            .filter(b -> b.getNumber() != null && b.getNumber() > currentWatermark)
            .filter(b -> Boolean.TRUE.equals(b.getBuilding()))
            .map(ListBuilds.BuildSummary::getNumber)
            .min(Integer::compareTo)
            .orElse(null);

        int safeCeiling = currentWatermark;
        if (lowestBuildingNumber != null) {
            safeCeiling = Math.max(currentWatermark, lowestBuildingNumber - 1);
        } else {
            Integer maxCompleted = summaries.stream()
                .filter(b -> b.getNumber() != null && b.getNumber() > currentWatermark)
                .filter(b -> Boolean.FALSE.equals(b.getBuilding()))
                .map(ListBuilds.BuildSummary::getNumber)
                .max(Integer::compareTo)
                .orElse(null);
            if (maxCompleted != null) {
                safeCeiling = maxCompleted;
            }
        }

        final int finalSafeCeiling = safeCeiling;
        var eligibleBuilds = summaries.stream()
            .filter(b -> b.getNumber() != null && b.getNumber() > currentWatermark && b.getNumber() <= finalSafeCeiling)
            .filter(b -> Boolean.FALSE.equals(b.getBuilding()))
            .sorted(Comparator.comparingInt(ListBuilds.BuildSummary::getNumber))
            .toList();

        var matchingBuilds = eligibleBuilds.stream()
            .filter(b -> rResult == null || rResult.name().equalsIgnoreCase(b.getResult()))
            .toList();

        if (safeCeiling > currentWatermark) {
            kvStore.put(
                stateKey, new KVValueAndMetadata(
                    new KVMetadata("Jenkins trigger watermark", (Duration) null),
                    safeCeiling
                )
            );
        }

        if (matchingBuilds.isEmpty()) {
            return Optional.empty();
        }

        var target = matchingBuilds.getFirst();

        logger.info("Firing Jenkins trigger for job '{}' on build #{}", rJobName, target.getNumber());

        var output = Output.builder()
            .buildNumber(target.getNumber())
            .buildUrl(target.getUrl())
            .result(target.getResult())
            .duration(target.getDuration())
            .timestamp(target.getTimestamp())
            .fullDisplayName(target.getFullDisplayName())
            .builds(matchingBuilds)
            .build();

        return Optional.of(TriggerService.generateExecution(this, conditionContext, context, output));
    }

    private static String sha256Hex(String input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchJobData(RunContext runContext, URI uri) throws Exception {
        var httpConfig = configureAuthentication();
        var request = HttpRequest.builder().uri(uri).method("GET").build();

        try (var client = new HttpClient(runContext, httpConfig)) {
            var response = client.request(request, String.class);
            if (response.getBody() != null && !response.getBody().isEmpty()) {
                return MAPPER.readValue(response.getBody(), Map.class);
            }
            return Collections.emptyMap();
        }
    }

    private HttpConfiguration configureAuthentication() {
        var configBuilder = (options != null) ? options.toBuilder() : HttpConfiguration.builder();

        return configBuilder.auth(
            BasicAuthConfiguration.builder()
                .username(username)
                .password(apiToken)
                .build()
        ).build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Build number")
        private final Integer buildNumber;

        @Schema(title = "Direct URL to the build in Jenkins")
        private final String buildUrl;

        @Schema(title = "Build result (e.g., SUCCESS, FAILURE, UNSTABLE)")
        private final String result;

        @Schema(title = "Build duration in milliseconds")
        private final Long duration;

        @Schema(title = "Build start timestamp")
        private final Instant timestamp;

        @Schema(title = "Full display name of the build")
        private final String fullDisplayName;

        @Schema(title = "Newly completed matching builds in ascending order")
        private final List<ListBuilds.BuildSummary> builds;
    }
}
