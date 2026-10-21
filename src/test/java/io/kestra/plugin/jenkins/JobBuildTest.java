package io.kestra.plugin.jenkins;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

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
            post(urlEqualTo("/job/My%20Job/buildWithParameters"))
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
        verify(postRequestedFor(urlEqualTo("/job/My%20Job/buildWithParameters")));
    }

    @Test
    void triggerJenkinsJobInFolder(WireMockRuntimeInfo wm) throws Exception {
        stubFor(
            post(urlEqualTo("/job/team/job/My%20Job/buildWithParameters"))
                .willReturn(aResponse().withStatus(201))
        );

        var runContext = runContextFactory.of(Map.of());

        var jobBuild = JobBuild.builder()
            .jobName(Property.ofValue("team/My Job"))
            .parameters(Property.ofValue(Map.of("param1", "value1")))
            .username(Property.ofValue("admin"))
            .apiToken(Property.ofValue("demo"))
            .serverUrl(Property.ofValue(wm.getHttpBaseUrl()))
            .build();

        var output = jobBuild.run(runContext);

        assertThat(output.getStatus(), is(201));
        verify(postRequestedFor(urlEqualTo("/job/team/job/My%20Job/buildWithParameters")));
    }

    @Test
    void triggerJenkinsJobWithLocationHeaderAndQueueId(WireMockRuntimeInfo wm) throws Exception {
        stubFor(
            post(urlEqualTo("/job/test-job/buildWithParameters"))
                .willReturn(
                    aResponse()
                        .withStatus(201)
                        .withHeader("Location", wm.getHttpBaseUrl() + "/queue/item/42/")
                )
        );

        RunContext runContext = runContextFactory.of(Map.of());

        JobBuild jobBuild = JobBuild.builder()
            .jobName(Property.ofValue("test-job"))
            .serverUrl(Property.ofValue(wm.getHttpBaseUrl()))
            .build();

        JobBuild.Output output = jobBuild.run(runContext);

        assertThat(output.getStatus(), is(201));
        assertThat(output.getQueueId(), is(42L));
    }

    @Test
    void triggerJenkinsJobAndWaitSuccess(WireMockRuntimeInfo wm) throws Exception {
        stubFor(
            post(urlEqualTo("/job/test-job/buildWithParameters"))
                .willReturn(
                    aResponse()
                        .withStatus(201)
                        .withHeader("Location", wm.getHttpBaseUrl() + "/queue/item/100/")
                )
        );

        stubFor(
            get(urlEqualTo("/queue/item/100/api/json"))
                .willReturn(
                    okJson("""
                        {
                          "id": 100,
                          "executable": {
                            "number": 15,
                            "url": "http://localhost:8082/job/test-job/15/"
                          }
                        }
                        """)
                )
        );

        stubFor(
            get(urlEqualTo("/job/test-job/15/api/json"))
                .willReturn(
                    okJson("""
                        {
                          "number": 15,
                          "building": false,
                          "result": "SUCCESS",
                          "duration": 5000,
                          "url": "http://localhost:8082/job/test-job/15/"
                        }
                        """)
                )
        );

        RunContext runContext = runContextFactory.of(Map.of());

        JobBuild jobBuild = JobBuild.builder()
            .jobName(Property.ofValue("test-job"))
            .serverUrl(Property.ofValue(wm.getHttpBaseUrl()))
            .wait(Property.ofValue(true))
            .pollInterval(Property.ofValue(Duration.ofMillis(50)))
            .waitTimeout(Property.ofValue(Duration.ofSeconds(5)))
            .build();

        JobBuild.Output output = jobBuild.run(runContext);

        assertThat(output.getStatus(), is(201));
        assertThat(output.getQueueId(), is(100L));
        assertThat(output.getBuildNumber(), is(15));
        assertThat(output.getResult(), is("SUCCESS"));
        assertThat(output.getInfo(), notNullValue());
    }

    @Test
    void triggerJenkinsJobAndWaitFailureThrows(WireMockRuntimeInfo wm) throws Exception {
        stubFor(
            post(urlEqualTo("/job/test-job/buildWithParameters"))
                .willReturn(
                    aResponse()
                        .withStatus(201)
                        .withHeader("Location", wm.getHttpBaseUrl() + "/queue/item/101/")
                )
        );

        stubFor(
            get(urlEqualTo("/queue/item/101/api/json"))
                .willReturn(
                    okJson("""
                        {
                          "id": 101,
                          "executable": {
                            "number": 16,
                            "url": "http://localhost:8082/job/test-job/16/"
                          }
                        }
                        """)
                )
        );

        stubFor(
            get(urlEqualTo("/job/test-job/16/api/json"))
                .willReturn(
                    okJson("""
                        {
                          "number": 16,
                          "building": false,
                          "result": "FAILURE"
                        }
                        """)
                )
        );

        RunContext runContext = runContextFactory.of(Map.of());

        JobBuild jobBuild = JobBuild.builder()
            .jobName(Property.ofValue("test-job"))
            .serverUrl(Property.ofValue(wm.getHttpBaseUrl()))
            .wait(Property.ofValue(true))
            .pollInterval(Property.ofValue(Duration.ofMillis(50)))
            .waitTimeout(Property.ofValue(Duration.ofSeconds(5)))
            .allowedStatuses(Property.ofValue(List.of("SUCCESS")))
            .build();

        assertThrows(IllegalStateException.class, () -> jobBuild.run(runContext));
    }

    @Test
    void triggerJenkinsJobQueueCancelledThrows(WireMockRuntimeInfo wm) throws Exception {
        stubFor(
            post(urlEqualTo("/job/test-job/buildWithParameters"))
                .willReturn(
                    aResponse()
                        .withStatus(201)
                        .withHeader("Location", wm.getHttpBaseUrl() + "/queue/item/102/")
                )
        );

        stubFor(
            get(urlEqualTo("/queue/item/102/api/json"))
                .willReturn(
                    okJson("""
                        {
                          "id": 102,
                          "cancelled": true
                        }
                        """)
                )
        );

        RunContext runContext = runContextFactory.of(Map.of());

        JobBuild jobBuild = JobBuild.builder()
            .jobName(Property.ofValue("test-job"))
            .serverUrl(Property.ofValue(wm.getHttpBaseUrl()))
            .wait(Property.ofValue(true))
            .pollInterval(Property.ofValue(Duration.ofMillis(50)))
            .waitTimeout(Property.ofValue(Duration.ofSeconds(5)))
            .build();

        assertThrows(IllegalStateException.class, () -> jobBuild.run(runContext));
    }

    @Test
    void triggerJenkinsJobTimeoutWithCancelQueueItem(WireMockRuntimeInfo wm) {
        stubFor(
            post(urlEqualTo("/job/test-job/buildWithParameters"))
                .willReturn(
                    aResponse()
                        .withStatus(201)
                        .withHeader("Location", wm.getHttpBaseUrl() + "/queue/item/103/")
                )
        );

        stubFor(
            get(urlEqualTo("/queue/item/103/api/json"))
                .willReturn(
                    okJson("""
                        {
                          "id": 103,
                          "blocked": true
                        }
                        """)
                )
        );

        stubFor(
            post(urlEqualTo("/queue/cancelItem?id=103"))
                .willReturn(ok())
        );

        RunContext runContext = runContextFactory.of(Map.of());

        JobBuild jobBuild = JobBuild.builder()
            .jobName(Property.ofValue("test-job"))
            .serverUrl(Property.ofValue(wm.getHttpBaseUrl()))
            .wait(Property.ofValue(true))
            .cancelOnTimeout(Property.ofValue(true))
            .pollInterval(Property.ofValue(Duration.ofMillis(50)))
            .waitTimeout(Property.ofValue(Duration.ofMillis(120)))
            .build();

        assertThrows(TimeoutException.class, () -> jobBuild.run(runContext));
        verify(postRequestedFor(urlEqualTo("/queue/cancelItem?id=103")));
    }

    @Test
    void triggerJenkinsJobTimeoutWithStopBuild(WireMockRuntimeInfo wm) {
        stubFor(
            post(urlEqualTo("/job/test-job/buildWithParameters"))
                .willReturn(
                    aResponse()
                        .withStatus(201)
                        .withHeader("Location", wm.getHttpBaseUrl() + "/queue/item/104/")
                )
        );

        stubFor(
            get(urlEqualTo("/queue/item/104/api/json"))
                .willReturn(
                    okJson("""
                        {
                          "id": 104,
                          "executable": {
                            "number": 17,
                            "url": "http://localhost:8082/job/test-job/17/"
                          }
                        }
                        """)
                )
        );

        stubFor(
            get(urlEqualTo("/job/test-job/17/api/json"))
                .willReturn(
                    okJson("""
                        {
                          "number": 17,
                          "building": true
                        }
                        """)
                )
        );

        stubFor(
            post(urlEqualTo("/job/test-job/17/stop"))
                .willReturn(ok())
        );

        RunContext runContext = runContextFactory.of(Map.of());

        JobBuild jobBuild = JobBuild.builder()
            .jobName(Property.ofValue("test-job"))
            .serverUrl(Property.ofValue(wm.getHttpBaseUrl()))
            .wait(Property.ofValue(true))
            .cancelOnTimeout(Property.ofValue(true))
            .pollInterval(Property.ofValue(Duration.ofMillis(50)))
            .waitTimeout(Property.ofValue(Duration.ofMillis(120)))
            .build();

        assertThrows(TimeoutException.class, () -> jobBuild.run(runContext));
        verify(postRequestedFor(urlEqualTo("/job/test-job/17/stop")));
    }
}
