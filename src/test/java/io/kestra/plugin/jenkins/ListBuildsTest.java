package io.kestra.plugin.jenkins;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@KestraTest
@WireMockTest(httpPort = 8082)
class ListBuildsTest {

    @Inject
    private RunContextFactory runContextFactory;

    private static final String BUILDS_JSON = """
        {
          "_class": "hudson.model.FreeStyleProject",
          "builds": [
            {
              "number": 10,
              "url": "http://localhost:8082/job/test-job/10/",
              "result": "SUCCESS",
              "building": false,
              "duration": 1000,
              "timestamp": 1690000300000,
              "fullDisplayName": "test-job #10"
            },
            {
              "number": 9,
              "url": "http://localhost:8082/job/test-job/9/",
              "result": "FAILURE",
              "building": false,
              "duration": 2000,
              "timestamp": 1690000200000,
              "fullDisplayName": "test-job #9"
            },
            {
              "number": 8,
              "url": "http://localhost:8082/job/test-job/8/",
              "result": "FAILURE",
              "building": false,
              "duration": 1500,
              "timestamp": 1690000100000,
              "fullDisplayName": "test-job #8"
            }
          ]
        }
        """;

    @Test
    void listAllBuilds(WireMockRuntimeInfo wm) throws Exception {
        stubFor(
            get(urlMatching("/job/test-job/api/json\\?tree=builds.*"))
                .willReturn(okJson(BUILDS_JSON))
        );

        RunContext runContext = runContextFactory.of(Map.of());

        ListBuilds task = ListBuilds.builder()
            .jobName(Property.ofValue("test-job"))
            .serverUrl(Property.ofValue(wm.getHttpBaseUrl()))
            .build();

        ListBuilds.Output output = task.run(runContext);

        assertThat(output.getSize(), is(3));
        assertThat(output.getBuilds().get(0).getNumber(), is(10));
        assertThat(output.getBuilds().get(0).getResult(), is("SUCCESS"));
    }

    @Test
    void listFailedBuilds(WireMockRuntimeInfo wm) throws Exception {
        stubFor(
            get(urlMatching("/job/test-job/api/json\\?tree=builds.*"))
                .willReturn(okJson(BUILDS_JSON))
        );

        RunContext runContext = runContextFactory.of(Map.of());

        ListBuilds task = ListBuilds.builder()
            .jobName(Property.ofValue("test-job"))
            .serverUrl(Property.ofValue(wm.getHttpBaseUrl()))
            .result(Property.ofValue(BuildResult.FAILURE))
            .build();

        ListBuilds.Output output = task.run(runContext);

        assertThat(output.getSize(), is(2));
        assertThat(output.getBuilds().get(0).getNumber(), is(9));
        assertThat(output.getBuilds().get(1).getNumber(), is(8));
        assertThat(output.getBuilds().get(0).getResult(), is("FAILURE"));
    }

    @Test
    void listBuildsSince(WireMockRuntimeInfo wm) throws Exception {
        stubFor(
            get(urlMatching("/job/test-job/api/json\\?tree=builds.*"))
                .willReturn(okJson(BUILDS_JSON))
        );

        RunContext runContext = runContextFactory.of(Map.of());

        ListBuilds task = ListBuilds.builder()
            .jobName(Property.ofValue("test-job"))
            .serverUrl(Property.ofValue(wm.getHttpBaseUrl()))
            .since(Property.ofValue(Instant.ofEpochMilli(1690000250000L)))
            .build();

        ListBuilds.Output output = task.run(runContext);

        assertThat(output.getSize(), is(1));
        assertThat(output.getBuilds().get(0).getNumber(), is(10));
    }

    @Test
    void listBuildsWithMax(WireMockRuntimeInfo wm) throws Exception {
        stubFor(
            get(urlMatching("/job/test-job/api/json\\?tree=builds.*"))
                .willReturn(okJson(BUILDS_JSON))
        );

        RunContext runContext = runContextFactory.of(Map.of());

        ListBuilds task = ListBuilds.builder()
            .jobName(Property.ofValue("test-job"))
            .serverUrl(Property.ofValue(wm.getHttpBaseUrl()))
            .max(Property.ofValue(1))
            .build();

        ListBuilds.Output output = task.run(runContext);

        assertThat(output.getSize(), is(1));
        assertThat(output.getBuilds().get(0).getNumber(), is(10));
    }

    @Test
    void listBuildsFetchOne(WireMockRuntimeInfo wm) throws Exception {
        stubFor(
            get(urlMatching("/job/test-job/api/json\\?tree=builds.*"))
                .willReturn(okJson(BUILDS_JSON))
        );

        RunContext runContext = runContextFactory.of(Map.of());

        ListBuilds task = ListBuilds.builder()
            .jobName(Property.ofValue("test-job"))
            .serverUrl(Property.ofValue(wm.getHttpBaseUrl()))
            .fetchType(Property.ofValue(FetchType.FETCH_ONE))
            .build();

        ListBuilds.Output output = task.run(runContext);

        assertThat(output.getSize(), is(3));
        assertThat(output.getBuild(), notNullValue());
        assertThat(output.getBuild().getNumber(), is(10));
    }

    @Test
    void listBuildsStore(WireMockRuntimeInfo wm) throws Exception {
        stubFor(
            get(urlMatching("/job/test-job/api/json\\?tree=builds.*"))
                .willReturn(okJson(BUILDS_JSON))
        );

        RunContext runContext = runContextFactory.of(Map.of());

        ListBuilds task = ListBuilds.builder()
            .jobName(Property.ofValue("test-job"))
            .serverUrl(Property.ofValue(wm.getHttpBaseUrl()))
            .fetchType(Property.ofValue(FetchType.STORE))
            .build();

        ListBuilds.Output output = task.run(runContext);

        assertThat(output.getSize(), is(3));
        assertThat(output.getUri(), notNullValue());

        try (
            var is = runContext.storage().getFile(output.getUri());
            var reader = new BufferedReader(new InputStreamReader(is))
        ) {
            long lines = reader.lines().count();
            assertThat(lines, is(3L));
        }
    }

    @Test
    void listBuildsStoreEmptyReturnsUriWithSizeZero(WireMockRuntimeInfo wm) throws Exception {
        stubFor(
            get(urlMatching("/job/test-job/api/json\\?tree=builds.*"))
                .willReturn(
                    okJson("""
                        {
                          "builds": []
                        }
                        """)
                )
        );

        RunContext runContext = runContextFactory.of(Map.of());

        ListBuilds task = ListBuilds.builder()
            .jobName(Property.ofValue("test-job"))
            .serverUrl(Property.ofValue(wm.getHttpBaseUrl()))
            .fetchType(Property.ofValue(FetchType.STORE))
            .build();

        ListBuilds.Output output = task.run(runContext);

        assertThat(output.getSize(), is(0));
        assertThat(output.getUri(), notNullValue());

        try (
            var is = runContext.storage().getFile(output.getUri());
            var reader = new BufferedReader(new InputStreamReader(is))
        ) {
            long lines = reader.lines().count();
            assertThat(lines, is(0L));
        }
    }
}
