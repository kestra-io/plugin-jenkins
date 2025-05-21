package io.kestra.plugin.jenkins;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.http.client.configurations.BasicAuthConfiguration;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.io.IOException;
import java.util.Objects;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractJenkins extends Task {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .registerModule(new JavaTimeModule());

    @NotNull
    @Schema(
        title = "Jenkins server URI",
        description = "The base URL of your Jenkins instance (e.g., http://jenkins.example.com:8080)"
    )
    protected Property<String> serverUrl;

    @Schema(
        title = "Username",
        description = "Username for Jenkins authentication"
    )
    private Property<String> username;

    @Schema(
        title = "API Token",
        description = "Jenkins API token for authentication."
    )
    private Property<String> apiToken;

    @Schema(
        title = "HTTP Configuration",
        description = "Additional HTTP configuration options"
    )
    private HttpConfiguration options;

    protected <RES> HttpResponse<RES> request(RunContext runContext, HttpRequest.HttpRequestBuilder requestBuilder, Class<RES> responseType)
        throws HttpClientException, IllegalVariableEvaluationException {
        HttpConfiguration httpConfig = configureAuthentication();

        var request = requestBuilder.build();

        try (HttpClient client = new HttpClient(runContext, httpConfig)) {
            HttpResponse<String> response = client.request(request, String.class);

            RES parsedResponse = null;
            if (responseType != Void.class && response.getBody() != null && !response.getBody().isEmpty()) {
                parsedResponse = MAPPER.readValue(response.getBody(), responseType);
            }

            return HttpResponse.<RES>builder()
                .request(request)
                .body(parsedResponse)
                .headers(response.getHeaders())
                .status(response.getStatus())
                .build();
        } catch (HttpClientResponseException e) {
            throw new HttpClientResponseException(
                "Request failed '" + Objects.requireNonNull(e.getResponse()).getStatus().getCode() +
                    "' and body '" + e.getResponse().getBody() + "'",
                e.getResponse()
            );
        } catch (IOException e) {
            throw new RuntimeException("Error parsing response body", e);
        }
    }

    private HttpConfiguration configureAuthentication() {
        HttpConfiguration.HttpConfigurationBuilder configBuilder =
            (options != null) ? options.toBuilder() : HttpConfiguration.builder();

        return configBuilder.auth(
            BasicAuthConfiguration.builder()
                .username(username)
                .password(apiToken)
                .build()
        ).build();
    }
}