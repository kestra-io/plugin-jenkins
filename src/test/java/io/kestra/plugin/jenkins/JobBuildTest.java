package io.kestra.plugin.jenkins;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

@KestraTest
@WireMockTest(httpPort = 8082)
class JobBuildTest {

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void triggerJenkinsJob(WireMockRuntimeInfo wm) throws Exception {
        stubFor(
            post(urlEqualTo("/job/test-job/buildWithParameters"))
                .willReturn(
                    aResponse()
                        .withStatus(201)
                )
        );

        RunContext runContext = runContextFactory.of(Map.of());

        JobBuild jobBuild = JobBuild.builder()
            .jobName(Property.ofValue("test-job"))
            .parameters(Property.ofValue(Map.of("param1", "value1")))
            .username(Property.ofValue("admin"))
            .apiToken(Property.ofValue("demo"))
            .serverUrl(Property.ofValue(wm.getHttpBaseUrl()))
            .build();

        JobBuild.Output output = jobBuild.run(runContext);

        assertThat(output.getStatus(), is(201));
    }

    @Test
    void triggerJenkinsJobWithSpaceInName(WireMockRuntimeInfo wm) throws Exception {
        stubFor(
            post(urlMatching("/job/.*/buildWithParameters"))
                .willReturn(aResponse().withStatus(201))
        );

        var runContext = runContextFactory.of(Map.of());

        var jobBuild = JobBuild.builder()
            .jobName(Property.ofValue("My Job"))
            .parameters(Property.ofValue(Map.of("param1", "value1")))
            .username(Property.ofValue("admin"))
            .apiToken(Property.ofValue("demo"))
            .serverUrl(Property.ofValue(wm.getHttpBaseUrl()))
            .build();

        var output = jobBuild.run(runContext);

        assertThat(output.getStatus(), is(201));

        var requests = findAll(postRequestedFor(urlMatching("/job/.*/buildWithParameters")));
        assertThat(requests.size(), is(1));
        var path = requests.get(0).getUrl();
        assertThat(path, containsString("%20"));
        assertThat(path, not(containsString("My+Job")));
    }
}
