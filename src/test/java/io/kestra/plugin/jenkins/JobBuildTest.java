package io.kestra.plugin.jenkins;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@KestraTest
@WireMockTest(httpPort = 8082)
class JobBuildTest {

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void triggerJenkinsJob(WireMockRuntimeInfo wm) throws Exception {
        stubFor(post(urlEqualTo("/job/test-job/buildWithParameters"))
            .willReturn(aResponse()
                .withStatus(201)));

        RunContext runContext = runContextFactory.of(Map.of());

        JobBuild jobBuild = JobBuild.builder()
            .jobName(Property.of("test-job"))
            .parameters(Property.of(Map.of("param1", "value1")))
            .username(Property.of("admin"))
            .apiToken(Property.of("demo"))
            .serverUrl(Property.of(wm.getHttpBaseUrl()))
            .build();

        JobBuild.Output output = jobBuild.run(runContext);

        assertThat(output.getStatus(), is(201));
    }
}
