// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Terminal of a {@code jk tool} plan: the resolved tool's coordinate, main class and classpath (kind {@code tool}). */
public record PlanFinishToolEvent(
        String dir,
        boolean success,
        @Nullable String toolCoord,
        @Nullable String toolMainClass,
        List<String> toolClasspath) {
    public String encode() {
        return RequestJson.request(EngineProtocol.BUILDPLAN_FINISH)
                .string("kind", "tool")
                .string("dir", dir)
                .bool("success", success)
                .string("toolCoord", toolCoord)
                .string("toolMainClass", toolMainClass)
                .array("toolClasspath", toolClasspath)
                .finish();
    }

    public static PlanFinishToolEvent decode(String json) {
        return new PlanFinishToolEvent(
                Jsonl.requiredStr(json, "dir"),
                Jsonl.bool(json, "success", false),
                Jsonl.str(json, "toolCoord"),
                Jsonl.str(json, "toolMainClass"),
                Jsonl.strArray(json, "toolClasspath"));
    }
}
