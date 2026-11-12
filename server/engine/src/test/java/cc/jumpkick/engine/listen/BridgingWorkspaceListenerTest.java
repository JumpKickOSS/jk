// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.listen;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.wire.runtime.ModulePlan;
import cc.jumpkick.wire.runtime.StepCost;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class BridgingWorkspaceListenerTest {

    @Test
    void preflight_emits_stage_and_resolve_phase() {
        RecordingEventSink sink = new RecordingEventSink();
        AtomicInteger preflights = new AtomicInteger();
        BridgingWorkspaceListener listener =
                new BridgingWorkspaceListener("ws", sink, new BridgingWorkspaceListener.Hooks() {
                    @Override
                    public void preflight(String stage, int done, int total) {
                        preflights.incrementAndGet();
                    }
                });
        listener.onPreflight("lock", 0, 1, "locking");
        assertThat(sink.events()).hasSize(2);
        assertThat(sink.events().getFirst()).isInstanceOf(EngineEvent.Preflight.class);
        EngineEvent.InvocationPhase phase =
                (EngineEvent.InvocationPhase) sink.events().get(1);
        assertThat(phase.name()).isEqualTo("resolve");
        assertThat(phase.status()).isEqualTo("start");
        assertThat(preflights.get()).isEqualTo(1);
    }

    @Test
    void eta_rides_the_sink_on_every_path() {
        RecordingEventSink sink = new RecordingEventSink();
        BridgingWorkspaceListener listener =
                new BridgingWorkspaceListener("ws", sink, new BridgingWorkspaceListener.Hooks() {});
        listener.onEtaEstimate(42);
        assertThat(sink.events()).hasSize(1);
        assertThat(((EngineEvent.Eta) sink.events().get(0)).remainingMs()).isEqualTo(42);
    }

    @Test
    void a_workspace_module_step_finish_carries_what_the_estimate_priced_it_at() {
        // JK-626GS: the corpus's first run carried no rung on any of its 920 task-finish events; the
        // workspace's hooks did not pass the lookup through.
        RecordingEventSink sink = new RecordingEventSink();
        StepCost compile = new StepCost("compile-java", 20, StepCost.Kind.PRE, 10, 40, 0, StepCost.Rung.CALIBRATION);
        BridgingWorkspaceListener listener =
                new BridgingWorkspaceListener("ws", sink, new BridgingWorkspaceListener.Hooks() {
                    @Override
                    public BridgingPlanListener.Hooks planHooks(String dir) {
                        return new BridgingPlanListener.Hooks() {
                            @Override
                            public @Nullable StepCost priced(String d, String step) {
                                return step.equals("compile-java") ? compile : null;
                            }
                        };
                    }
                });
        Path lib = Path.of("/ws/lib");
        var plan = listener.onModuleStart(
                new ModulePlan(lib, "g:lib", BuildPlan.builder("lib").build(), 1, false, Path.of("cache")));
        plan.stepFinish("compile-java", "compile", TaskStatus.SUCCESS, Duration.ofMillis(2_500), Duration.ZERO);

        EngineEvent.StepFinish finish = sink.events().stream()
                .filter(EngineEvent.StepFinish.class::isInstance)
                .map(EngineEvent.StepFinish.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(finish.rung()).isEqualTo("CALIBRATION");
        assertThat(finish.predictedMs()).isEqualTo(20L * 150);
    }
}
