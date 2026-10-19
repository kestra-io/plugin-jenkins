package io.kestra.plugin.jenkins;

import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
import lombok.extern.jackson.Jacksonized;

@SuperBuilder
@Getter
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Schema(
    title = "List recent builds of a Jenkins job",
    description = """
        Retrieves builds from the Jenkins job API tree, supporting filters by build result and start time.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "List failed builds of a job",
            full = true,
            code = """
                id: jenkins_failed_builds
                namespace: company.team

                tasks:
                  - id: list_failed
                    type: io.kestra.plugin.jenkins.ListBuilds
                    serverUrl: https://jenkins.example.com
                    username: "{{ secret('JENKINS_USER') }}"
                    apiToken: "{{ secret('JENKINS_API_TOKEN') }}"
                    jobName: folder/my-job
                    result: FAILURE

                  - id: log_results
                    type: io.kestra.plugin.core.log.Log
                    message: "Found {{ outputs.list_failed.size }} failed builds"
                """
        )
    }
)
public class ListBuilds extends AbstractJenkins implements RunnableTask<ListBuilds.Output> {

    @Schema(
        title = "Jenkins job path",
        description = """
            Job name or foldered path using `/`; each segment is URL-encoded.
            """
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> jobName;

    @Schema(
        title = "Build result filter",
        description = """
            Optional filter by build result (e.g., `SUCCESS`, `FAILURE`, `UNSTABLE`, `ABORTED`). Case-insensitive.
            """
    )
    @PluginProperty(group = "advanced")
    private Property<String> result;

    @Schema(
        title = "Since timestamp",
        description = """
            Optional filter to include only builds started at or after this instant.
            """
    )
    @PluginProperty(group = "advanced")
    private Property<Instant> since;

    @Schema(
        title = "Maximum builds",
        description = """
            Optional maximum number of builds to return.
            """
    )
    @PluginProperty(group = "advanced")
    private Property<Integer> max;

    @Override
    @SuppressWarnings("unchecked")
    public Output run(RunContext runContext) throws Exception {
        String base = runContext.render(serverUrl).as(String.class).orElseThrow();
        if (!base.endsWith("/")) {
            base += "/";
        }

        String renderedJobName = runContext.render(jobName).as(String.class).orElseThrow();
        String renderedResult = result != null ? runContext.render(result).as(String.class).orElse(null) : null;
        Instant renderedSince = since != null ? runContext.render(since).as(Instant.class).orElse(null) : null;
        Integer renderedMax = max != null ? runContext.render(max).as(Integer.class).orElse(null) : null;

        var jobPath = encodeJobPath(renderedJobName);

        URI uri = URI.create(base + jobPath + "/api/json?tree=builds[number,url,result,building,duration,timestamp,fullDisplayName]");

        HttpRequest.HttpRequestBuilder request = HttpRequest.builder()
            .uri(uri)
            .method("GET");

        Map<String, Object> jobData = this.request(runContext, request, Map.class).getBody();

        List<Map<String, Object>> rawBuilds = Collections.emptyList();
        if (jobData != null && jobData.get("builds") instanceof List<?> list) {
            rawBuilds = (List<Map<String, Object>>) list;
        }

        Stream<BuildSummary> stream = rawBuilds.stream().map(
            b -> BuildSummary.builder()
                .number(b.get("number") instanceof Number n ? n.intValue() : null)
                .url(b.get("url") instanceof String u ? u : null)
                .result(b.get("result") instanceof String r ? r : null)
                .building(b.get("building") instanceof Boolean bld ? bld : null)
                .duration(b.get("duration") instanceof Number n ? n.longValue() : null)
                .timestamp(b.get("timestamp") instanceof Number n ? Instant.ofEpochMilli(n.longValue()) : null)
                .fullDisplayName(b.get("fullDisplayName") instanceof String d ? d : null)
                .build()
        );

        if (renderedResult != null) {
            stream = stream.filter(b -> renderedResult.equalsIgnoreCase(b.getResult()));
        }

        if (renderedSince != null) {
            stream = stream.filter(b -> b.getTimestamp() != null && !b.getTimestamp().isBefore(renderedSince));
        }

        if (renderedMax != null && renderedMax > 0) {
            stream = stream.limit(renderedMax);
        }

        List<BuildSummary> builds = stream.collect(Collectors.toList());

        return Output.builder()
            .builds(builds)
            .size(builds.size())
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "List of builds matching the criteria")
        private final List<BuildSummary> builds;

        @Schema(title = "Number of builds returned")
        private final Integer size;
    }

    @Builder
    @Getter
    @Jacksonized
    public static class BuildSummary {
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

        @Schema(title = "Full display name of the build")
        private final String fullDisplayName;
    }
}
