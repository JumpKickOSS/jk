// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Run a plugin-contributed command engine-side. */
public record PluginCommandRequest(
        @Nullable String dir,
        @Nullable String cache,
        @Nullable String command,
        @Nullable List<String> args) {

    public PluginCommandRequest {
        args = args == null ? List.of() : List.copyOf(args);
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.PLUGIN_VERB_REQUEST)
                .string("dir", dir)
                .string("cache", cache)
                .string("command", command)
                .array("args", args)
                .finish();
    }

    public static PluginCommandRequest decode(String json) {
        return new PluginCommandRequest(
                Jsonl.str(json, "dir"),
                Jsonl.str(json, "cache"),
                Jsonl.str(json, "command"),
                Jsonl.strArray(json, "args"));
    }
}
