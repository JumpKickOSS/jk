// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.layout.NodeProject;
import cc.jumpkick.layout.NodeShape;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.NodePin;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.ToolchainSpec;
import cc.jumpkick.node.NodePlatform;
import cc.jumpkick.node.NodeResolution;
import cc.jumpkick.node.NodeResolver;
import cc.jumpkick.node.NodeSpec;
import cc.jumpkick.node.PackageManager;
import cc.jumpkick.node.PackageManagerResolver;
import cc.jumpkick.node.PackageManagerSpec;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Stamps the lock's {@code [node]} from the Node.js version a project declares and the package
 * manager its node builds use. One workspace has one Node.js and one manager.
 *
 * <p>{@code previous} is the lock being replaced on a plain {@code jk lock}; its pin is kept while
 * it still satisfies the declaration (a major keeps its point release, a keyword such as {@code lts}
 * keeps whatever it resolved to), so the record of what built the lock does not move by itself.
 * {@code jk update} passes null and a suggestion moves to the newest release it names.
 */
final class NodeLockStamp {

    private NodeLockStamp() {}

    /**
     * What a workspace declares: the Node.js version and, when a node build names one, its package
     * manager ({@code null} for npm).
     */
    record Declared(NodeSpec spec, @Nullable PackageManagerSpec manager) {}

    /**
     * The workspace's declaration from {@code modules} (directory to manifest, inheritance applied),
     * or null when none declares a Node.js version. Two versions or two managers are refused.
     */
    static @Nullable Declared declared(Map<Path, JkBuild> modules) {
        NodeSpec spec = null;
        Path specAt = null;
        PackageManagerSpec manager = null;
        Path managerAt = null;
        for (Map.Entry<Path, JkBuild> e : modules.entrySet()) {
            ToolchainSpec declared = e.getValue().project().nodeSpec();
            if (declared.isEmpty()) continue;
            NodeSpec s = NodeSpec.of(declared);
            if (spec != null && !spec.equals(s)) {
                throw new IllegalArgumentException("one Node.js version per workspace: node = \"" + spec + "\" in "
                        + specAt + " and \"" + s + "\" in " + e.getKey() + " — declare it once at the root");
            }
            spec = s;
            specAt = e.getKey();
            Path nodeDir = NodeShape.nodeDir(e.getValue(), e.getKey());
            if (nodeDir == null) continue;
            NodeProject project = NodeProject.infer(nodeDir, e.getValue().node());
            PackageManagerSpec m = managerOf(project);
            if (m == null) continue;
            if (manager != null && !manager.equals(m)) {
                throw new IllegalArgumentException("one package manager per workspace: " + manager + " in " + managerAt
                        + " and " + m + " in " + e.getKey() + " — use one manager for every node build");
            }
            manager = m;
            managerAt = e.getKey();
        }
        return spec == null ? null : new Declared(spec, manager);
    }

    /** The manager a node build runs, at the version it names, else {@code latest}; null for npm. */
    static @Nullable PackageManagerSpec managerOf(NodeProject project) {
        PackageManager pm = PackageManager.byId(project.packageManager())
                .orElseThrow(() -> new IllegalArgumentException("package manager `" + project.packageManager()
                        + "` is not one jk runs (npm, pnpm, yarn, bun)"));
        if (pm.tool().isEmpty()) return null;
        String version = project.packageManagerVersion();
        return new PackageManagerSpec(pm, version == null || version.isBlank() ? PackageManagerSpec.LATEST : version);
    }

    /**
     * {@code lock} with {@code [node]} for {@code declared}: {@code previous}'s pin when it still
     * satisfies it, else what {@code resolver} selects. Null {@code declared} clears the table.
     */
    static Lockfile apply(
            Lockfile lock,
            @Nullable Lockfile previous,
            @Nullable Declared declared,
            NodeResolver resolver,
            PackageManagerResolver managers,
            NodePlatform host)
            throws IOException, InterruptedException {
        if (declared == null) return lock.node() == null ? lock : lock.withNode(null);
        NodePin prev = previous == null ? null : previous.node();
        String version;
        String npm;
        Map<String, String> sha256;
        if (prev != null && keeps(declared.spec(), prev.version())) {
            version = prev.version();
            npm = prev.npm();
            sha256 = prev.sha256();
        } else {
            NodeResolution resolved = resolver.resolve(declared.spec(), host);
            version = resolved.version();
            npm = resolved.npm();
            sha256 = new LinkedHashMap<>(resolved.sha256());
        }
        return lock.withNode(new NodePin(version, npm, manager(declared.manager(), prev, managers, host), sha256));
    }

    /** Whether a lock's {@code version} still answers {@code spec} without asking the catalog. */
    static boolean keeps(NodeSpec spec, String version) {
        return switch (spec.kind()) {
            case EXACT -> spec.text().equals(version);
            case LINE -> version.startsWith(spec.text() + ".");
            case MAJOR -> NodeSpec.parse(version).major() == spec.major();
            case LTS, LTS_CODENAME, LATEST -> true;
        };
    }

    /**
     * {@code name@exact} for {@code spec}: its own version, else the previous lock's when it named the
     * same manager, else the registry's newest. Null for npm.
     */
    private static @Nullable String manager(
            @Nullable PackageManagerSpec spec,
            @Nullable NodePin prev,
            PackageManagerResolver managers,
            NodePlatform host)
            throws IOException, InterruptedException {
        if (spec == null) return null;
        if (!spec.version().equals(PackageManagerSpec.LATEST)) return spec.toString();
        String kept = prev == null ? null : prev.packageManager();
        if (kept != null && kept.startsWith(spec.manager().id() + "@")) return kept;
        String exact = managers.resolve(spec, host).version();
        return new PackageManagerSpec(spec.manager(), Objects.requireNonNull(exact, "version")).toString();
    }
}
