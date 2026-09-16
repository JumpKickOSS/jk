// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.ModuleOrder;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.host.Log;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PackageId;
import cc.jumpkick.model.Scope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Structured dependency graph for the engine dashboard ({@code GET /api/project/graph}) and other
 * consumers that need nodes/edges rather than {@link DependencyTree}'s ASCII render.
 *
 * <p>Workspace modules are always included. External coordinates come from declared deps in the
 * selected scopes (from each module's {@code jk.toml}); when {@code transitive} is true the lockfile
 * expands each direct dep. Kinds:
 *
 * <ul>
 *   <li>{@code module} — workspace member (has its own {@code jk.toml})
 *   <li>{@code declared} — listed in a selected-scope {@code jk.toml} dependency table
 *   <li>{@code transitive} — only reached via lockfile edges (not declared in any selected scope)
 * </ul>
 */
public final class DependencyGraphModel {

    /** Display order for scope filters (matches {@code jk tree} section order). */
    public static final List<Scope> SCOPE_ORDER = List.of(
            Scope.EXPORT,
            Scope.MAIN,
            Scope.RUNTIME,
            Scope.PROVIDED,
            Scope.PROCESSOR,
            Scope.PLATFORM,
            Scope.TEST,
            Scope.DEV,
            Scope.TEST_DEV);

    public record Node(
            String id,
            String label,
            @Nullable String version,
            @Nullable String path,
            String kind) {
        public Node {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(kind, "kind");
        }
    }

    /** {@code from} (dependent) → {@code to} (prereq); optional {@code scope} for declared edges. */
    public record Edge(String from, String to, @Nullable String scope) {
        public Edge {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
        }
    }

    /**
     * {@code truncated} is true when the transitive expansion hit {@link #MAX_NODES} /
     * {@link #MAX_EDGES} and the graph is a prefix of the full closure. Consumers should
     * surface it — a silently clipped graph reads as "these are all the dependencies".
     */
    public record Graph(
            boolean workspace,
            List<String> scopes,
            boolean transitive,
            List<String> availableScopes,
            List<Node> nodes,
            List<Edge> edges,
            boolean truncated) {
        public Graph {
            scopes = List.copyOf(scopes);
            availableScopes = List.copyOf(availableScopes);
            nodes = List.copyOf(nodes);
            edges = List.copyOf(edges);
        }

        public static Graph empty(List<String> scopes, boolean transitive) {
            return new Graph(false, scopes, transitive, availableScopeNames(), List.of(), List.of(), false);
        }
    }

    /**
     * Transitive-expansion caps: a 1000+ artifact lock with every scope ticked would
     * otherwise hand the browser a force-layout simulation it cannot finish. Workspace modules and
     * declared deps are never clipped — only lockfile expansion stops at the cap, with
     * {@link Graph#truncated()} set.
     */
    public static final int MAX_NODES = 500;

    public static final int MAX_EDGES = 2000;

    private DependencyGraphModel() {}

    public static List<String> availableScopeNames() {
        return SCOPE_ORDER.stream().map(Scope::canonical).toList();
    }

    /** Every canonical scope name, comma-joined — for "valid: …" diagnostics. */
    public static String validScopes() {
        List<String> names = new ArrayList<>();
        for (Scope s : SCOPE_ORDER) names.add(s.canonical());
        return String.join(", ", names);
    }

    /**
     * Default scopes match {@code jk tree}: export, main, runtime
     * ({@link DependencyTreeStyle#defaultScopeOrder()}).
     */
    public static List<Scope> defaultScopes() {
        return List.copyOf(DependencyTreeStyle.defaultScopeOrder());
    }

    /**
     * Parse a comma-separated scopes query (canonical names). Empty/null → {@link #defaultScopes()}
     * ({@code export}, {@code main}, {@code runtime} — same as {@code jk tree}).
     *
     * <p>An unrecognized token <strong>throws</strong>. Silently dropping it and falling back to
     * a default made a whole class of caller bug invisible: the endpoint forgot to percent-decode
     * this parameter, so {@code main%2Ctest} parsed as one unknown token and the user got a
     * wrong graph with boxes still ticked.
     */
    public static List<Scope> parseScopes(@Nullable String scopesQuery) {
        if (scopesQuery == null || scopesQuery.isBlank()) {
            return defaultScopes();
        }
        LinkedHashSet<Scope> out = new LinkedHashSet<>();
        for (String raw : scopesQuery.split(",")) {
            String t = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
            if (t.isEmpty()) continue;
            try {
                out.add(Scope.fromCanonical(t));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("unknown scope '" + raw.trim() + "' (valid: " + validScopes() + ")");
            }
        }
        if (out.isEmpty()) return defaultScopes();
        // Stable display order
        List<Scope> ordered = new ArrayList<>();
        for (Scope s : SCOPE_ORDER) {
            if (out.contains(s)) ordered.add(s);
        }
        return ordered;
    }

    /**
     * Build the graph for {@code projectDir}. An <em>absent</em> root {@code jk.toml} → empty graph
     * (a deleted or non-jk checkout is not an error). Everything else that fails — malformed toml,
     * a workspace member whose {@code jk.toml} is missing, IO trouble — <strong>throws</strong>
     * ({@link cc.jumpkick.config.JkBuildParseException} / {@link IOException}) so callers surface
     * the message instead of telling the user their project has no dependencies. A
     * missing lockfile still shows modules + declared externals (versions may be null).
     *
     * <p>Default scopes (null/empty {@code scopes}) are {@link #defaultScopes()} — the same
     * {@code export, main, runtime} set {@code jk tree} uses, from the same definition.
     */
    public static Graph forProjectDir(Path projectDir, @Nullable List<Scope> scopes, boolean transitive)
            throws IOException {
        Objects.requireNonNull(projectDir, "projectDir");
        List<Scope> scopeList = scopes == null || scopes.isEmpty() ? defaultScopes() : List.copyOf(scopes);
        List<String> scopeNames = scopeList.stream().map(Scope::canonical).toList();
        Path root = projectDir.toAbsolutePath().normalize();
        Path toml = ManifestPaths.manifestIn(root);
        if (!Files.isRegularFile(toml)) {
            return Graph.empty(scopeNames, transitive);
        }
        JkBuild entry = JkBuildParser.parse(toml);
        LockGraph graph = LockGraph.forLock(readLock(root));

        if (entry.isWorkspaceRoot()) {
            Map<Path, JkBuild> modules = WorkspaceLoader.loadModules(root, entry);
            return buildWorkspace(root, entry, modules, scopeList, transitive, graph);
        }
        return buildStandalone(root, entry, scopeList, transitive, graph);
    }

    private static @Nullable Lockfile readLock(Path projectDir) {
        try {
            Path lf = LockPaths.lockFile(projectDir);
            if (Files.isRegularFile(lf)) return LockfileReader.read(lf);
        } catch (IOException | RuntimeException e) {
            // no lock — declared-only graph
            Log.debug("readLock: no lock", e);
        }
        return null;
    }

    /**
     * Single-project graph. When {@code dir} is a member of a workspace (the dashboard hands module
     * dirs straight from journal records), sibling modules the project depends on are drawn as
     * {@code module} nodes — not as version-less external artifacts. Sibling discovery is
     * best-effort: a broken workspace root never blocks the module's own graph.
     */
    private static Graph buildStandalone(
            Path dir, JkBuild build, List<Scope> scopes, boolean transitive, LockGraph graph) {
        Builder b = new Builder(scopes, transitive, graph);
        try {
            Path wsRoot = WorkspaceLocator.findRoot(dir).orElse(null);
            if (wsRoot != null) {
                JkBuild rootBuild = JkBuildParser.parse(ManifestPaths.manifestIn(wsRoot));
                for (var e : WorkspaceLoader.loadModules(wsRoot, rootBuild).entrySet()) {
                    Path modDir = e.getKey().toAbsolutePath().normalize();
                    if (modDir.equals(dir)) continue;
                    b.sibling(modDir, e.getValue(), relLabel(dir, modDir));
                }
            }
        } catch (IOException | RuntimeException e) {
            // Sibling enrichment only — the module graph itself must still render.
            Log.debug("buildStandalone: Sibling enrichment only", e);
        }
        String rootId = b.moduleNode(dir, build, ".");
        b.addDeclaredDeps(rootId, build, scopes);
        return b.finish(false);
    }

    private static Graph buildWorkspace(
            Path root,
            JkBuild rootBuild,
            Map<Path, JkBuild> modulesByDir,
            List<Scope> scopes,
            boolean transitive,
            LockGraph graph) {
        Builder b = new Builder(scopes, transitive, graph);
        Map<Path, String> idByDir = new LinkedHashMap<>();

        // The workspace root is a node too: its own [dependencies] are part of the build.
        String rootId = b.moduleNode(root, rootBuild, ".");
        for (var e : modulesByDir.entrySet()) {
            Path dir = e.getKey().toAbsolutePath().normalize();
            idByDir.put(dir, b.moduleNode(dir, e.getValue(), relLabel(root, dir)));
        }

        b.addDeclaredDeps(rootId, rootBuild, scopes);
        for (var e : modulesByDir.entrySet()) {
            Path dir = e.getKey().toAbsolutePath().normalize();
            var moduleId = idByDir.get(dir);
            if (moduleId != null) b.addDeclaredDeps(moduleId, e.getValue(), scopes);
        }
        return b.finish(true);
    }

    private static String relLabel(Path root, Path moduleDir) {
        try {
            Path rel = root.relativize(moduleDir);
            String s = rel.toString().replace('\\', '/');
            return s.isEmpty() ? "." : s;
        } catch (IllegalArgumentException e) {
            return moduleDir.toString();
        }
    }

    /**
     * Canonical node identity for an external artifact: the full package key
     * ({@code g:a:type:classifier}), so a test-jar and the main jar of one GA — or two classifier
     * variants — stay distinct nodes. Falls back to the raw key for non-Maven (git/path/workspace
     * placeholder) modules.
     */
    private static String canonicalKey(String moduleOrKey) {
        if (moduleOrKey == null || moduleOrKey.isEmpty()) return "";
        if (PackageId.isMavenPackageKey(moduleOrKey)) {
            try {
                return PackageId.parse(moduleOrKey).key();
            } catch (RuntimeException e) {
                // fall through
                Log.debug("canonicalKey: fall through", e);
            }
        }
        return moduleOrKey;
    }

    /** Display label: {@code g:a} for a default jar, with a classifier/type badge otherwise. */
    private static String nodeLabel(String key) {
        if (key == null) return "";
        if (PackageId.isMavenPackageKey(key)) {
            try {
                return PackageId.parse(key).display();
            } catch (RuntimeException e) {
                // fall through
                Log.debug("nodeLabel: fall through", e);
            }
        }
        String s = key;
        int at = s.indexOf('@');
        if (at > 0) s = s.substring(0, at);
        String[] parts = s.split(":");
        if (parts.length >= 2) return parts[0] + ":" + parts[1];
        return s;
    }

    /** A workspace sibling not yet in the graph — materialized only when a dep references it. */
    private record SiblingModule(Path dir, JkBuild build, String path) {}

    private static final class Builder {
        private final List<Scope> scopes;
        private final boolean transitive;
        private final LockGraph graph;
        private final Map<String, Node> nodes = new LinkedHashMap<>();
        private final List<Edge> edges = new ArrayList<>();
        private final Set<String> edgeKeys = new LinkedHashSet<>();
        /** External packages declared in a selected scope (canonical package keys). */
        private final Set<String> declaredKeys = new LinkedHashSet<>();
        /** Materialized module nodes by {@code group:artifact} coordinate / bare project name. */
        private final Map<String, String> idByCoord = new LinkedHashMap<>();

        private final Map<String, String> idByName = new LinkedHashMap<>();
        /** Lazily materialized siblings (module-dir graphs). */
        private final Map<String, SiblingModule> siblingByCoord = new LinkedHashMap<>();

        private final Map<String, SiblingModule> siblingByName = new LinkedHashMap<>();
        /**
         * Packages whose lockfile deps have already been emitted — shared across the whole
         * expansion, so the reachable closure is walked once instead of once per declared root.
         */
        private final Set<String> expanded = new LinkedHashSet<>();

        private boolean truncated;

        Builder(List<Scope> scopes, boolean transitive, LockGraph graph) {
            this.scopes = scopes;
            this.transitive = transitive;
            this.graph = graph;
        }

        String moduleNode(Path dir, JkBuild build, String path) {
            String label = build.project().group() + ":" + build.project().name();
            if (label.equals(":")) {
                label = dir.getFileName() != null ? dir.getFileName().toString() : "module";
            }
            String id = "m" + nodes.size();
            String version = build.project().version();
            nodes.put(id, new Node(id, label, version, path, "module"));
            idByCoord.put(build.project().group() + ":" + build.project().name(), id);
            idByName.put(build.project().name(), id);
            return id;
        }

        /** Register a sibling for lazy materialization (drawn only if a dep references it). */
        void sibling(Path dir, JkBuild build, String path) {
            SiblingModule s = new SiblingModule(dir, build, path);
            siblingByCoord.put(build.project().group() + ":" + build.project().name(), s);
            siblingByName.put(build.project().name(), s);
        }

        void addDeclaredDeps(String fromId, JkBuild build, List<Scope> scopes) {
            for (Scope scope : scopes) {
                String scopeName = scope.canonical();
                for (Dependency d : build.dependencies().of(scope)) {
                    // Workspace sibling → edge to module node
                    String wsId = resolveWorkspaceId(d);
                    if (wsId != null) {
                        addEdge(fromId, wsId, scopeName);
                        continue;
                    }
                    String key = canonicalKey(d.packageKey());
                    if (key.isEmpty()) continue;
                    declaredKeys.add(key);
                    String toId = externalNode(key, "declared");
                    addEdge(fromId, toId, scopeName);
                    if (transitive) {
                        expandTransitive(key);
                    }
                }
            }
        }

        /**
         * Workspace identity matches on {@code group:artifact} coordinate or (for unresolved
         * {@code workspace = true} placeholders) bare sibling name — the same
         * {@link ModuleOrder#resolveSibling} rule the build order uses. No table-key fallback: a
         * declared external whose TOML key happens to equal a module's name stays external.
         */
        private @Nullable String resolveWorkspaceId(Dependency d) {
            String id = ModuleOrder.resolveSibling(d, idByCoord, idByName);
            if (id != null) return id;
            SiblingModule sib = ModuleOrder.resolveSibling(d, siblingByCoord, siblingByName);
            if (sib != null) {
                return moduleNode(sib.dir(), sib.build(), sib.path());
            }
            return null;
        }

        private String externalNode(String key, String kind) {
            // Stable id by canonical package key so diamond edges converge
            String id = "a:" + key;
            Node existing = nodes.get(id);
            if (existing != null) {
                // Prefer declared over transitive if both appear
                if ("transitive".equals(existing.kind()) && "declared".equals(kind)) {
                    nodes.put(id, new Node(id, existing.label(), existing.version(), null, "declared"));
                }
                return id;
            }
            String version = null;
            Lockfile.Artifact art = graph.artifact(key);
            if (art != null) version = art.version();
            nodes.put(id, new Node(id, nodeLabel(key), version, null, kind));
            return id;
        }

        /**
         * BFS over lockfile deps from one declared root. {@code expanded} is shared across every
         * root, so a subgraph reachable from several declared deps is walked once; its edges are
         * already in the graph from the first walk. Node/edge caps set {@code truncated} instead of
         * growing without bound.
         */
        private void expandTransitive(String fromKey) {
            if (!expanded.add(fromKey)) return;
            Queue<String> q = new ArrayDeque<>();
            q.add(fromKey);
            while (!q.isEmpty()) {
                String parentKey = q.remove();
                if (graph.artifact(parentKey) == null) continue;
                String parentId = "a:" + parentKey;
                for (String dep : graph.forward(parentKey)) {
                    String childKey = canonicalKey(dep);
                    if (childKey.isEmpty()) continue;
                    boolean isNewNode = !nodes.containsKey("a:" + childKey);
                    if ((isNewNode && nodes.size() >= MAX_NODES) || edges.size() >= MAX_EDGES) {
                        truncated = true;
                        continue;
                    }
                    String childId = externalNode(childKey, kindFor(childKey));
                    addEdge(parentId, childId, null);
                    if (expanded.add(childKey)) {
                        q.add(childKey);
                    }
                }
            }
        }

        private String kindFor(String key) {
            return declaredKeys.contains(key) ? "declared" : "transitive";
        }

        private void addEdge(String from, String to, @Nullable String scope) {
            if (from.equals(to)) return;
            String key = from + "\0" + to + "\0" + (scope == null ? "" : scope);
            if (!edgeKeys.add(key)) return;
            edges.add(new Edge(from, to, scope));
        }

        Graph finish(boolean workspace) {
            List<String> scopeNames = scopes.stream().map(Scope::canonical).toList();
            return new Graph(
                    workspace,
                    scopeNames,
                    transitive,
                    availableScopeNames(),
                    new ArrayList<>(nodes.values()),
                    edges,
                    truncated);
        }
    }
}
