// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.Task;
import cc.jumpkick.wire.protocol.ProtoEvents;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every single-plan reader carries the plan's {@code warn} events on its terminal result, which is
 * what the console renders above its result line, and hands each to the live listener exactly once.
 */
class PlanReaderWarningsTest {

    private static final String WARNING = "the formatter skipped 1 generated file";

    private static BufferedReader stream(String... lines) {
        return new BufferedReader(new StringReader(String.join("\n", lines) + "\n"));
    }

    /** Records live warnings and the results its plan finished with. */
    private static final class Recorder implements BuildPlanListener {
        final List<String> live = new ArrayList<>();
        final List<BuildPlanResult> finished = new ArrayList<>();

        @Override
        public void warn(String step, String code, String message) {
            live.add(message);
        }

        @Override
        public void planFinish(BuildPlanResult result) {
            finished.add(result);
        }
    }

    private static void assertCarried(BuildPlanResult result, Recorder recorder) {
        assertThat(recorder.live).containsExactly(WARNING);
        assertThat(result.warnings()).singleElement().satisfies(d -> {
            assertThat(d.step()).isEqualTo("fmt");
            assertThat(d.code()).isEqualTo("skipped");
            assertThat(d.message()).isEqualTo(WARNING);
        });
        assertThat(recorder.finished)
                .singleElement()
                .extracting(BuildPlanResult::warnings)
                .isEqualTo(result.warnings());
    }

    @Test
    void the_hosted_reader_carries_a_warning_on_its_result() throws IOException {
        Recorder recorder = new Recorder();

        EnginePluginAdapter.HostedFinish finish = WireStream.pumpJob(
                stream(
                        ProtoEvents.planDone(0),
                        ProtoEvents.warn("/p", "fmt", "skipped", WARNING),
                        ProtoEvents.planFinish("/p", true)),
                null,
                EnginePluginAdapter.hostedDecoder("format", steps -> recorder, (type, line) -> {}, null));

        assertCarried(finish.result(), recorder);
    }

    @Test
    void the_sync_reader_carries_a_warning_on_its_result() throws IOException {
        Recorder recorder = new Recorder();

        BuildPlanResult result = WireStream.pumpJob(
                stream(
                        ProtoEvents.planDone(0),
                        ProtoEvents.warn("/p", "fmt", "skipped", WARNING),
                        ProtoEvents.planFinishSync("/p", true, 1, 2)),
                null,
                EngineResolveAdapter.syncDecoder(steps -> recorder, new long[1], new long[1]));

        assertCarried(result, recorder);
    }

    @Test
    void the_cascade_reader_carries_each_modules_warnings_on_that_modules_result() throws IOException {
        Recorder recorder = new Recorder();
        List<BuildPlanResult> moduleResults = new ArrayList<>();
        EngineRequests.LockHandler handler = new EngineRequests.LockHandler() {
            @Override
            public BuildPlanListener onModuleStart(String dir, String coord, List<Task> steps) {
                return recorder;
            }

            @Override
            public void onModuleFinish(String dir, BuildPlanResult result, EngineRequests.LockCounts counts) {
                moduleResults.add(result);
            }
        };

        WireStream.pumpJob(
                stream(
                        ProtoEvents.lockModule("/p", "g:a"),
                        ProtoEvents.planDone(0),
                        ProtoEvents.warn("/p", "fmt", "skipped", WARNING),
                        ProtoEvents.planFinishLock("/p", true, 1, 0, 0, 0, 0, List.of()),
                        ProtoEvents.lockModule("/q", "g:b"),
                        ProtoEvents.planDone(0),
                        ProtoEvents.planFinishLock("/q", true, 1, 0, 0, 0, 0, List.of()),
                        ProtoEvents.lockFinish(true, 0, List.of(), 0)),
                null,
                new EngineResolveAdapter.CascadeDecoder(handler, "lock"));

        assertThat(recorder.live).containsExactly(WARNING);
        assertThat(moduleResults).hasSize(2);
        assertThat(moduleResults.get(0).warnings())
                .singleElement()
                .extracting(BuildPlanResult.Diagnostic::message)
                .isEqualTo(WARNING);
        assertThat(moduleResults.get(1).warnings()).isEmpty();
    }

    @Test
    void a_warning_before_plan_done_reaches_the_result_without_a_listener() throws IOException {
        Recorder recorder = new Recorder();

        BuildPlanResult result = EngineEventDecoder.streamSingleBuildPlanEvents(
                stream(
                        ProtoLifecycle.jobStart(46, "test", "/p", 9),
                        ProtoEvents.warn("/p", "fmt", "skipped", WARNING),
                        ProtoEvents.planDone(0),
                        ProtoEvents.planFinish("/p", true, false)),
                steps -> recorder,
                null,
                null);

        assertThat(recorder.live).isEmpty();
        assertThat(result.warnings())
                .singleElement()
                .extracting(BuildPlanResult.Diagnostic::message)
                .isEqualTo(WARNING);
    }
}
