// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.plugin.manifest.PluginContributions;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * What a closure a plugin resolves for the lock is resolved from, as one digest: the plugin and
 * its version, the module, and every row of the module's view of the lock. A resolved row carries it as
 * {@code inputs}: a plain {@code jk lock} keeps a row whose inputs still match instead of forking
 * the plugin again, and lock freshness reads a missing or different one as a stale lock, without
 * forking anything.
 *
 * <p>The plugin's table is not an input. The lock is the same for every variant, and a variant or
 * a schema default rewrites the table a build reads; what the table changes about a resolution (a
 * platform version) is already in the rows.
 */
public final class LockClosureInputs {

    private LockClosureInputs() {}

    /**
     * The hex digest of {@code closure}'s inputs for the module at {@code moduleDir}, read against
     * {@code view}, its view of the lock at {@code lockDir}. The module's path under the lock is
     * an input: in a workspace every member may read the same rows, and two members' closures are
     * two answers.
     */
    public static String of(PluginContributions.LockClosureDep closure, Path lockDir, Path moduleDir, Lockfile view) {
        StringBuilder b = new StringBuilder();
        b.append("resolved-by ").append(closure.resolvedBy()).append('\n');
        b.append("module ").append(module(lockDir, moduleDir)).append('\n');
        b.append("plugin ")
                .append(closure.manifest().id())
                .append(' ')
                .append(Objects.requireNonNullElse(closure.manifest().version(), ""))
                .append('\n');
        List<String> rows = new ArrayList<>();
        for (Lockfile.Artifact a : view.artifacts()) {
            rows.add(a.name()
                    + ' '
                    + a.version()
                    + ' '
                    + Objects.requireNonNullElse(a.checksum(), "-")
                    + ' '
                    + a.scopes());
        }
        Collections.sort(rows);
        for (String row : rows) b.append("artifact ").append(row).append('\n');
        return Hashing.sha256Hex(b.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** {@code moduleDir} under {@code lockDir}, {@code /}-separated; empty for the lock's own module. */
    static String module(Path lockDir, Path moduleDir) {
        Path root = lockDir.toAbsolutePath().normalize();
        Path module = moduleDir.toAbsolutePath().normalize();
        if (root.equals(module) || !module.startsWith(root)) return "";
        return root.relativize(module).toString().replace('\\', '/');
    }
}
