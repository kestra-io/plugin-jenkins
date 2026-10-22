package io.kestra.plugin.jenkins;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.apache.hc.core5.http.ContentType;
import org.slf4j.Logger;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@Getter
@ToString
@EqualsAndHashCode
@NoArgsConstructor
@Schema(
    title = "Trigger a Jenkins job build",
    description = """
        Calls Jenkins buildWithParameters to enqueue a build, URL-encoding foldered job names and form parameters; optionally waits for completion and verifies the build result.
        """
)
@Plugin(
    examples = {
        @Example(
            full = true,
            title = "Trigger a Jenkins job with parameters",
            code = """
                id: jenkins_job_trigger
                namespace: company.team

                tasks:
                  - id: build
                    type: io.kestra.plugin.jenkins.JobBuild
                    jobName: deploy-app
                    serverUrl: http://localhost:8080
                    username: admin
                    apiToken: "{{ secret('JENKINS_API_TOKEN') }}"
                    parameters:
                      branch: main
                      environment:
                        - staging
                """
        ),
        @Example(
            full = true,
            title = "Trigger a Jenkins job and wait for the result",
            code = """
                id: jenkins_build_and_wait
                namespace: company.team

                tasks:
                  - id: build
                    type: io.kestra.plugin.jenkins.JobBuild
                    serverUrl: https://jenkins.example.com
                    username: "{{ secret('JENKINS_USER') }}"
                    apiToken: "{{ secret('JENKINS_API_TOKEN') }}"
                    jobName: folder/my-job
                    parameters:
                      DEPLOY_ENV: staging
                    wait: true
                    waitTimeout: PT30M
                """
        )
    }
)
public class JobBuild extends AbstractJenkins implements RunnableTask<JobBuild.Output> {

    private static final Pattern QUEUE_ITEM_PATTERN = Pattern.compile(".*/queue/item/(\\d+)/?.*");

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @Schema(hidden = true)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private final transient AtomicReference<Runnable> killable = new AtomicReference<>();

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @Schema(hidden = true)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private final transient AtomicBoolean isKilled = new AtomicBoolean(false);

    @Schema(
        title = "Jenkins job path",
        description = """
            Job name or foldered path using `/` (e.g., `team/project/job`); each segment is URL-encoded.
            """
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> jobName;

    @Schema(
        title = "Build parameters",
        description = """
            Optional map sent as form data to Jenkins; values are rendered then URL-encoded.
            """
    )
    @PluginProperty(group = "main")
    private Property<Map<String, Object>> parameters;

    @Schema(
        title = "Wait for completion",
        description = """
            Whether to poll until the queued build finishes running.
            """
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<Boolean> wait = Property.ofValue(false);

    @Schema(
        title = "Polling interval",
        description = """
            Frequency at which to poll the queue item and build endpoint when `wait` is true. Defaults to `PT2S`.
            """
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<Duration> pollInterval = Property.ofValue(Duration.ofSeconds(2));

    @Schema(
        title = "Wait timeout",
        description = """
            Maximum duration to wait for the build to finish before timing out. Defaults to `PT10M`.
            """
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<Duration> waitTimeout = Property.ofValue(Duration.ofMinutes(10));

    @Schema(
        title = "Cancel build on timeout",
        description = """
            If true, attempts to cancel the queued item or stop the running build if the timeout is reached.
            """
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<Boolean> cancelOnTimeout = Property.ofValue(false);

    @Schema(
        title = "Allowed success statuses",
        description = """
            List of acceptable build results when `wait` is true: `SUCCESS`, `UNSTABLE`, `FAILURE`, `NOT_BUILT`, or `ABORTED`. Defaults to `['SUCCESS']`.
            """
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<List<BuildResult>> allowedStatuses = Property.ofValue(List.of(BuildResult.SUCCESS));

    @Override
    public void kill() {
        if (isKilled.compareAndSet(false, true)) {
            Optional.ofNullable(killable.get()).ifPresent(Runnable::run);
        }
    }

    @Override
    public void stop() {
        kill();
    }

    @Override
    public Output run(RunContext runContext) throws Exception {
        var logger = runContext.logger();

        var rJobName = runContext.render(jobName).as(String.class).orElseThrow(() -> new IllegalArgumentException("jobName is required"));
        var rParameters = runContext.render(parameters).asMap(String.class, Object.class);

        var jobPath = encodeJobPath(rJobName);

        var rServerUrl = runContext.render(serverUrl)
            .as(String.class)
            .map(u -> u.endsWith("/") ? u : u + "/")
            .orElseThrow(() -> new IllegalArgumentException("serverUrl is required"));

        var uri = URI.create(rServerUrl + jobPath + "/buildWithParameters");

        var requestBuilder = HttpRequest.builder()
            .uri(uri)
            .addHeader("Content-Type", ContentType.APPLICATION_FORM_URLENCODED.getMimeType())
            .method("POST")
            .body(HttpRequest.UrlEncodedRequestBody.builder().content(rParameters != null ? rParameters : Collections.emptyMap()).build());

        var response = this.request(runContext, requestBuilder, Void.class);

        logger.info("Triggered job '{}' with status code: {}", rJobName, response.getStatus().getCode());

        var locationHeader = Optional.ofNullable(response.getHeaders())
            .flatMap(h -> h.firstValue("Location"))
            .orElse(null);

        Long queueId = null;
        if (locationHeader != null) {
            var matcher = QUEUE_ITEM_PATTERN.matcher(locationHeader);
            if (matcher.find()) {
                queueId = Long.parseLong(matcher.group(1));
            }
        }

        var rWait = runContext.render(wait).as(Boolean.class).orElse(false);

        if (!Boolean.TRUE.equals(rWait)) {
            Integer buildNumber = null;
            String buildUrl = null;
            if (queueId != null) {
                try {
                    var queueItem = getQueueItem(runContext, rServerUrl, queueId);
                    if (queueItem != null && queueItem.get("executable") instanceof Map<?, ?> exec) {
                        buildNumber = exec.get("number") instanceof Number n ? n.intValue() : null;
                        buildUrl = exec.get("url") instanceof String u ? u : null;
                    }
                } catch (Exception e) {
                    logger.debug("Could not resolve queue item {}: {}", queueId, e.getMessage());
                }
            }

            return Output.builder()
                .status(response.getStatus().getCode())
                .queueId(queueId)
                .buildNumber(buildNumber)
                .buildUrl(buildUrl)
                .build();
        }

        if (queueId == null) {
            throw new IllegalStateException("Jenkins returned no queue Location header when wait was requested. Check Jenkins configuration or reverse proxy.");
        }

        final Long finalQueueId = queueId;
        killable.set(() -> cancelQueueItem(runContext, rServerUrl, finalQueueId, logger));

        var rWaitTimeout = runContext.render(waitTimeout).as(Duration.class).orElse(Duration.ofMinutes(10));
        if (rWaitTimeout.isNegative() || rWaitTimeout.isZero()) {
            throw new IllegalArgumentException("waitTimeout must be positive, got: " + rWaitTimeout);
        }

        var rPollInterval = runContext.render(pollInterval).as(Duration.class).orElse(Duration.ofSeconds(2));
        if (rPollInterval.isNegative() || rPollInterval.isZero()) {
            throw new IllegalArgumentException("pollInterval must be positive, got: " + rPollInterval);
        }
        var rCancelOnTimeout = runContext.render(cancelOnTimeout).as(Boolean.class).orElse(false);
        var rAllowedStatuses = runContext.render(allowedStatuses).asList(BuildResult.class);

        var start = Instant.now();
        var buildDetails = waitForQueueItem(runContext, rServerUrl, queueId, start, rWaitTimeout, rPollInterval, rCancelOnTimeout, logger);
        Integer buildNumber = buildDetails.buildNumber;
        String buildUrl = buildDetails.buildUrl;

        final Integer finalBuildNumber = buildNumber;
        killable.set(() -> stopBuild(runContext, rServerUrl, jobPath, finalBuildNumber, logger));

        var completedBuild = waitForBuildCompletion(runContext, rServerUrl, jobPath, buildNumber, start, rWaitTimeout, rPollInterval, rCancelOnTimeout, logger);
        var buildInfo = completedBuild.buildInfo;
        var result = completedBuild.result;

        if (rAllowedStatuses != null && !rAllowedStatuses.isEmpty()) {
            final String finalResult = result;
            var allowed = rAllowedStatuses.stream().anyMatch(s -> s.name().equalsIgnoreCase(finalResult));
            if (!allowed) {
                throw new IllegalStateException("Jenkins build #" + buildNumber + " completed with result '" + result + "', but expected one of " + rAllowedStatuses);
            }
        }

        return Output.builder()
            .status(response.getStatus().getCode())
            .queueId(queueId)
            .buildNumber(buildNumber)
            .buildUrl(buildUrl)
            .result(result)
            .info(buildInfo)
            .build();
    }

    private record QueueResult(Integer buildNumber, String buildUrl) {
    }

    private QueueResult waitForQueueItem(
        RunContext runContext,
        String rServerUrl,
        Long queueId,
        Instant start,
        Duration rWaitTimeout,
        Duration rPollInterval,
        Boolean rCancelOnTimeout,
        Logger logger) throws Exception {
        Integer buildNumber = null;
        String buildUrl = null;

        while (Instant.now().isBefore(start.plus(rWaitTimeout))) {
            Map<String, Object> queueItem;
            try {
                queueItem = getQueueItem(runContext, rServerUrl, queueId);
            } catch (HttpClientResponseException e) {
                var code = e.getResponse() != null && e.getResponse().getStatus() != null ? e.getResponse().getStatus().getCode() : null;
                if (code != null && code >= 400 && code < 500 && code != 404) {
                    throw new IllegalStateException("Client error (" + code + ") while polling Jenkins queue item " + queueId + ": " + e.getMessage(), e);
                }
                logger.warn("Transient error while waiting for queue item {}: {}", queueId, e.getMessage());
                queueItem = null;
            } catch (Exception e) {
                logger.warn("Transient error while waiting for queue item {}: {}", queueId, e.getMessage());
                queueItem = null;
            }

            if (queueItem != null) {
                if (Boolean.TRUE.equals(queueItem.get("cancelled"))) {
                    throw new IllegalStateException("Jenkins build was cancelled while waiting in queue: " + queueId);
                }
                if (queueItem.get("executable") instanceof Map<?, ?> exec) {
                    buildNumber = exec.get("number") instanceof Number n ? n.intValue() : null;
                    buildUrl = exec.get("url") instanceof String u ? u : null;
                    if (buildNumber != null) {
                        return new QueueResult(buildNumber, buildUrl);
                    }
                }
            }

            try {
                Thread.sleep(rPollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while polling Jenkins queue", e);
            }
        }

        if (Boolean.TRUE.equals(rCancelOnTimeout)) {
            cancelQueueItem(runContext, rServerUrl, queueId, logger);
        }
        throw new TimeoutException("Jenkins build timed out while in queue after " + rWaitTimeout);
    }

    private record BuildCompletion(Map<String, Object> buildInfo, String result) {
    }

    private BuildCompletion waitForBuildCompletion(
        RunContext runContext,
        String rServerUrl,
        String jobPath,
        Integer buildNumber,
        Instant start,
        Duration rWaitTimeout,
        Duration rPollInterval,
        Boolean rCancelOnTimeout,
        Logger logger) throws Exception {
        Map<String, Object> buildInfo = null;
        String result = null;

        while (Instant.now().isBefore(start.plus(rWaitTimeout))) {
            try {
                buildInfo = getBuildInfo(runContext, rServerUrl, jobPath, buildNumber);
            } catch (HttpClientResponseException e) {
                var code = e.getResponse() != null && e.getResponse().getStatus() != null ? e.getResponse().getStatus().getCode() : null;
                if (code != null && code >= 400 && code < 500) {
                    throw new IllegalStateException("Client error (" + code + ") while polling Jenkins build #" + buildNumber + ": " + e.getMessage(), e);
                }
                logger.warn("Transient error while waiting for build #{}: {}", buildNumber, e.getMessage());
                buildInfo = null;
            } catch (Exception e) {
                logger.warn("Transient error while waiting for build #{}: {}", buildNumber, e.getMessage());
                buildInfo = null;
            }

            if (buildInfo != null) {
                Boolean isBuilding = (Boolean) buildInfo.get("building");
                if (Boolean.FALSE.equals(isBuilding)) {
                    result = (String) buildInfo.get("result");
                    return new BuildCompletion(buildInfo, result);
                }
            }

            try {
                Thread.sleep(rPollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while polling Jenkins build", e);
            }
        }

        if (Boolean.TRUE.equals(rCancelOnTimeout) && buildNumber != null) {
            stopBuild(runContext, rServerUrl, jobPath, buildNumber, logger);
        }
        throw new TimeoutException("Jenkins build #" + buildNumber + " timed out after " + rWaitTimeout);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getQueueItem(RunContext runContext, String serverUrl, Long queueId) throws Exception {
        var uri = URI.create(serverUrl + "queue/item/" + queueId + "/api/json");
        var req = HttpRequest.builder().uri(uri).method("GET");
        return this.request(runContext, req, Map.class).getBody();
    }

    private void cancelQueueItem(RunContext runContext, String serverUrl, Long queueId, Logger logger) {
        try {
            var uri = URI.create(serverUrl + "queue/cancelItem?id=" + queueId);
            var req = HttpRequest.builder().uri(uri).method("POST");
            this.request(runContext, req, Void.class);
            logger.info("Cancelled Jenkins queue item {}", queueId);
        } catch (Exception e) {
            logger.warn("Failed to cancel Jenkins queue item {}: {}", queueId, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getBuildInfo(RunContext runContext, String serverUrl, String jobPath, Integer buildNumber) throws Exception {
        var uri = URI.create(serverUrl + jobPath + "/" + buildNumber + "/api/json");
        var req = HttpRequest.builder().uri(uri).method("GET");
        return this.request(runContext, req, Map.class).getBody();
    }

    private void stopBuild(RunContext runContext, String serverUrl, String jobPath, Integer buildNumber, Logger logger) {
        try {
            var uri = URI.create(serverUrl + jobPath + "/" + buildNumber + "/stop");
            var req = HttpRequest.builder().uri(uri).method("POST");
            this.request(runContext, req, Void.class);
            logger.info("Stopped Jenkins build #{}", buildNumber);
        } catch (Exception e) {
            logger.warn("Failed to stop Jenkins build #{}: {}", buildNumber, e.getMessage());
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "HTTP status code returned by Jenkins")
        private final Integer status;

        @Schema(
            title = "Jenkins queue item ID",
            description = """
                Parsed from the Location header returned when queuing the build.
                """
        )
        private final Long queueId;

        @Schema(title = "Build number assigned to the queued build")
        private final Integer buildNumber;

        @Schema(title = "Direct URL to the build in Jenkins")
        private final String buildUrl;

        @Schema(
            title = "Build result",
            description = """
                Final result status of the build (e.g., SUCCESS, FAILURE, UNSTABLE). Only populated when wait is true.
                """
        )
        private final String result;

        @Schema(
            title = "Full build details payload from Jenkins API",
            description = """
                Only populated when wait is true.
                """
        )
        private final Map<String, Object> info;
    }
}
