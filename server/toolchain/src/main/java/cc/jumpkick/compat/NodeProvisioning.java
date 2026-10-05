// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import cc.jumpkick.http.Http;
import cc.jumpkick.node.DiscoveredNode;
import cc.jumpkick.node.NodeDiscovery;
import cc.jumpkick.node.NodeHome;
import cc.jumpkick.node.NodePlatform;
import cc.jumpkick.node.NodeRelease;
import cc.jumpkick.node.NodeResolution;
import cc.jumpkick.node.NodeResolver;
import cc.jumpkick.node.NodeSelector;
import cc.jumpkick.node.NodeSources;
import cc.jumpkick.node.NodeSpec;
import cc.jumpkick.node.PackageManager;
import cc.jumpkick.node.PackageManagerResolver;
import cc.jumpkick.node.PackageManagerSpec;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Puts a Node home on disk: a managed install under {@code $JK_STORE_DIR/tools/node/<exact>/}, else
 * one another manager installed (unless discovery is off), else a download verified against the
 * resolution's sha256 for this platform. Package managers install beside it under {@code
 * tools/<manager>/<exact>/}, verified against the registry's integrity.
 */
public final class NodeProvisioning {

    /** {@code noDiscover} ignores installs other managers made, as {@code --no-discover} does. */
    public record Policy(boolean noDiscover) {
        public static final Policy DEFAULT = new Policy(false);
    }

    private final ToolRegistry registry;
    private final Http http;
    private final NodeDiscovery discovery;
    private final URI distBase;
    private final PackageManagerResolver managers;
    private final NodePlatform host;

    public NodeProvisioning() {
        this(
                new ToolRegistry(JkDirs.tools()),
                NodeSources.http(),
                new NodeDiscovery(),
                NodeSources.distBase(),
                new PackageManagerResolver(),
                NodePlatform.host());
    }

    public NodeProvisioning(
            ToolRegistry registry,
            Http http,
            NodeDiscovery discovery,
            URI distBase,
            PackageManagerResolver managers,
            NodePlatform host) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.http = Objects.requireNonNull(http, "http");
        this.discovery = Objects.requireNonNull(discovery, "discovery");
        this.distBase = Objects.requireNonNull(distBase, "distBase");
        this.managers = Objects.requireNonNull(managers, "managers");
        this.host = Objects.requireNonNull(host, "host");
    }

    /**
     * Node at exactly {@code resolution}'s version — what a lock pins. A discovered install counts
     * only at that exact version; a download is verified against the resolution's digest.
     */
    public NodeHome ensure(NodeResolution resolution, Policy policy, ToolProgress progress)
            throws IOException, InterruptedException {
        String version = resolution.version();
        Optional<NodeHome> managed = managed(version);
        if (managed.isPresent()) return managed.get();
        if (!policy.noDiscover()) {
            Optional<DiscoveredNode> found = discovery.discover().stream()
                    .filter(d -> d.version().equals(version))
                    .findFirst();
            if (found.isPresent())
                return new NodeHome(found.get().home(), version, found.get().source(), null);
        }
        InstalledTool installed = new ToolInstaller(http, registry)
                .install(resolution.distribution(host, distBase), false, progress)
                .tool();
        return new NodeHome(installed.home(), version, "jk", null);
    }

    /**
     * Node for {@code spec} with no lock to pin it: a managed or discovered install that satisfies
     * it, else the release {@code resolver} selects.
     */
    public NodeHome ensure(NodeSpec spec, NodeResolver resolver, Policy policy, ToolProgress progress)
            throws IOException, InterruptedException {
        List<NodeRelease> releases = spec.kind() == NodeSpec.Kind.MAJOR
                        || spec.kind() == NodeSpec.Kind.LINE
                        || spec.kind() == NodeSpec.Kind.EXACT
                ? List.of()
                : resolver.catalog().releases();
        Optional<NodeHome> managed = registry.list(BuildTool.NODE).stream()
                .filter(t -> NodeSelector.satisfies(spec, t.version(), releases))
                .filter(t -> Files.exists(BuildTool.NODE.launcher(t.home())))
                .max(Comparator.comparing(t -> NodeDiscovery.VersionKey.of(t.version())))
                .map(t -> new NodeHome(t.home(), t.version(), "jk", null));
        if (managed.isPresent()) return managed.get();
        if (!policy.noDiscover()) {
            Optional<DiscoveredNode> found = discovery.find(spec, releases);
            if (found.isPresent()) {
                return new NodeHome(
                        found.get().home(), found.get().version(), found.get().source(), null);
            }
        }
        return ensure(resolver.resolve(spec, host), new Policy(true), progress);
    }

    /**
     * {@code node} with {@code spec}'s package manager: npm leaves it as is; pnpm, Yarn and bun come
     * from the store, else the registry. {@code latest} asks the registry and falls back to the
     * newest installed when the registry cannot be reached.
     */
    public NodeHome withManager(NodeHome node, PackageManagerSpec spec, ToolProgress progress)
            throws IOException, InterruptedException {
        Optional<BuildTool> tool = spec.manager().tool();
        if (tool.isEmpty()) return node.withManager(null);
        BuildTool t = tool.get();
        if (!spec.version().equals(PackageManagerSpec.LATEST)) {
            Optional<InstalledTool> installed =
                    registry.find(t, spec.version()).filter(i -> Files.exists(t.launcher(i.home())));
            if (installed.isPresent()) return node.withManager(managerHome(spec.manager(), installed.get()));
        }
        try {
            InstalledTool installed = new ToolInstaller(http, registry)
                    .install(managers.resolve(spec, host), false, progress)
                    .tool();
            return node.withManager(managerHome(spec.manager(), installed));
        } catch (IOException offline) {
            if (!spec.version().equals(PackageManagerSpec.LATEST)) throw offline;
            InstalledTool newest = registry.list(t).stream()
                    .filter(i -> Files.exists(t.launcher(i.home())))
                    .max(Comparator.comparing(i -> NodeDiscovery.VersionKey.of(i.version())))
                    .orElseThrow(() -> offline);
            return node.withManager(managerHome(spec.manager(), newest));
        }
    }

    /** The managed install of exactly {@code version}, when one is on disk. */
    public Optional<NodeHome> managed(String version) {
        return registry.find(BuildTool.NODE, version)
                .filter(t -> Files.exists(BuildTool.NODE.launcher(t.home())))
                .map(t -> new NodeHome(t.home(), version, "jk", null));
    }

    private static NodeHome.ManagerHome managerHome(PackageManager pm, InstalledTool installed) {
        return new NodeHome.ManagerHome(pm, installed.version(), installed.home());
    }
}
