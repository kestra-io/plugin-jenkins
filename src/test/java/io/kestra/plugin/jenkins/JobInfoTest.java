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
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@WireMockTest(httpPort = 8082)
class JobInfoTest {

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void fetchJobInfo(WireMockRuntimeInfo wm) throws Exception {
        String buildInfoJson = """
            {
              "number": 1,
              "url": "http://localhost:8082/job/test-job/1/",
              "fullDisplayName": "demo-job #1",
              "result": "SUCCESS",
              "building": false,
              "duration": 1234,
              "timestamp": 1690000000000
            }
            """;

        stubFor(
            get(urlEqualTo("/job/test-job/1/api/json"))
                .willReturn(okJson(buildInfoJson))
        );

        RunContext runContext = runContextFactory.of(Map.of());

        JobInfo task = JobInfo.builder()
            .jobName(Property.ofValue("test-job"))
            .buildNumber(Property.ofValue("1"))
            .username(Property.ofValue("admin"))
            .apiToken(Property.ofValue("token"))
            .serverUrl(Property.ofValue(wm.getHttpBaseUrl()))
            .build();

        JobInfo.Output output = task.run(runContext);

        assertThat(output.getInfo().get("result"), is("SUCCESS"));
        assertThat(output.getInfo().get("duration"), is(1234));
        assertThat(output.getNumber(), is(1));
        assertThat(output.getUrl(), is("http://localhost:8082/job/test-job/1/"));
        assertThat(output.getResult(), is("SUCCESS"));
        assertThat(output.getBuilding(), is(false));
        assertThat(output.getDuration(), is(1234L));
        assertThat(output.getTimestamp(), notNullValue());
    }

    @Test
    void fetchJobInfoWithLastBuildAlias(WireMockRuntimeInfo wm) throws Exception {
        String buildInfoJson = """
            {
              "number": 42,
              "url": "http://localhost:8082/job/test-job/42/",
              "fullDisplayName": "demo-job #42",
              "result": "SUCCESS",
              "building": false,
              "duration": 5678,
              "timestamp": 1690000000000
            }
            """;

        stubFor(
            get(urlEqualTo("/job/test-job/lastBuild/api/json"))
                .willReturn(okJson(buildInfoJson))
        );

        RunContext runContext = runContextFactory.of(Map.of());

        JobInfo task = JobInfo.builder()
            .jobName(Property.ofValue("test-job"))
            .buildNumber(Property.ofValue("lastBuild"))
            .username(Property.ofValue("admin"))
            .apiToken(Property.ofValue("token"))
            .serverUrl(Property.ofValue(wm.getHttpBaseUrl()))
            .build();

        JobInfo.Output output = task.run(runContext);

        assertThat(output.getNumber(), is(42));
        assertThat(output.getResult(), is("SUCCESS"));
        assertThat(output.getUrl(), is("http://localhost:8082/job/test-job/42/"));
        verify(getRequestedFor(urlEqualTo("/job/test-job/lastBuild/api/json")));
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
            .jobName(Property.ofValue("My Job"))
            .buildNumber(Property.ofValue("1"))
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
            .jobName(Property.ofValue("team/My Job"))
            .buildNumber(Property.ofValue("1"))
            .username(Property.ofValue("admin"))
            .apiToken(Property.ofValue("token"))
            .serverUrl(Property.ofValue(wm.getHttpBaseUrl()))
            .build();

        var output = task.run(runContext);

        assertThat(output.getInfo().get("result"), is("SUCCESS"));
        verify(getRequestedFor(urlEqualTo("/job/team/job/My%20Job/1/api/json")));
    }

    @Test
    void rejectsInvalidBuildNumberTraversal(WireMockRuntimeInfo wm) {
        var runContext = runContextFactory.of(Map.of());

        var task = JobInfo.builder()
            .jobName(Property.ofValue("test-job"))
            .buildNumber(Property.ofValue("../../arbitrary"))
            .username(Property.ofValue("admin"))
            .apiToken(Property.ofValue("token"))
            .serverUrl(Property.ofValue(wm.getHttpBaseUrl()))
            .build();

        assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
    }
}
