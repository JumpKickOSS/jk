// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Answer to {@link EngineProtocol#PLUGIN_VERB_REQUEST}: whether a plugin owns the command, plus
 * output/exit. {@code found=false} → unknown-command help; non-null {@code error} → failed run.
 */
public record PluginCommandReport(@Nullable String error, boolean found, int exit, List<String> output) {

    public static PluginCommandReport notFound() {
        return new PluginCommandReport(null, false, 0, List.of());
    }

    public static PluginCommandReport error(String message) {
        return new PluginCommandReport(message, true, 1, List.of());
    }

    public String encode() {
        return "{\"type\":\"" + EngineProtocol.PLUGIN_VERB_ACK + "\""
                + ",\"error\":" + (error == null ? "null" : Jsonl.quote(error))
                + ",\"found\":" + found
                + ",\"exit\":" + exit
                + ",\"output\":" + EngineProtocol.quoteArray(output)
                + "}";
    }

    public static PluginCommandReport decode(String line) {
        return new PluginCommandReport(
                Jsonl.str(line, "error"),
                Jsonl.bool(line, "found", false),
                Jsonl.intValue(line, "exit", 0),
                Jsonl.strArray(line, "output"));
    }
}
