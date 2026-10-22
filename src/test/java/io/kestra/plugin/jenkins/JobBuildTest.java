package io.kestra.plugin.jenkins;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import io.kestra.core.http.client.HttpClientResponseException;
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
            .allowedStatuses(Property.ofValue(List.of(BuildResult.SUCCESS)))
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

    @Test
    void triggerJenkinsJobBuildPolling400FailsFast(WireMockRuntimeInfo wm) throws Exception {
        stubFor(
            post(urlEqualTo("/job/test-job/buildWithParameters"))
                .willReturn(
                    aResponse()
                        .withStatus(201)
                        .withHeader("Location", wm.getHttpBaseUrl() + "/queue/item/110/")
                )
        );

        stubFor(
            get(urlEqualTo("/queue/item/110/api/json"))
                .willReturn(
                    okJson("""
                        {
                          "id": 110,
                          "executable": {
                            "number": 20,
                            "url": "http://localhost:8082/job/test-job/20/"
                          }
                        }
                        """)
                )
        );

        stubFor(
            get(urlEqualTo("/job/test-job/20/api/json"))
                .willReturn(
                    aResponse()
                        .withStatus(400)
                        .withBody("Bad Request")
                )
        );

        RunContext runContext = runContextFactory.of(Map.of());

        JobBuild jobBuild = JobBuild.builder()
            .jobName(Property.ofValue("test-job"))
            .serverUrl(Property.ofValue(wm.getHttpBaseUrl()))
            .wait(Property.ofValue(true))
            .pollInterval(Property.ofValue(Duration.ofMillis(50)))
            .waitTimeout(Property.ofValue(Duration.ofMinutes(10)))
            .build();

        long start = System.currentTimeMillis();
        var ex = assertThrows(IllegalStateException.class, () -> jobBuild.run(runContext));
        long duration = System.currentTimeMillis() - start;

        assertThat(ex.getMessage(), containsString("Client error (400)"));
        assertThat("Should fail fast rather than waiting for 10m timeout", duration, lessThan(5000L));
    }

    @Test
    void triggerJenkinsJobQueueItem404IsRetriedUntilAllocated(WireMockRuntimeInfo wm) throws Exception {
        stubFor(
            post(urlEqualTo("/job/test-job/buildWithParameters"))
                .willReturn(
                    aResponse()
                        .withStatus(201)
                        .withHeader("Location", wm.getHttpBaseUrl() + "/queue/item/120/")
                )
        );

        // First poll: 404 while Jenkins queue allocates
        stubFor(
            get(urlEqualTo("/queue/item/120/api/json"))
                .inScenario("Queue404Retry")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(
                    aResponse()
                        .withStatus(404)
                        .withBody("Not Found")
                )
                .willSetStateTo("ALLOCATED")
        );

        // Second poll: queue item is allocated with build #25
        stubFor(
            get(urlEqualTo("/queue/item/120/api/json"))
                .inScenario("Queue404Retry")
                .whenScenarioStateIs("ALLOCATED")
                .willReturn(
                    okJson("""
                        {
                          "id": 120,
                          "executable": {
                            "number": 25,
                            "url": "http://localhost:8082/job/test-job/25/"
                          }
                        }
                        """)
                )
        );

        stubFor(
            get(urlEqualTo("/job/test-job/25/api/json"))
                .willReturn(
                    okJson("""
                        {
                          "number": 25,
                          "building": false,
                          "result": "SUCCESS"
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

        assertThat(output.getBuildNumber(), is(25));
        assertThat(output.getResult(), is("SUCCESS"));
    }

    @Test
    void triggerJenkinsJobBadAllowedStatusesThrowsBeforePost(WireMockRuntimeInfo wm) {
        stubFor(
            post(urlEqualTo("/job/test-job/buildWithParameters"))
                .willReturn(aResponse().withStatus(201))
        );

        RunContext runContext = runContextFactory.of(Map.of("badStatus", List.of("success")));

        JobBuild jobBuild = JobBuild.builder()
            .jobName(Property.ofValue("test-job"))
            .serverUrl(Property.ofValue(wm.getHttpBaseUrl()))
            .wait(Property.ofValue(true))
            .allowedStatuses(Property.ofExpression("{{ badStatus }}"))
            .build();

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> jobBuild.run(runContext)
        );

        assertThat(
            exception.getMessage(),
            containsString("allowedStatuses must contain only [SUCCESS, UNSTABLE, FAILURE, NOT_BUILT, ABORTED] (upper case)")
        );
        verify(0, postRequestedFor(urlEqualTo("/job/test-job/buildWithParameters")));
    }

    @Test
    void triggerJenkinsJobLongErrorBodyIsTruncated(WireMockRuntimeInfo wm) {
        String longBody = "A".repeat(1500);
        stubFor(
            post(urlEqualTo("/job/test-job/buildWithParameters"))
                .willReturn(
                    aResponse()
                        .withStatus(500)
                        .withBody(longBody)
                )
        );

        RunContext runContext = runContextFactory.of(Map.of());

        JobBuild jobBuild = JobBuild.builder()
            .jobName(Property.ofValue("test-job"))
            .serverUrl(Property.ofValue(wm.getHttpBaseUrl()))
            .build();

        var exception = assertThrows(
            HttpClientResponseException.class,
            () -> jobBuild.run(runContext)
        );

        assertThat(exception.getMessage(), containsString("... (truncated, 1500 bytes)"));
        assertThat(exception.getMessage(), not(containsString(longBody)));
    }
}
