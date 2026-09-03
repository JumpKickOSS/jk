// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/** Compute the IDE wire model engine-side; generation stays client-side. */
public record IdeModelRequest(
        @Nullable String dir,
        @Nullable String cache,
        @Nullable String jdksDir) {

    public String encode() {
        return RequestJson.request(EngineProtocol.IDE_MODEL_REQUEST)
                .string("dir", dir)
                .string("cache", cache)
                .string(ProtoJobs.JDKS_DIR, jdksDir)
                .finish();
    }

    public static IdeModelRequest decode(String json) {
        return new IdeModelRequest(
                Jsonl.str(json, "dir"), Jsonl.str(json, "cache"), Jsonl.str(json, ProtoJobs.JDKS_DIR));
    }
}
