// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.listen.SseFrame;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.wire.protocol.EventEnvelope;
import cc.jumpkick.wire.protocol.ModuleFinishEvent;
import cc.jumpkick.wire.protocol.TaskFinishEvent;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * An SSE frame is its event's wire record under {@code schema}, {@code type} and {@code jid}: the
 * dashboard and MCP read the record's fields under the record's names, nothing renamed or lost.
 */
class SseFrameTest {

    @Test
    void a_frame_is_the_envelope_then_the_records_fields() {
        String record = new TaskFinishEvent("/ws/a", "compile", "compile", "SUCCESS", 120, 30, "3 sources").encode();
        String frame = SseFrame.of("task-finish", 7, record).toString();

        assertThat(EventEnvelope.keys(frame))
                .containsExactly(
                        "schema", "type", "jid", "dir", "task", "stage", "status", "millis", "waitMillis", "label");
        assertThat(frame).contains("\"jid\":7").contains("\"millis\":120").contains("\"waitMillis\":30");
    }

    @Test
    void a_module_finish_frame_carries_every_field_the_wire_does() {
        String record =
                new ModuleFinishEvent("/ws/a", "g:a", false, 1, 42, true, false, true, null, null, null).encode();
        List<String> wire = EventEnvelope.keys(record);
        List<String> frame =
                EventEnvelope.keys(SseFrame.of("module-finish", 7, record).toString());

        assertThat(frame)
                .containsAll(wire.stream().filter(k -> !k.equals("schema")).toList());
    }

    @Test
    void a_diagnostic_frame_is_the_error_line_record_named_error() {
        var d = new BuildPlanResult.Diagnostic(
                "run-tests",
                "test-failure",
                "boom",
                "a.T#m",
                "java.lang.AssertionError",
                "g:a",
                "junit-jupiter",
                "a.T",
                "m",
                "at a.T.m",
                "a/T.java",
                12,
                10,
                List.of("x"),
                2,
                "",
                "");
        Object parsed =
                MiniJson.parse(SsePublisher.errorEventJson(7, "/ws/a", d).toString());

        Map<?, ?> frame = (Map<?, ?>) Objects.requireNonNull(parsed);
        assertThat(frame.get("type")).isEqualTo("error");
        assertThat(frame.get("testClass")).isEqualTo("a.T");
        assertThat(((Number) Objects.requireNonNull(frame.get("line"))).intValue())
                .isEqualTo(12);
        assertThat(frame.get("snippet")).isEqualTo(List.of("x"));
    }
}
