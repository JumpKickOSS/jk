// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The version literal {@code unresolved} is what {@code jk import} writes for a dependency whose
 * version no POM in its chain supplied; it names nothing any repository holds. A lock refuses a
 * manifest that carries it before any solve starts, pointing at the line that does.
 */
final class UnresolvedPins {

    static final String LITERAL = "unresolved";

    private UnresolvedPins() {}

    /** Throw for the first dependency of {@code build} pinned at {@link #LITERAL}, naming its line in {@code manifest}. */
    static void refuse(Path manifest, JkBuild build) {
        for (Map.Entry<Scope, List<Dependency>> scope :
                build.dependencies().byScope().entrySet()) {
            for (Dependency dep : scope.getValue()) {
                if (!(dep.version() instanceof VersionSelector.Exact exact) || !LITERAL.equals(exact.version())) {
                    continue;
                }
                throw new JkBuildParseException(where(manifest, scope.getKey(), dep.library()) + ": " + dep.module()
                        + " is pinned at `" + LITERAL + "`, the version jk import writes when no POM in the chain"
                        + " supplies one, and no repository has it. Write the version there and relock.");
            }
        }
    }

    /** {@code path:line [table] key} when the key sits under its table in the file, else the path, table and key. */
    static String where(Path manifest, Scope scope, String library) {
        String table = "[" + scope.tomlSection() + "]";
        try {
            List<String> lines = Files.readAllLines(manifest);
            boolean inTable = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).strip();
                if (line.startsWith("[")) {
                    inTable = line.equals(table);
                } else if (inTable && keyOf(line).equals(library)) {
                    return manifest + ":" + (i + 1) + " " + table + " " + library;
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            // the path and table still name the declaration
        }
        return manifest + " " + table + " " + library;
    }

    private static String keyOf(String line) {
        int eq = line.indexOf('=');
        if (eq < 0) return "";
        String key = line.substring(0, eq).strip();
        if (key.length() >= 2 && key.startsWith("\"") && key.endsWith("\"")) {
            key = key.substring(1, key.length() - 1);
        }
        return key;
    }
}
