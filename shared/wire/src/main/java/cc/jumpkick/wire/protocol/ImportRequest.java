// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** A foreign-build import request. */
public record ImportRequest(
        @Nullable String source,
        @Nullable String out,
        @Nullable String baseDir,
        @Nullable String tmpDir,
        boolean force,
        @Nullable String report,
        @Nullable String cache,
        List<String> profiles) {

    public String encode() {
        return RequestJson.request(EngineProtocol.IMPORT_REQUEST)
                .string("source", source)
                .string("out", out)
                .string("baseDir", baseDir)
                .string("tmpDir", tmpDir)
                .bool("force", force)
                .string("report", report)
                .string("cache", cache)
                .array("profiles", profiles)
                .finish();
    }

    public static ImportRequest decode(String json) {
        return new ImportRequest(
                Jsonl.str(json, "source"),
                Jsonl.str(json, "out"),
                Jsonl.str(json, "baseDir"),
                Jsonl.str(json, "tmpDir"),
                Jsonl.bool(json, "force", false),
                Jsonl.str(json, "report"),
                Jsonl.str(json, "cache"),
                Jsonl.strArray(json, "profiles"));
    }
}
