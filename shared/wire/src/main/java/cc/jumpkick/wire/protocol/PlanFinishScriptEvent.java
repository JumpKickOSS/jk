// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Terminal of a {@code jk script} prepare: the compiled script's main class, classpath, classes dir and the kotlinc it used (kind {@code script}). */
public record PlanFinishScriptEvent(
        String dir,
        boolean success,
        @Nullable String scriptMainClass,
        List<String> scriptClasspath,
        @Nullable String scriptClassesDir,
        @Nullable String scriptKotlincBin,
        @Nullable String scriptStdlib) {
    public String encode() {
        return RequestJson.request(EngineProtocol.BUILDPLAN_FINISH)
                .string("kind", "script")
                .string("dir", dir)
                .bool("success", success)
                .string("scriptMainClass", scriptMainClass)
                .array("scriptClasspath", scriptClasspath)
                .string("scriptClassesDir", scriptClassesDir)
                .string("scriptKotlincBin", scriptKotlincBin)
                .string("scriptStdlib", scriptStdlib)
                .finish();
    }

    public static PlanFinishScriptEvent decode(String json) {
        return new PlanFinishScriptEvent(
                Jsonl.requiredStr(json, "dir"),
                Jsonl.bool(json, "success", false),
                Jsonl.str(json, "scriptMainClass"),
                Jsonl.strArray(json, "scriptClasspath"),
                Jsonl.str(json, "scriptClassesDir"),
                Jsonl.str(json, "scriptKotlincBin"),
                Jsonl.str(json, "scriptStdlib"));
    }
}
