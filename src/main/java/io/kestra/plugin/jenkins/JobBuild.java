package io.kestra.plugin.jenkins;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.hc.core5.http.ContentType;
import org.slf4j.Logger;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
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
                    timeout: PT30M
                """
        )
    }
)
public class JobBuild extends AbstractJenkins implements RunnableTask<JobBuild.Output> {

    private static final Pattern QUEUE_ITEM_PATTERN = Pattern.compile(".*/queue/item/(\\d+)/?.*");

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
            Frequency at which to poll the queue item and build endpoint when `wait` is true.
            """
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<Duration> pollInterval = Property.ofValue(Duration.ofSeconds(2));

    @Schema(
        title = "Execution timeout",
        description = """
            Maximum duration to wait for the build to finish before timing out.
            """
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<Duration> timeout = Property.ofValue(Duration.ofMinutes(10));

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
            List of acceptable build results when `wait` is true. Defaults to `['SUCCESS']`.
            """
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<List<String>> allowedStatuses = Property.ofValue(List.of("SUCCESS"));

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        String renderedJobName = runContext.render(jobName).as(String.class).orElseThrow();
        Map<String, Object> renderedParams = runContext.render(parameters).asMap(String.class, Object.class);

        var jobPath = encodeJobPath(renderedJobName);

        String renderedUri = runContext.render(serverUrl)
            .as(String.class)
            .map(u -> u.endsWith("/") ? u : u + "/")
            .orElseThrow();

        URI uri = URI.create(renderedUri + jobPath + "/buildWithParameters");

        HttpRequest.HttpRequestBuilder requestBuilder = HttpRequest.builder()
            .uri(uri)
            .addHeader("Content-Type", ContentType.APPLICATION_FORM_URLENCODED.getMimeType())
            .method("POST")
            .body(HttpRequest.UrlEncodedRequestBody.builder().content(renderedParams != null ? renderedParams : Collections.emptyMap()).build());

        HttpResponse<Void> response = this.request(runContext, requestBuilder, Void.class);

        logger.info("Triggered job '{}' with status code: {}", renderedJobName, response.getStatus().getCode());

        String locationHeader = Optional.ofNullable(response.getHeaders())
            .flatMap(h -> h.firstValue("Location"))
            .orElse(null);

        Long queueId = null;
        if (locationHeader != null) {
            Matcher matcher = QUEUE_ITEM_PATTERN.matcher(locationHeader);
            if (matcher.find()) {
                queueId = Long.parseLong(matcher.group(1));
            }
        }

        Boolean shouldWait = runContext.render(wait).as(Boolean.class).orElse(false);

        if (!Boolean.TRUE.equals(shouldWait)) {
            Integer buildNumber = null;
            String buildUrl = null;
            if (queueId != null) {
                try {
                    Map<String, Object> queueItem = getQueueItem(runContext, renderedUri, locationHeader, queueId);
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

        Duration timeoutVal = runContext.render(timeout).as(Duration.class).orElse(Duration.ofMinutes(10));
        Duration pollIntervalVal = runContext.render(pollInterval).as(Duration.class).orElse(Duration.ofSeconds(2));
        Boolean cancelOnTimeoutVal = runContext.render(cancelOnTimeout).as(Boolean.class).orElse(false);
        List<String> allowedStatusesVal = runContext.render(allowedStatuses).asList(String.class);

        Instant start = Instant.now();
        Integer buildNumber = null;
        String buildUrl = null;

        // 1. Wait for queue item to produce executable build number
        if (queueId != null) {
            while (Instant.now().isBefore(start.plus(timeoutVal))) {
                Map<String, Object> queueItem;
                try {
                    queueItem = getQueueItem(runContext, renderedUri, locationHeader, queueId);
                } catch (Exception e) {
                    logger.debug("Waiting for queue item {} (error: {})", queueId, e.getMessage());
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
                            break;
                        }
                    }
                }

                try {
                    Thread.sleep(pollIntervalVal.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while polling Jenkins queue", e);
                }
            }

            if (buildNumber == null) {
                if (Boolean.TRUE.equals(cancelOnTimeoutVal)) {
                    cancelQueueItem(runContext, renderedUri, queueId, logger);
                }
                throw new TimeoutException("Jenkins build timed out while in queue after " + timeoutVal);
            }
        }

        // 2. Wait for build to complete (building == false)
        Map<String, Object> buildInfo = null;
        String result = null;

        while (Instant.now().isBefore(start.plus(timeoutVal))) {
            try {
                buildInfo = getBuildInfo(runContext, renderedUri, jobPath, buildNumber);
            } catch (Exception e) {
                logger.debug("Waiting for build #{} (error: {})", buildNumber, e.getMessage());
                buildInfo = null;
            }

            if (buildInfo != null) {
                Boolean isBuilding = (Boolean) buildInfo.get("building");
                if (Boolean.FALSE.equals(isBuilding)) {
                    result = (String) buildInfo.get("result");
                    break;
                }
            }

            try {
                Thread.sleep(pollIntervalVal.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while polling Jenkins build", e);
            }
        }

        if (buildInfo == null || Boolean.TRUE.equals(buildInfo.get("building"))) {
            if (Boolean.TRUE.equals(cancelOnTimeoutVal) && buildNumber != null) {
                stopBuild(runContext, renderedUri, jobPath, buildNumber, logger);
            }
            throw new TimeoutException("Jenkins build #" + buildNumber + " timed out after " + timeoutVal);
        }

        if (allowedStatusesVal != null && !allowedStatusesVal.isEmpty()) {
            final String finalResult = result;
            boolean allowed = allowedStatusesVal.stream().anyMatch(s -> s.equalsIgnoreCase(finalResult));
            if (!allowed) {
                throw new IllegalStateException("Jenkins build #" + buildNumber + " completed with result '" + result + "', but expected one of " + allowedStatusesVal);
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> getQueueItem(RunContext runContext, String serverUrl, String locationHeader, Long queueId) throws Exception {
        URI uri;
        if (locationHeader != null && (locationHeader.startsWith("http://") || locationHeader.startsWith("https://"))) {
            String base = locationHeader.endsWith("/") ? locationHeader : locationHeader + "/";
            uri = URI.create(base + "api/json");
        } else {
            uri = URI.create(serverUrl + "queue/item/" + queueId + "/api/json");
        }
        var req = HttpRequest.builder().uri(uri).method("GET");
        return this.request(runContext, req, Map.class).getBody();
    }

    private void cancelQueueItem(RunContext runContext, String serverUrl, Long queueId, Logger logger) {
        try {
            URI uri = URI.create(serverUrl + "queue/cancelItem?id=" + queueId);
            var req = HttpRequest.builder().uri(uri).method("POST");
            this.request(runContext, req, Void.class);
            logger.info("Cancelled Jenkins queue item {}", queueId);
        } catch (Exception e) {
            logger.warn("Failed to cancel Jenkins queue item {}: {}", queueId, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getBuildInfo(RunContext runContext, String serverUrl, String jobPath, Integer buildNumber) throws Exception {
        URI uri = URI.create(serverUrl + jobPath + "/" + buildNumber + "/api/json");
        var req = HttpRequest.builder().uri(uri).method("GET");
        return this.request(runContext, req, Map.class).getBody();
    }

    private void stopBuild(RunContext runContext, String serverUrl, String jobPath, Integer buildNumber, Logger logger) {
        try {
            URI uri = URI.create(serverUrl + jobPath + "/" + buildNumber + "/stop");
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
