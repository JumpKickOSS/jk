// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.plugin.protocol.Jsonl;
import cc.jumpkick.run.PipelineResult;
import cc.jumpkick.run.PipelineView;
import cc.jumpkick.run.StepStatus;
import java.time.Duration;
import java.time.Instant;

/**
 * Stable wire format for pipeline events as one-JSON-object-per-line text. Shared by {@link
 * JsonlListener} (stdout for {@code --output json}/{@code jsonl}) and {@link EventLogListener}
 * (always-on under the cache run log). Centralising the shape here means agents, CI, and future MCP
 * tools share one schema — see {@code docs/machine-output.md}.
 *
 * <p>Every object includes {@code schema} ({@link #SCHEMA}), {@code ts} (epoch ms), and {@code type}.
 */
final class JsonlShape {

    /**
     * Stay on {@code 1} until jk <strong>1.0</strong> — no pre-release version churn (see
     * {@code docs/architecture.md} schema freeze). Additive fields only.
     */
    static final int SCHEMA = 1;

    private JsonlShape() {}

    /** Shared prefix: schema + ts + type. */
    private static StringBuilder open(String type) {
        return new StringBuilder(96)
                .append("{\"schema\":")
                .append(SCHEMA)
                .append(",\"ts\":")
                .append(nowMillis())
                .append(",\"type\":")
                .append(js(type));
    }

    static String pipelineStart(PipelineView v) {
        return open("pipeline-start")
                .append(",\"pipeline\":")
                .append(js(v.pipelineName()))
                .append(",\"denominator\":")
                .append(v.denominator())
                .append(",\"steps\":")
                .append(v.stepsTotal())
                .append('}')
                .toString();
    }

    static String stepStart(String step, String phase, int ticks) {
        return open("step-start")
                .append(",\"step\":")
                .append(js(step))
                .append(",\"phase\":")
                .append(js(phase))
                .append(",\"ticks\":")
                .append(ticks)
                .append('}')
                .toString();
    }

    static String progress(String step, int delta, PipelineView v) {
        return open("progress")
                .append(",\"step\":")
                .append(js(step))
                .append(",\"delta\":")
                .append(delta)
                .append(",\"numerator\":")
                .append(v.numerator())
                .append(",\"denominator\":")
                .append(v.denominator())
                .append('}')
                .toString();
    }

    static String tickUpdate(String step, int delta, PipelineView v) {
        return open("tick-update")
                .append(",\"step\":")
                .append(js(step))
                .append(",\"delta\":")
                .append(delta)
                .append(",\"denominator\":")
                .append(v.denominator())
                .append('}')
                .toString();
    }

    static String label(String step, String label) {
        return open("label")
                .append(",\"step\":")
                .append(js(step))
                .append(",\"label\":")
                .append(js(label))
                .append('}')
                .toString();
    }

    static String output(String step, String line) {
        return open("output")
                .append(",\"step\":")
                .append(js(step))
                .append(",\"line\":")
                .append(js(line))
                .append('}')
                .toString();
    }

    static String warn(String step, String code, String msg) {
        return open("warn")
                .append(",\"step\":")
                .append(js(step))
                .append(",\"code\":")
                .append(js(code))
                .append(",\"message\":")
                .append(js(msg))
                .append('}')
                .toString();
    }

    static String error(String step, String code, String msg) {
        return error(step, code, msg, "", "");
    }

    /**
     * Error event with optional discrete {@code test} / {@code exceptionClass} fields — emitted (in
     * addition to {@code message}) only when non-empty, so a test failure's parts stay separate on
     * the wire without bloating the common diagnostic shape.
     */
    static String error(String step, String code, String msg, String test, String exceptionClass) {
        StringBuilder sb = open("error")
                .append(",\"step\":")
                .append(js(step))
                .append(",\"code\":")
                .append(js(code))
                .append(",\"message\":")
                .append(js(msg));
        if (test != null && !test.isEmpty()) sb.append(",\"test\":").append(js(test));
        if (exceptionClass != null && !exceptionClass.isEmpty())
            sb.append(",\"exceptionClass\":").append(js(exceptionClass));
        return sb.append('}').toString();
    }

    static String stepFinish(String step, String phase, StepStatus status, Duration duration) {
        return open("step-finish")
                .append(",\"step\":")
                .append(js(step))
                .append(",\"phase\":")
                .append(js(phase))
                .append(",\"status\":")
                .append(js(status.name()))
                .append(",\"duration_ms\":")
                .append(duration.toMillis())
                .append('}')
                .toString();
    }

    static String pipelineFinish(PipelineResult r) {
        return open("pipeline-finish")
                .append(",\"pipeline\":")
                .append(js(r.pipelineName()))
                .append(",\"success\":")
                .append(r.success())
                .append(",\"duration_ms\":")
                .append(r.duration().toMillis())
                .append(",\"warnings\":")
                .append(r.warnings().size())
                .append(",\"errors\":")
                .append(r.errors().size())
                .append('}')
                .toString();
    }

    /**
     * JSON string escaping — delegates to the shared {@link Jsonl#quote} codec (same escaping the
     * worker wire protocol uses) so there's one implementation to keep correct. A {@code null}
     * encodes as the bare literal {@code null}.
     */
    static String js(String s) {
        return Jsonl.quote(s);
    }

    static long nowMillis() {
        return Instant.now().toEpochMilli();
    }
}
