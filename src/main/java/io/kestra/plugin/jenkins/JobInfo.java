package io.kestra.plugin.jenkins;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

import io.kestra.core.http.HttpRequest;
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
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Schema(
    title = "Fetch Jenkins build details",
    description = "Retrieves a build JSON payload from Jenkins `/api/json`, URL-encoding foldered job paths and using Basic Auth when credentials are set."
)
@Plugin(
    examples = {
        @Example(
            title = "Get info about a specific build",
            full = true,
            code = """
                id: get_info
                namespace: company.team
                tasks:
                  - id: info
                    type: io.kestra.plugin.jenkins.JobInfo
                    username: admin
                    apiToken: "{{ secret('JENKINS_API_TOKEN') }}"
                    jobName: test
                    buildNumber: "1"
                    serverUrl: http://localhost:8080
                """
        ),
        @Example(
            title = "Get info about the last build of a job",
            full = true,
            code = """
                id: get_last_build_info
                namespace: company.team
                tasks:
                  - id: info
                    type: io.kestra.plugin.jenkins.JobInfo
                    username: admin
                    apiToken: "{{ secret('JENKINS_API_TOKEN') }}"
                    jobName: test
                    buildNumber: "lastBuild"
                    serverUrl: http://localhost:8080
                """
        )
    }
)
@SuppressWarnings("unchecked")
public class JobInfo extends AbstractJenkins implements RunnableTask<JobInfo.Output> {

    @Schema(
        title = "Jenkins job path",
        description = "Job name or foldered path using `/`; each segment is URL-encoded."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> jobName;

    @Schema(
        title = "Build number or alias",
        description = "Jenkins build number (e.g. `1`) or alias such as `lastBuild`, `lastSuccessfulBuild`, `lastFailedBuild`."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> buildNumber;

    public abstract static class JobInfoBuilder<C extends JobInfo, B extends JobInfoBuilder<C, B>> extends AbstractJenkinsBuilder<C, B> {
        public B jobName(String jobName) {
            this.jobName = Property.ofValue(jobName);
            return self();
        }

        public B jobName(Property<String> jobName) {
            this.jobName = jobName;
            return self();
        }

        public B buildNumber(Integer buildNumber) {
            this.buildNumber = Property.ofValue(String.valueOf(buildNumber));
            return self();
        }

        public B buildNumber(String buildNumber) {
            this.buildNumber = Property.ofValue(buildNumber);
            return self();
        }

        public B buildNumber(Property<String> buildNumber) {
            this.buildNumber = buildNumber;
            return self();
        }
    }

    @Override
    public Output run(RunContext runContext) throws Exception {
        String base = runContext.render(serverUrl).as(String.class).orElseThrow();
        if (!base.endsWith("/")) {
            base += "/";
        }

        String renderedJobName = runContext.render(jobName).as(String.class).orElseThrow();
        String renderedBuild = runContext.render(buildNumber).as(String.class).orElse("lastBuild");

        var jobPath = encodeJobPath(renderedJobName);

        URI uri = URI.create(base + jobPath + "/" + renderedBuild + "/api/json");

        HttpRequest.HttpRequestBuilder request = HttpRequest.builder()
            .uri(uri)
            .method("GET");

        Map<String, Object> buildInfo = this.request(runContext, request, Map.class).getBody();

        Integer number = null;
        String url = null;
        String result = null;
        Boolean building = null;
        Long duration = null;
        Instant timestamp = null;

        if (buildInfo != null) {
            if (buildInfo.get("number") instanceof Number n) {
                number = n.intValue();
            }
            if (buildInfo.get("url") instanceof String u) {
                url = u;
            }
            if (buildInfo.get("result") instanceof String r) {
                result = r;
            }
            if (buildInfo.get("building") instanceof Boolean b) {
                building = b;
            }
            if (buildInfo.get("duration") instanceof Number n) {
                duration = n.longValue();
            }
            if (buildInfo.get("timestamp") instanceof Number n) {
                timestamp = Instant.ofEpochMilli(n.longValue());
            }
        }

        return Output.builder()
            .info(buildInfo)
            .number(number)
            .url(url)
            .result(result)
            .building(building)
            .duration(duration)
            .timestamp(timestamp)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Raw build info map from Jenkins")
        private final Map<String, Object> info;

        @Schema(title = "Build number")
        private final Integer number;

        @Schema(title = "Direct URL to the build in Jenkins")
        private final String url;

        @Schema(title = "Build result (e.g., SUCCESS, FAILURE, UNSTABLE)")
        private final String result;

        @Schema(title = "Whether the build is in progress")
        private final Boolean building;

        @Schema(title = "Build duration in milliseconds")
        private final Long duration;

        @Schema(title = "Build start timestamp")
        private final Instant timestamp;
    }

    @Data
    @NoArgsConstructor
    public static class GetResult {
        Map<String, Object> response;
    }
}
