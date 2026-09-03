// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** A compile-only request. */
public record CompileRequest(
        @Nullable String dir,
        @Nullable String cache,
        @Nullable String profile,
        boolean offline,
        boolean force,
        boolean verbose,
        @Nullable List<String> moduleDirs) {

    public CompileRequest {
        moduleDirs = moduleDirs == null ? List.of() : List.copyOf(moduleDirs);
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.COMPILE_REQUEST)
                .string("dir", dir)
                .string("cache", cache)
                .string("profile", profile)
                .bool("offline", offline)
                .bool("force", force)
                .bool("verbose", verbose)
                .array("moduleDirs", moduleDirs)
                .finish();
    }

    public static CompileRequest decode(String json) {
        return new CompileRequest(
                Jsonl.str(json, "dir"),
                Jsonl.str(json, "cache"),
                Jsonl.str(json, "profile"),
                Jsonl.bool(json, "offline", false),
                Jsonl.bool(json, "force", false),
                Jsonl.bool(json, "verbose", false),
                Jsonl.strArray(json, "moduleDirs"));
    }
}
