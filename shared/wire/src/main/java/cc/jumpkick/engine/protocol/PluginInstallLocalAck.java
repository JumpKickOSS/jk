// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;

/** Terminal for {@link EngineProtocol#PLUGIN_INSTALL_LOCAL_REQUEST}. */
public record PluginInstallLocalAck(
        String error, int installed, int skipped, List<String> missing, List<String> lines) {

    public static PluginInstallLocalAck error(String message) {
        return new PluginInstallLocalAck(message, 0, 0, List.of(), List.of());
    }

    public String encode() {
        return "{\"type\":\"" + EngineProtocol.PLUGIN_INSTALL_LOCAL_ACK + "\""
                + ",\"error\":" + (error == null ? "null" : Jsonl.quote(error))
                + ",\"installed\":" + installed
                + ",\"skipped\":" + skipped
                + ",\"missing\":" + EngineProtocol.quoteArray(missing)
                + ",\"lines\":" + EngineProtocol.quoteArray(lines)
                + "}";
    }

    public static PluginInstallLocalAck decode(String line) {
        return new PluginInstallLocalAck(
                Jsonl.str(line, "error"),
                Jsonl.intValue(line, "installed", 0),
                Jsonl.intValue(line, "skipped", 0),
                Jsonl.strArray(line, "missing"),
                Jsonl.strArray(line, "lines"));
    }
}
