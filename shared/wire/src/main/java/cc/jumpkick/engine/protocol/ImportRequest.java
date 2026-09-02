// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** A foreign-build import request. */
public record ImportRequest(
        String source, String out, String baseDir, String tmpDir, boolean force, String report, String cache) {

    public String encode() {
        return RequestJson.request(EngineProtocol.IMPORT_REQUEST)
                .string("source", source)
                .string("out", out)
                .string("baseDir", baseDir)
                .string("tmpDir", tmpDir)
                .bool("force", force)
                .string("report", report)
                .string("cache", cache)
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
                Jsonl.str(json, "cache"));
    }
}
