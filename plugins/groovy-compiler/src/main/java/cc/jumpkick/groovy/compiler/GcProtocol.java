// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.groovy.compiler;

import cc.jumpkick.plugin.protocol.PluginReply;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.util.Map;

/**
 * Typed view over the shared {@link ProtocolWriter} for the groovy-compiler plugin's messages, on
 * the unified plugin wire. The prefix and line framing live in the {@code ProtocolWriter} (built by
 * {@code PluginMain} from the plugin manifest); this just builds the reply for each event.
 */
final class GcProtocol {

    private final ProtocolWriter out;

    GcProtocol(ProtocolWriter out) {
        this.out = out;
    }

    /** A compiler diagnostic; {@code file} may be null, {@code line}/{@code col} 0 when unknown. */
    void diagnostic(String severity, String file, int line, int col, String message) {
        out.emit(PluginReply.diagnostic(severity, file, line, col, message));
    }

    /** The terminal outcome, e.g. {@code COMPILATION_SUCCESS}. */
    void result(String status) {
        out.emit(PluginReply.result(Map.of("status", status)));
    }

    /** The terminal marker carrying the exit code. */
    void done(int exit) {}
}
