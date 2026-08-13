// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.listen;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.protocol.EngineProtocol;
import org.junit.jupiter.api.Test;

class WireEventSinkTest {

    @Test
    void plan_start_encodes_existing_wire_token() {
        String line = WireEventSink.encode(new EngineEvent.PlanStart("d", "build", 1, 2, 3, 0, false));
        assertThat(EngineProtocol.typeOf(line)).isEqualTo(EngineProtocol.BUILDPLAN_START);
        assertThat(line).contains("\"dir\":\"d\"");
    }

    @Test
    void recording_sink_keeps_order() {
        RecordingEventSink rec = new RecordingEventSink();
        rec.emit(new EngineEvent.StepStart("d", "javac", "compile", 1));
        rec.emit(new EngineEvent.Label("d", "javac", "ok"));
        assertThat(rec.events()).hasSize(2);
        assertThat(rec.events().getFirst()).isInstanceOf(EngineEvent.StepStart.class);
    }

    @Test
    void workspace_events_keep_existing_wire_tokens() {
        assertThat(EngineProtocol.typeOf(WireEventSink.encode(new EngineEvent.Preflight("lock", 0, 1, "locking"))))
                .isEqualTo(EngineProtocol.PREFLIGHT);
        assertThat(EngineProtocol.typeOf(WireEventSink.encode(new EngineEvent.PlanDone(3))))
                .isEqualTo(EngineProtocol.PLAN_DONE);
        assertThat(EngineProtocol.typeOf(WireEventSink.encode(new EngineEvent.ModuleStart("d"))))
                .isEqualTo(EngineProtocol.MODULE_START);
        assertThat(EngineProtocol.typeOf(WireEventSink.encode(new EngineEvent.Eta(9))))
                .isEqualTo(EngineProtocol.ETA);
    }

    @Test
    void composite_fans_out() {
        RecordingEventSink a = new RecordingEventSink();
        RecordingEventSink b = new RecordingEventSink();
        new CompositeEventSink(a, b).emit(new EngineEvent.PlanDone(1));
        assertThat(a.events()).hasSize(1);
        assertThat(b.events()).hasSize(1);
    }
}
