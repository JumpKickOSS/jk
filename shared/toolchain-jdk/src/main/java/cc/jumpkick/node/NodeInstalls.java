// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.node;

import cc.jumpkick.compat.BuildTool;
import cc.jumpkick.compat.InstalledTool;
import cc.jumpkick.compat.ToolRegistry;
import cc.jumpkick.config.TomlScan;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.lock.ToolchainPins;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The Node.js a project declares and the installs on this machine that can serve it, read without
 * installing anything: the lock's version, else the manifest's spec; jk's own installs, then those
 * other managers made.
 */
public final class NodeInstalls {

    private final ToolRegistry tools;
    private final NodeDiscovery discovery;

    public NodeInstalls() {
        this(new ToolRegistry(JkDirs.tools()), new NodeDiscovery());
    }

    public NodeInstalls(ToolRegistry tools, NodeDiscovery discovery) {
        this.tools = tools;
        this.discovery = discovery;
    }

    /**
     * A project's Node.js.
     *
     * @param root the directory of the nearest {@code jk.toml}
     * @param locked the lock's exact version, or null before the first lock
     * @param spec the {@code node} / {@code [node] version} the nearest manifest declaring one writes
     * @param packageManager the lock's {@code package-manager} ({@code pnpm@10.18.1}), or null for npm
     */
    public record ProjectNode(
            Path root,
            @Nullable String locked,
            @Nullable String spec,
            @Nullable String packageManager) {

        /** The spec a lookup goes by: the locked version exactly, else the manifest's. */
        public @Nullable NodeSpec lookup() {
            if (locked != null) return NodeSpec.parse("=" + locked);
            return spec == null ? null : NodeSpec.parse(spec);
        }

        /** What the project asks for, as a user would type it. */
        public String wanted() {
            return locked != null ? locked : spec == null ? "" : spec;
        }
    }

    /** One install: jk's own ({@code source} {@code jk}) or another manager's. */
    public record Install(String version, String source, Path home) {}

    /** The project {@code dir} belongs to; empty outside any {@code jk.toml} or when nothing declares Node.js. */
    public static Optional<ProjectNode> project(Path dir) {
        Optional<Path> root = nearestManifestDir(dir);
        if (root.isEmpty()) return Optional.empty();
        ToolchainPins pins = ToolchainPins.scan(root.get());
        String spec = declaredSpec(root.get());
        if (pins.node() == null && spec == null) return Optional.empty();
        return Optional.of(new ProjectNode(root.get(), pins.node(), spec, pins.nodePackageManager()));
    }

    /** The nearest directory at or above {@code dir} holding a {@code jk.toml}. */
    public static Optional<Path> nearestManifestDir(Path dir) {
        Path p = dir.toAbsolutePath().normalize();
        while (p != null) {
            if (written(p)) return Optional.of(p);
            p = p.getParent();
        }
        return Optional.empty();
    }

    /** The {@code node} / {@code [node] version} the nearest manifest declaring one writes, walking up. */
    static @Nullable String declaredSpec(Path dir) {
        Path p = dir.toAbsolutePath().normalize();
        while (p != null) {
            if (written(p)) {
                Path manifest = ManifestPaths.manifestIn(p);
                TomlScan scan = TomlScan.scan(manifest, "node", "node.version");
                String v = scan.get("node");
                if (v == null || v.isBlank()) v = scan.get("node.version");
                if (v != null && !v.isBlank()) return v.trim();
            }
            p = p.getParent();
        }
        return null;
    }

    /** Whether {@code dir} holds a written {@code jk.toml}; a pom-built directory's shadow is not one. */
    private static boolean written(Path dir) {
        return !ManifestPaths.isShadowed(dir) && Files.isRegularFile(ManifestPaths.manifestIn(dir));
    }

    /** jk's own installs, newest first. */
    public List<Install> managed() throws IOException {
        List<Install> out = new ArrayList<>();
        for (InstalledTool t : tools.list(BuildTool.NODE)) out.add(new Install(t.version(), "jk", t.home()));
        out.sort(Comparator.comparing((Install i) -> NodeDiscovery.VersionKey.of(i.version()))
                .reversed());
        return out;
    }

    /** Every install: jk's first, then those other managers made, each newest first. */
    public List<Install> all() throws IOException {
        List<Install> out = new ArrayList<>(managed());
        List<DiscoveredNode> found = new ArrayList<>(discovery.discover());
        found.sort(Comparator.comparing((DiscoveredNode d) -> NodeDiscovery.VersionKey.of(d.version()))
                .reversed());
        for (DiscoveredNode d : found) out.add(new Install(d.version(), d.source(), d.home()));
        return out;
    }

    /**
     * The newest install satisfying {@code spec}, jk's own first. {@code releases} answers an
     * {@code lts} or {@code latest} spec; a numeric one needs none.
     */
    public Optional<Install> installed(NodeSpec spec, List<NodeRelease> releases) throws IOException {
        for (Install i : all()) {
            if (NodeSelector.satisfies(spec, i.version(), releases)) return Optional.of(i);
        }
        return Optional.empty();
    }

    /** Whether {@code spec} needs the catalog to judge an install. */
    public static boolean needsReleases(NodeSpec spec) {
        return spec.kind() == NodeSpec.Kind.LTS
                || spec.kind() == NodeSpec.Kind.LTS_CODENAME
                || spec.kind() == NodeSpec.Kind.LATEST;
    }

    /** {@code version}'s home in jk's store, if installed. */
    public Optional<InstalledTool> managedTool(BuildTool tool, String version) {
        return tools.find(tool, version);
    }

    /** The newest major with an LTS release, or 0 when {@code releases} lists none. */
    public static int newestLtsMajor(List<NodeRelease> releases) {
        return releases.stream()
                .filter(r -> !r.preRelease() && r.lts() != null)
                .mapToInt(NodeRelease::major)
                .max()
                .orElse(0);
    }
}
