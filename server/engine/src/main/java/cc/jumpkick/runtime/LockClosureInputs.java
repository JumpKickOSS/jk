// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.lock.LockClosureStamp;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.ToolEntry;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.manifest.PluginContributions;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * What a closure a plugin resolves for the lock is resolved from, as one digest: the plugin that
 * answers it ({@link LockClosureStamp}), the module, the module's own rows in the scopes the
 * closure reads ({@code entries}: the production closure, or the test runtime closure), and the
 * lock's tool rows (what the plugin resolves with). {@code jk lock} keeps the previous lock's row
 * for the module while these are unchanged, without forking the plugin; the build finds its row by
 * closure and module, never by recomputing this.
 *
 * <p>The module's own rows are its classpath's ({@link ClasspathResolver#moduleRows}): a change to
 * another member of the workspace, or a test-only change to a production closure, is not an input.
 * The plugin's table is not one either: the lock is the same for every variant, and what the table
 * changes about a resolution (a platform version) is already in the rows.
 */
final class LockClosureInputs {

    private LockClosureInputs() {}

    /**
     * The hex digest of {@code closure}'s inputs for the module {@code build} at {@code moduleDir},
     * read against {@code view}, its view of the lock at {@code lockDir}.
     */
    static String of(
            PluginContributions.LockClosureDep closure, JkBuild build, Path lockDir, Path moduleDir, Lockfile view) {
        StringBuilder b = new StringBuilder();
        b.append("resolved-by ").append(closure.resolvedBy()).append('\n');
        b.append("module ").append(module(lockDir, moduleDir)).append('\n');
        b.append("plugin ").append(LockClosureStamp.of(closure.manifest())).append('\n');
        List<String> rows = new ArrayList<>();
        var scopes = closure.closure().testEntries() ? ClasspathResolver.TEST : ClasspathResolver.RUNTIME;
        for (Lockfile.Artifact a : ClasspathResolver.moduleRows(view, scopes, build, moduleDir)) {
            // Name, version and bytes: the scopes a row is labelled with are the workspace's, and
            // which of them this module reads is what moduleRows already decided.
            rows.add(a.name() + ' ' + a.version() + ' ' + Objects.requireNonNullElse(a.checksum(), "-"));
        }
        Collections.sort(rows);
        for (String row : rows) b.append("artifact ").append(row).append('\n');
        List<String> tools = new ArrayList<>();
        for (ToolEntry row : view.tools()) {
            if (!row.isResolved()) tools.add(row.toString());
        }
        Collections.sort(tools);
        for (String row : tools) b.append("tool ").append(row).append('\n');
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
