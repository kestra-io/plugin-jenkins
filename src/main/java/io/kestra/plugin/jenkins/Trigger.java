package io.kestra.plugin.jenkins;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import org.slf4j.Logger;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
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

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .registerModule(new JavaTimeModule());

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
            Optional filter by build result (e.g., `SUCCESS`, `FAILURE`, `UNSTABLE`). Case-insensitive.
            """
    )
    @PluginProperty(group = "advanced")
    private Property<String> result;

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
        RunContext runContext = conditionContext.getRunContext();
        Logger logger = runContext.logger();

        String base = runContext.render(serverUrl).as(String.class).orElseThrow();
        if (!base.endsWith("/")) {
            base += "/";
        }

        String renderedJobName = runContext.render(jobName).as(String.class).orElseThrow();
        String renderedResult = result != null ? runContext.render(result).as(String.class).orElse(null) : null;
        Integer renderedInitialBuild = initialBuildNumber != null ? runContext.render(initialBuildNumber).as(Integer.class).orElse(null) : null;

        var jobPath = AbstractJenkins.encodeJobPath(renderedJobName);
        var kvStore = runContext.namespaceKv(context.getNamespace());
        String stateKey = "jenkins_watermark_" + context.getFlowId() + "_" + this.getId() + "_" + jobPath.replace('/', '_');

        Integer watermark = null;
        try {
            var kvVal = kvStore.getValue(stateKey);
            if (kvVal.isPresent() && kvVal.get().value() != null) {
                Object val = kvVal.get().value();
                if (val instanceof Number n) {
                    watermark = n.intValue();
                } else if (val instanceof byte[] bytes && bytes.length > 0) {
                    watermark = Integer.parseInt(new String(bytes, StandardCharsets.UTF_8));
                } else {
                    watermark = Integer.parseInt(val.toString());
                }
            }
        } catch (Exception e) {
            logger.debug("No previous watermark found in KV store: {}", e.getMessage());
        }

        URI uri = URI.create(base + jobPath + "/api/json?tree=builds[number,url,result,building,duration,timestamp,fullDisplayName]");
        Map<String, Object> jobData = fetchJobData(runContext, uri);

        List<Map<String, Object>> rawBuilds = Collections.emptyList();
        if (jobData != null && jobData.get("builds") instanceof List<?> list) {
            rawBuilds = (List<Map<String, Object>>) list;
        }

        if (watermark == null) {
            if (renderedInitialBuild != null) {
                watermark = renderedInitialBuild - 1;
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
                        String.valueOf(maxBuildNumber).getBytes(StandardCharsets.UTF_8)
                    )
                );

                logger.info("Initialized Jenkins trigger watermark for job '{}' to build #{}", renderedJobName, maxBuildNumber);
                return Optional.empty();
            }
        }

        final int currentWatermark = watermark;

        List<ListBuilds.BuildSummary> candidateBuilds = rawBuilds.stream()
            .map(
                b -> ListBuilds.BuildSummary.builder()
                    .number(b.get("number") instanceof Number n ? n.intValue() : null)
                    .url(b.get("url") instanceof String u ? u : null)
                    .result(b.get("result") instanceof String r ? r : null)
                    .building(b.get("building") instanceof Boolean bld ? bld : null)
                    .duration(b.get("duration") instanceof Number n ? n.longValue() : null)
                    .timestamp(b.get("timestamp") instanceof Number n ? Instant.ofEpochMilli(n.longValue()) : null)
                    .fullDisplayName(b.get("fullDisplayName") instanceof String d ? d : null)
                    .build()
            )
            .filter(b -> b.getNumber() != null && b.getNumber() > currentWatermark)
            .filter(b -> Boolean.FALSE.equals(b.getBuilding()))
            .filter(b -> renderedResult == null || renderedResult.equalsIgnoreCase(b.getResult()))
            .sorted(Comparator.comparingInt(ListBuilds.BuildSummary::getNumber))
            .collect(Collectors.toList());

        if (candidateBuilds.isEmpty()) {
            return Optional.empty();
        }

        ListBuilds.BuildSummary target = candidateBuilds.get(0);

        kvStore.put(
            stateKey, new KVValueAndMetadata(
                new KVMetadata("Jenkins trigger watermark", (Duration) null),
                String.valueOf(target.getNumber()).getBytes(StandardCharsets.UTF_8)
            )
        );

        logger.info("Firing Jenkins trigger for job '{}' on build #{}", renderedJobName, target.getNumber());

        Output output = Output.builder()
            .buildNumber(target.getNumber())
            .buildUrl(target.getUrl())
            .result(target.getResult())
            .duration(target.getDuration())
            .timestamp(target.getTimestamp())
            .fullDisplayName(target.getFullDisplayName())
            .build();

        return Optional.of(TriggerService.generateExecution(this, conditionContext, context, output));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchJobData(RunContext runContext, URI uri) throws Exception {
        HttpConfiguration httpConfig = configureAuthentication();
        var request = HttpRequest.builder().uri(uri).method("GET").build();

        try (HttpClient client = new HttpClient(runContext, httpConfig)) {
            HttpResponse<String> response = client.request(request, String.class);
            if (response.getBody() != null && !response.getBody().isEmpty()) {
                return MAPPER.readValue(response.getBody(), Map.class);
            }
            return Collections.emptyMap();
        }
    }

    private HttpConfiguration configureAuthentication() {
        HttpConfiguration.HttpConfigurationBuilder configBuilder = (options != null) ? options.toBuilder() : HttpConfiguration.builder();

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
    }
}
