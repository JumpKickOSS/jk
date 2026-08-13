// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.listen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
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
    void http_path_is_hooks_only() {
        AtomicInteger etas = new AtomicInteger();
        BridgingWorkspaceListener listener =
                new BridgingWorkspaceListener("ws", NoopEventSink.INSTANCE, new BridgingWorkspaceListener.Hooks() {
                    @Override
                    public void eta(long remainingMs) {
                        etas.incrementAndGet();
                    }
                });
        listener.onEtaEstimate(42);
        assertThat(etas.get()).isEqualTo(1);
    }
}
