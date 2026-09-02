// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** A source formatting request. */
public record FormatRequest(
        String dir,
        String cache,
        boolean check,
        String javaStyle,
        String kotlinStyle,
        boolean optimizeImports,
        boolean importOrder,
        boolean removeUnusedImports,
        boolean offline,
        boolean verbose) {

    public String encode() {
        return RequestJson.request(EngineProtocol.FORMAT_REQUEST)
                .string("dir", dir)
                .string("cache", cache)
                .bool("check", check)
                .string("javaStyle", javaStyle)
                .string("kotlinStyle", kotlinStyle)
                .bool("optimizeImports", optimizeImports)
                .bool("importOrder", importOrder)
                .bool("removeUnusedImports", removeUnusedImports)
                .bool("offline", offline)
                .bool("verbose", verbose)
                .finish();
    }

    public static FormatRequest decode(String json) {
        return new FormatRequest(
                Jsonl.str(json, "dir"),
                Jsonl.str(json, "cache"),
                Jsonl.bool(json, "check", false),
                Jsonl.str(json, "javaStyle"),
                Jsonl.str(json, "kotlinStyle"),
                Jsonl.bool(json, "optimizeImports", true),
                Jsonl.bool(json, "importOrder", true),
                Jsonl.bool(json, "removeUnusedImports", true),
                Jsonl.bool(json, "offline", false),
                Jsonl.bool(json, "verbose", false));
    }
}
