// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.listen;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.SecretRedactor;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.BuildPlanView;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BridgingPlanListenerTest {

    private static final String SECRET = "jk-1929-must-not-leak-token";

    @Test
    void label_is_redacted_before_the_sink(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve(".env"), "TOKEN=" + SECRET + "\n");
        RecordingEventSink sink = new RecordingEventSink();
        BridgingPlanListener listener =
                new BridgingPlanListener(tmp.toString(), sink, new BridgingPlanListener.Hooks() {});
        listener.label("javac", "token=" + SECRET);
        assertThat(sink.events()).singleElement().isInstanceOf(EngineEvent.Label.class);
        EngineEvent.Label ev = (EngineEvent.Label) sink.events().getFirst();
        assertThat(ev.text()).doesNotContain(SECRET).contains(SecretRedactor.MASK);
    }

    @Test
    void plan_finish_runs_hooks_before_the_terminal_line() {
        RecordingEventSink sink = new RecordingEventSink();
        AtomicInteger sinkSizeAtHook = new AtomicInteger(-1);
        BridgingPlanListener listener = new BridgingPlanListener(
                "d",
                sink,
                new BridgingPlanListener.Hooks() {
                    @Override
                    public void planFinished(String dir, BuildPlanResult result) {
                        sinkSizeAtHook.set(sink.events().size());
                    }
                },
                result -> "FINISH");
        BuildPlanResult result = new BuildPlanResult(
                "build",
                false,
                Duration.ZERO,
                List.of(),
                List.of(),
                List.of(new BuildPlanResult.Diagnostic("javac", "E", "boom")),
                false);
        listener.planFinish(result);
        assertThat(sink.events()).hasSize(2);
        assertThat(sink.events().getFirst()).isInstanceOf(EngineEvent.PlanDiagnostic.class);
        assertThat(sink.events().getLast()).isInstanceOf(EngineEvent.PlanFinishLine.class);
        // Hook saw the diagnostic burst, not the terminal line — timeline/slot release stay first.
        assertThat(sinkSizeAtHook.get()).isEqualTo(1);
    }

    @Test
    void plan_start_reaches_the_sink_and_hooks() {
        RecordingEventSink sink = new RecordingEventSink();
        AtomicInteger progress = new AtomicInteger();
        BridgingPlanListener listener = new BridgingPlanListener("d", sink, new BridgingPlanListener.Hooks() {
            @Override
            public void planProgress(String dir, BuildPlanView view) {
                progress.incrementAndGet();
            }
        });
        listener.planStart(new BuildPlanView("build", 1, 2, 3, 0, false));
        assertThat(sink.events()).singleElement().isInstanceOf(EngineEvent.PlanStart.class);
        assertThat(progress.get()).isEqualTo(1);
    }
}
