// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin;

import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.util.List;

/**
 * Plugin SPI ({@code META-INF/services/cc.jumpkick.plugin.Plugin}). Runs as a forked worker JVM
 * and emits via {@link ProtocolWriter}; in-engine extensions implement {@link Extension} only.
 */
public interface Plugin extends Extension {

    /** Static identity + protocol prefix. */
    PluginManifest manifest();

    /** An extension's id is its manifest id. */
    @Override
    default String id() {
        return manifest().id();
    }

    /**
     * Execute the plugin against {@code args} (the verbatim process arguments — typically a single
     * spec-file path), emitting structured results through {@code out}. Return the process exit code
     * (0 = success).
     */
    int run(List<String> args, ProtocolWriter out) throws Exception;
}
