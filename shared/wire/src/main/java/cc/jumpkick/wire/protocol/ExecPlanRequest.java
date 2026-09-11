// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/** Plan how to run the project's program ({@code jk run} / {@code jk install}). */
public record ExecPlanRequest(
        @Nullable String dir,
        @Nullable String cache,
        @Nullable String kind,
        @Nullable String mainOverride,
        @Nullable String binName,
        @Nullable String binDir,
        @Nullable String libDir,
        /** {@link cc.jumpkick.config.DebugJvm#spelling() Spelling} of the JDWP listener for the launched JVM; null for none. */
        @Nullable String debugJvm) {

    public String encode() {
        return RequestJson.request(EngineProtocol.EXEC_PLAN_REQUEST)
                .string("dir", dir)
                .string("cache", cache)
                .string("kind", kind)
                .string("mainOverride", mainOverride)
                .string("binName", binName)
                .string("binDir", binDir)
                .string("libDir", libDir)
                .optionalNonBlankString(ProtoJobs.DEBUG_JVM, debugJvm)
                .finish();
    }

    public static ExecPlanRequest decode(String json) {
        return new ExecPlanRequest(
                Jsonl.str(json, "dir"),
                Jsonl.str(json, "cache"),
                Jsonl.str(json, "kind"),
                Jsonl.str(json, "mainOverride"),
                Jsonl.str(json, "binName"),
                Jsonl.str(json, "binDir"),
                Jsonl.str(json, "libDir"),
                Jsonl.str(json, ProtoJobs.DEBUG_JVM));
    }
}
