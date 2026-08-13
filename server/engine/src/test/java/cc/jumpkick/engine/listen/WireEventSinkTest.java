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
}
