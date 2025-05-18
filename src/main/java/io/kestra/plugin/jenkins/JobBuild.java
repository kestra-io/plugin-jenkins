package io.kestra.plugin.jenkins;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.apache.hc.core5.http.ContentType;
import org.slf4j.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@SuperBuilder
@Getter
@ToString
@EqualsAndHashCode
@NoArgsConstructor
@Schema(
    title = "Trigger a Jenkins job.",
    description = "Starts a Jenkins job using the buildWithParameters endpoint."
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
                    serverUri: http://localhost:8080
                    username: admin
                    api_token: my_api_token
                    parameters:
                      branch: main
                      environment:
                        - staging
                """
        )
    }
)
public class JobBuild extends AbstractJenkins implements RunnableTask<JobBuild.Output> {

    @Schema(title = "Jenkins job name (can be foldered using `/`, e.g., 'team/project/job')")
    @NotNull
    private Property<String> jobName;

    @Schema(title = "Parameters for the job")
    private Property<Map<String, Object>> parameters;

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        String renderedJobName = runContext.render(jobName).as(String.class).orElseThrow();
        Map<String, Object> renderedParams = runContext.render(parameters).asMap(String.class, Object.class);

        String jobPath = Arrays.stream(renderedJobName.split("/"))
            .map(s -> "job/" + URLEncoder.encode(s, StandardCharsets.UTF_8))
            .collect(Collectors.joining("/"));

        String renderedUri = runContext.render(serverUrl)
            .as(String.class)
            .map(u -> u.endsWith("/") ? u : u + "/")
            .orElseThrow();

        URI uri = URI.create(renderedUri + jobPath + "/buildWithParameters");

        HttpRequest.HttpRequestBuilder requestBuilder = HttpRequest.builder()
            .uri(uri)
            .addHeader("Content-Type", ContentType.APPLICATION_FORM_URLENCODED.getMimeType())
            .method("POST")
            .body(HttpRequest.UrlEncodedRequestBody.builder().content(renderedParams).build());

        HttpResponse<Void> response = this.request(runContext, requestBuilder, Void.class);

        logger.info("Triggered job '{}' with status code: {}", renderedJobName, response.getStatus().getCode());

        return Output.builder()
            .status(response.getStatus().getCode())
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "HTTP status code returned by Jenkins")
        private final Integer status;
    }
}
