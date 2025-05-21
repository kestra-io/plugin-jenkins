package io.kestra.plugin.jenkins;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;

import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.http.HttpRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

@SuperBuilder
@Getter
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Schema(
    title = "Get Jenkins job build info."
)
@Plugin(
    examples = {
        @Example(
            title = "Get info about a specific build",
            code = """
                id: get_info
                namespace: company.team
                tasks:
                  - id: info
                    type: io.kestra.plugin.jenkins.JobInfo
                    username: admin
                    apiToken: my_api_token
                    jobName: test
                    buildNumber: 1
                    serverUri: http://localhost:8080
                """
        )
    }
)
@SuppressWarnings("unchecked")
public class JobInfo extends AbstractJenkins implements RunnableTask<JobInfo.Output> {

    @NotNull
    private String jobName;

    @NotNull
    private Integer buildNumber;

    @Override
    public Output run(RunContext runContext) throws Exception {
        String base = runContext.render(serverUrl).as(String.class).orElseThrow();
        if (!base.endsWith("/")) base += "/";

        String jobPath = Arrays.stream(jobName.split("/"))
            .map(part -> "job/" + URLEncoder.encode(part, StandardCharsets.UTF_8))
            .collect(Collectors.joining("/"));

        URI uri = URI.create(base + jobPath + "/" + buildNumber + "/api/json");

        HttpRequest.HttpRequestBuilder request = HttpRequest.builder()
            .uri(uri)
            .method("GET");

        Map<String, Object> buildInfo = this.request(runContext, request, Map.class).getBody();


        return Output.builder()
            .info(buildInfo)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Build info from Jenkins")
        private final Map<String, Object> info;
    }

    @Data
    @NoArgsConstructor
    public static class GetResult {
        Map<String, Object> response;
    }
}
