package io.kestra.plugin.jenkins;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@KestraTest
@WireMockTest(httpPort = 8082)
class TriggerTest {

    @Inject
    private RunContextFactory runContextFactory;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        reset();
    }

    @Test
    void triggerLifecycleWithKvWatermark(WireMockRuntimeInfo wm) throws Exception {
        String initialBuildsJson = """
            {
              "builds": [
                {
                  "number": 3,
                  "url": "http://localhost:8082/job/trigger-lifecycle-job/3/",
                  "result": "SUCCESS",
                  "building": false,
                  "duration": 1200,
                  "timestamp": 1690000300000,
                  "fullDisplayName": "trigger-lifecycle-job #3"
                },
                {
                  "number": 2,
                  "url": "http://localhost:8082/job/trigger-lifecycle-job/2/",
                  "result": "SUCCESS",
                  "building": false,
                  "duration": 1100,
                  "timestamp": 1690000200000,
                  "fullDisplayName": "trigger-lifecycle-job #2"
                }
              ]
            }
            """;

        stubFor(
            get(urlEqualTo("/job/trigger-lifecycle-job/api/json?tree=builds[number,url,result,building,duration,timestamp,fullDisplayName]"))
                .willReturn(okJson(initialBuildsJson))
        );

        Trigger trigger = Trigger.builder()
            .id("jenkins-trigger-" + IdUtils.create())
            .type(Trigger.class.getName())
            .serverUrl(Property.ofValue(wm.getHttpBaseUrl()))
            .jobName(Property.ofValue("trigger-lifecycle-job"))
            .build();

        var context = TestsUtils.mockTrigger(runContextFactory, trigger);

        // 1. First poll: initializes watermark to latest completed build (#3), returns empty
        Optional<Execution> eval1 = trigger.evaluate(context.getKey(), context.getValue());
        assertThat(eval1.isPresent(), is(false));

        // 2. Second poll: still build #3, returns empty (no duplicate)
        Optional<Execution> eval2 = trigger.evaluate(context.getKey(), context.getValue());
        assertThat(eval2.isPresent(), is(false));

        // 3. New build #4 finishes
        String updatedBuildsJson = """
            {
              "builds": [
                {
                  "number": 4,
                  "url": "http://localhost:8082/job/trigger-lifecycle-job/4/",
                  "result": "SUCCESS",
                  "building": false,
                  "duration": 1300,
                  "timestamp": 1690000400000,
                  "fullDisplayName": "trigger-lifecycle-job #4"
                },
                {
                  "number": 3,
                  "url": "http://localhost:8082/job/trigger-lifecycle-job/3/",
                  "result": "SUCCESS",
                  "building": false,
                  "duration": 1200,
                  "timestamp": 1690000300000,
                  "fullDisplayName": "trigger-lifecycle-job #3"
                }
              ]
            }
            """;

        stubFor(
            get(urlEqualTo("/job/trigger-lifecycle-job/api/json?tree=builds[number,url,result,building,duration,timestamp,fullDisplayName]"))
                .willReturn(okJson(updatedBuildsJson))
        );

        // Third poll: fires for build #4 and advances watermark to 4
        Optional<Execution> eval3 = trigger.evaluate(context.getKey(), context.getValue());
        assertThat(eval3.isPresent(), is(true));
        Execution execution = eval3.get();
        assertThat(execution.getTrigger(), notNullValue());
        assertThat(execution.getTrigger().getVariables().get("buildNumber"), is(4));
        assertThat(execution.getTrigger().getVariables().get("result"), is("SUCCESS"));
        assertThat(execution.getTrigger().getVariables().get("buildUrl"), is("http://localhost:8082/job/trigger-lifecycle-job/4/"));

        // 4. Fourth poll: no new builds beyond #4, returns empty
        Optional<Execution> eval4 = trigger.evaluate(context.getKey(), context.getValue());
        assertThat(eval4.isPresent(), is(false));
    }

    @Test
    void triggerWithResultFilter(WireMockRuntimeInfo wm) throws Exception {
        String buildsJson = """
            {
              "builds": [
                {
                  "number": 5,
                  "url": "http://localhost:8082/job/trigger-fail-job/5/",
                  "result": "FAILURE",
                  "building": false,
                  "duration": 1500,
                  "timestamp": 1690000500000,
                  "fullDisplayName": "trigger-fail-job #5"
                }
              ]
            }
            """;

        stubFor(
            get(urlEqualTo("/job/trigger-fail-job/api/json?tree=builds[number,url,result,building,duration,timestamp,fullDisplayName]"))
                .willReturn(okJson(buildsJson))
        );

        Trigger trigger = Trigger.builder()
            .id("jenkins-fail-trigger-" + IdUtils.create())
            .type(Trigger.class.getName())
            .serverUrl(Property.ofValue(wm.getHttpBaseUrl()))
            .jobName(Property.ofValue("trigger-fail-job"))
            .result(Property.ofValue("FAILURE"))
            .initialBuildNumber(Property.ofValue(5))
            .build();

        var context = TestsUtils.mockTrigger(runContextFactory, trigger);

        Optional<Execution> eval = trigger.evaluate(context.getKey(), context.getValue());
        assertThat(eval.isPresent(), is(true));
        assertThat(eval.get().getTrigger().getVariables().get("buildNumber"), is(5));
        assertThat(eval.get().getTrigger().getVariables().get("result"), is("FAILURE"));
    }
}
