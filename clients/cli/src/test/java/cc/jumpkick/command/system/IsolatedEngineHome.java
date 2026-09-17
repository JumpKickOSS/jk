// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Gives a throwaway home the suite's real engine, so an engine-hosted verb run against that home
 * has a daemon to start. Reads the suite's layout through {@link JkDirs} — the JVM's own, before any
 * overlay is set — not through {@code JK_HOME} in the environment, so the copy happens whatever the
 * shell that started the gate exported.
 */
final class IsolatedEngineHome {

    private IsolatedEngineHome() {}

    /** The suite's engine install and per-app engine config, resolved before an overlay hides them. */
    record Suite(Path lib, Path config) {
        static Suite current() {
            JkDirs suite = JkDirs.current();
            return new Suite(
                    suite.productLibDir().resolve("jk-engine"),
                    suite.configDir().resolve("jk-engine"));
        }
    }

    /** Copy the suite's engine jars and engine config into {@code home}'s own {@code lib} and {@code config}. */
    static void copyEngine(Suite suite, Path home) throws IOException {
        copyFiles(suite.lib(), home.resolve("lib").resolve("jk-engine"));
        copyFiles(suite.config(), home.resolve("config").resolve("jk-engine"));
    }

    private static void copyFiles(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        if (!Files.isDirectory(from)) return;
        try (var stream = Files.list(from)) {
            for (Path p : stream.toList()) {
                if (Files.isRegularFile(p))
                    Files.copy(p, to.resolve(p.getFileName().toString()));
            }
        }
    }
}
