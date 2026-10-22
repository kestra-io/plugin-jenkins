package io.kestra.plugin.jenkins;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.net.URI;
import java.time.Instant;
import java.util.*;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;
import reactor.core.publisher.Flux;

@SuperBuilder
@Getter
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Schema(
    title = "List recent builds of a Jenkins job",
    description = """
        Retrieves builds from the Jenkins job API tree, supporting filters by build result and start time, with FETCH, FETCH_ONE, and STORE modes.
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
        title = "The way you want to store the data",
        description = """
            FETCH_ONE to retrieve only the first build, FETCH to output as a list, STORE to store as internal storage file. Defaults to `FETCH`.
            """
    )
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Schema(
        title = "Build result filter",
        description = """
            Optional filter by build result: `SUCCESS`, `UNSTABLE`, `FAILURE`, `NOT_BUILT`, or `ABORTED`.
            """
    )
    @PluginProperty(group = "processing")
    private Property<BuildResult> result;

    @Schema(
        title = "Since timestamp",
        description = """
            Optional filter to include only builds started at or after this instant.
            """
    )
    @PluginProperty(group = "processing")
    private Property<Instant> since;

    @Schema(
        title = "Maximum builds",
        description = """
            Maximum number of builds to return (between 1 and 100, matching the Jenkins tree API limit). Defaults to `50`.
            """
    )
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<@Min(1) @Max(100) Integer> max = Property.ofValue(50);

    @Override
    @SuppressWarnings("unchecked")
    public Output run(RunContext runContext) throws Exception {
        var rServerUrl = runContext.render(serverUrl).as(String.class).orElseThrow(() -> new IllegalArgumentException("serverUrl is required"));
        var rBase = rServerUrl.endsWith("/") ? rServerUrl : rServerUrl + "/";

        var rJobName = runContext.render(jobName).as(String.class).orElseThrow(() -> new IllegalArgumentException("jobName is required"));
        var rFetchType = runContext.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH);
        var rResult = result != null ? runContext.render(result).as(BuildResult.class).orElse(null) : null;
        var rSince = since != null ? runContext.render(since).as(Instant.class).orElse(null) : null;
        Integer rMax = max != null ? runContext.render(max).as(Integer.class).orElse(50) : 50;

        var jobPath = encodeJobPath(rJobName);

        String treeParam = (rResult == null && rSince == null && rMax != null)
            ? "tree=builds[number,url,result,building,duration,timestamp,fullDisplayName]%7B0," + rMax + "%7D"
            : "tree=builds[number,url,result,building,duration,timestamp,fullDisplayName]";

        var uri = URI.create(rBase + jobPath + "/api/json?" + treeParam);

        var request = HttpRequest.builder()
            .uri(uri)
            .method("GET");

        Map<String, Object> jobData = this.request(runContext, request, Map.class).getBody();

        List<Map<String, Object>> rawBuilds = Collections.emptyList();
        if (jobData != null && jobData.get("builds") instanceof List<?> list) {
            rawBuilds = (List<Map<String, Object>>) list;
        }

        var stream = rawBuilds.stream().map(BuildSummary::from);

        if (rResult != null) {
            stream = stream.filter(b -> rResult.name().equalsIgnoreCase(b.getResult()));
        }

        if (rSince != null) {
            stream = stream.filter(b -> b.getTimestamp() != null && !b.getTimestamp().isBefore(rSince));
        }

        if (rMax != null && rMax > 0) {
            stream = stream.limit(rMax);
        }

        List<BuildSummary> builds = stream.toList();

        Output.OutputBuilder outputBuilder = Output.builder()
            .size(builds.size());

        switch (rFetchType) {
            case FETCH_ONE -> {
                if (!builds.isEmpty()) {
                    outputBuilder.build(builds.getFirst());
                }
            }
            case STORE -> {
                File tempFile = runContext.workingDir().createTempFile(".ion").toFile();
                try (var fileWriter = new BufferedWriter(new FileWriter(tempFile), FileSerde.BUFFER_SIZE)) {
                    if (!builds.isEmpty()) {
                        Flux<BuildSummary> flux = Flux.fromIterable(builds);
                        FileSerde.writeAll(fileWriter, flux).block();
                    }
                }
                outputBuilder.uri(runContext.storage().putFile(tempFile));
            }
            case FETCH -> {
                outputBuilder.builds(builds);
            }
            case NONE -> {
            }
        }

        return outputBuilder.build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "List of builds matching the criteria (when fetchType is FETCH)")
        private final List<BuildSummary> builds;

        @Schema(title = "Single build (when fetchType is FETCH_ONE)")
        private final BuildSummary build;

        @Schema(title = "URI of the stored builds file in internal storage (when fetchType is STORE)")
        private final URI uri;

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

        public static BuildSummary from(Map<String, Object> b) {
            if (b == null) {
                return null;
            }
            return BuildSummary.builder()
                .number(b.get("number") instanceof Number n ? n.intValue() : null)
                .url(b.get("url") instanceof String u ? u : null)
                .result(b.get("result") instanceof String r ? r : null)
                .building(b.get("building") instanceof Boolean bld ? bld : null)
                .duration(b.get("duration") instanceof Number n ? n.longValue() : null)
                .timestamp(b.get("timestamp") instanceof Number n ? Instant.ofEpochMilli(n.longValue()) : null)
                .fullDisplayName(b.get("fullDisplayName") instanceof String d ? d : null)
                .build();
        }
    }
}
