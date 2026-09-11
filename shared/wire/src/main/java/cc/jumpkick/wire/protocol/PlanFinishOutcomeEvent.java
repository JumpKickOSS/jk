// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.run.TestSummary;
import org.jspecify.annotations.Nullable;

/**
 * A build plan's terminal carrying the build outcome ({@code null} when not applicable) and, when a
 * test phase ran ({@code total >= 0}), its counts as the one {@link TestSummary#WIRE_KEY} object
 * (see {@link EngineProtocol#BUILDPLAN_FINISH}).
 */
public record PlanFinishOutcomeEvent(
        String dir,
        boolean success,
        @Nullable String buildOutcome,
        long total,
        long succeeded,
        long failed,
        long skipped) {
    public String encode() {
        RequestJson json = RequestJson.request(EngineProtocol.BUILDPLAN_FINISH)
                .string("kind", "build")
                .string("dir", dir)
                .bool("success", success)
                .string("buildOutcome", buildOutcome);
        if (total >= 0) json.token(TestSummary.WIRE_KEY, TestSummary.countsJson(total, succeeded, failed, skipped));
        return json.finish();
    }

    public static PlanFinishOutcomeEvent decode(String json) {
        String tests = Jsonl.nested(json, TestSummary.WIRE_KEY);
        return new PlanFinishOutcomeEvent(
                Jsonl.requiredStr(json, "dir"),
                Jsonl.bool(json, "success", false),
                Jsonl.str(json, "buildOutcome"),
                tests == null ? -1 : Jsonl.longValue(tests, "total", -1),
                tests == null ? -1 : Jsonl.longValue(tests, "succeeded", -1),
                tests == null ? -1 : Jsonl.longValue(tests, "failed", -1),
                tests == null ? -1 : Jsonl.longValue(tests, "skipped", -1));
    }
}
