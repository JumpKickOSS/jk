// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Prepare a loose script/jar for execution (see {@link EngineProtocol#SCRIPT_PREPARE_REQUEST}).
 * {@code stateDir} / {@code repoUrl} may be {@code null} (defaults).
 */
public record ScriptPrepareRequest(
        @Nullable String mode,
        @Nullable String script,
        @Nullable String cache,
        @Nullable String stateDir,
        @Nullable String repoUrl,
        boolean forceRecompile,
        @Nullable List<String> with) {

    public ScriptPrepareRequest {
        with = with == null ? List.of() : List.copyOf(with);
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.SCRIPT_PREPARE_REQUEST)
                .string("mode", mode)
                .string("script", script)
                .string("cache", cache)
                .string("stateDir", stateDir)
                .string("repoUrl", repoUrl)
                .bool("forceRecompile", forceRecompile)
                .array("with", with)
                .finish();
    }

    public static ScriptPrepareRequest decode(String json) {
        return new ScriptPrepareRequest(
                Jsonl.str(json, "mode"),
                Jsonl.str(json, "script"),
                Jsonl.str(json, "cache"),
                Jsonl.str(json, "stateDir"),
                Jsonl.str(json, "repoUrl"),
                Jsonl.bool(json, "forceRecompile", false),
                Jsonl.strArray(json, "with"));
    }
}
