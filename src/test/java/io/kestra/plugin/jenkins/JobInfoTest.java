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
import static org.hamcrest.Matchers.is;

@KestraTest
@WireMockTest(httpPort = 8082)
class JobInfoTest {

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void fetchJobInfo(WireMockRuntimeInfo wm) throws Exception {
        String buildInfoJson = """
            {
              "fullDisplayName": "demo-job #1",
              "result": "SUCCESS",
              "duration": 1234
            }
            """;

        stubFor(
            get(urlEqualTo("/job/test-job/1/api/json"))
                .willReturn(okJson(buildInfoJson))
        );

        RunContext runContext = runContextFactory.of(Map.of());

        JobInfo task = JobInfo.builder()
            .jobName("test-job")
            .buildNumber(1)
            .username(Property.ofValue("admin"))
            .apiToken(Property.ofValue("token"))
            .serverUrl(Property.ofValue(wm.getHttpBaseUrl()))
            .build();

        JobInfo.Output output = task.run(runContext);

        assertThat(output.getInfo().get("result"), is("SUCCESS"));
        assertThat(output.getInfo().get("duration"), is(1234));
    }

    @Test
    void fetchJobInfoWithSpaceInName(WireMockRuntimeInfo wm) throws Exception {
        var buildInfoJson = """
            {
              "fullDisplayName": "My Job #1",
              "result": "SUCCESS",
              "duration": 1234
            }
            """;

        stubFor(
            get(urlEqualTo("/job/My%20Job/1/api/json"))
                .willReturn(okJson(buildInfoJson))
        );

        var runContext = runContextFactory.of(Map.of());

        var task = JobInfo.builder()
            .jobName("My Job")
            .buildNumber(1)
            .username(Property.ofValue("admin"))
            .apiToken(Property.ofValue("token"))
            .serverUrl(Property.ofValue(wm.getHttpBaseUrl()))
            .build();

        var output = task.run(runContext);

        assertThat(output.getInfo().get("result"), is("SUCCESS"));
        verify(getRequestedFor(urlEqualTo("/job/My%20Job/1/api/json")));
    }

    @Test
    void fetchJobInfoInFolder(WireMockRuntimeInfo wm) throws Exception {
        var buildInfoJson = """
            {
              "fullDisplayName": "team » My Job #1",
              "result": "SUCCESS",
              "duration": 1234
            }
            """;

        stubFor(
            get(urlEqualTo("/job/team/job/My%20Job/1/api/json"))
                .willReturn(okJson(buildInfoJson))
        );

        var runContext = runContextFactory.of(Map.of());

        var task = JobInfo.builder()
            .jobName("team/My Job")
            .buildNumber(1)
            .username(Property.ofValue("admin"))
            .apiToken(Property.ofValue("token"))
            .serverUrl(Property.ofValue(wm.getHttpBaseUrl()))
            .build();

        var output = task.run(runContext);

        assertThat(output.getInfo().get("result"), is("SUCCESS"));
        verify(getRequestedFor(urlEqualTo("/job/team/job/My%20Job/1/api/json")));
    }
}
