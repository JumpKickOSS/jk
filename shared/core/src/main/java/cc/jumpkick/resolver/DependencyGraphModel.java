// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
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

    public record Node(String id, String label, String version, String path, String kind) {
        public Node {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(kind, "kind");
        }
    }

    /** {@code from} (dependent) → {@code to} (prereq); optional {@code scope} for declared edges. */
    public record Edge(String from, String to, String scope) {
        public Edge {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
        }
    }

    public record Graph(
            boolean workspace,
            List<String> scopes,
            boolean transitive,
            List<String> availableScopes,
            List<Node> nodes,
            List<Edge> edges) {
        public Graph {
            scopes = List.copyOf(scopes);
            availableScopes = List.copyOf(availableScopes);
            nodes = List.copyOf(nodes);
            edges = List.copyOf(edges);
        }

        public static Graph empty(List<String> scopes, boolean transitive) {
            return new Graph(false, scopes, transitive, availableScopeNames(), List.of(), List.of());
        }
    }

    private DependencyGraphModel() {}

    public static List<String> availableScopeNames() {
        return SCOPE_ORDER.stream().map(Scope::canonical).toList();
    }

    /**
     * Parse a comma-separated scopes query (canonical names). Empty/null → {@code main} only.
     * Unknown tokens are ignored.
     */
    public static List<Scope> parseScopes(String scopesQuery) {
        if (scopesQuery == null || scopesQuery.isBlank()) {
            return List.of(Scope.MAIN);
        }
        LinkedHashSet<Scope> out = new LinkedHashSet<>();
        for (String raw : scopesQuery.split(",")) {
            String t = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
            if (t.isEmpty()) continue;
            try {
                out.add(Scope.fromCanonical(t));
            } catch (IllegalArgumentException ignored) {
                // drop unknown
            }
        }
        if (out.isEmpty()) return List.of(Scope.MAIN);
        // Stable display order
        List<Scope> ordered = new ArrayList<>();
        for (Scope s : SCOPE_ORDER) {
            if (out.contains(s)) ordered.add(s);
        }
        return ordered;
    }

    /**
     * Build the graph for {@code projectDir}. Missing / unparseable {@code jk.toml} → empty graph
     * (never throws for absent files). Missing lockfile still shows modules + declared externals
     * (versions may be null).
     */
    public static Graph forProjectDir(Path projectDir, List<Scope> scopes, boolean transitive) {
        Objects.requireNonNull(projectDir, "projectDir");
        List<Scope> scopeList = scopes == null || scopes.isEmpty() ? List.of(Scope.MAIN) : List.copyOf(scopes);
        List<String> scopeNames = scopeList.stream().map(Scope::canonical).toList();
        Path root = projectDir.toAbsolutePath().normalize();
        Path toml = root.resolve("jk.toml");
        if (!Files.isRegularFile(toml)) {
            return Graph.empty(scopeNames, transitive);
        }
        try {
            JkBuild entry = JkBuildParser.parse(toml);
            Lockfile lock = readLock(root);
            Map<String, Lockfile.Artifact> byModule =
                    lock == null ? Map.of() : DependencyTree.indexByModule(lock);

            if (entry.isWorkspaceRoot()) {
                Map<Path, JkBuild> modules = WorkspaceLoader.loadModules(root, entry);
                return buildWorkspace(root, modules, scopeList, transitive, byModule);
            }
            return buildStandalone(root, entry, scopeList, transitive, byModule);
        } catch (IOException | RuntimeException e) {
            return Graph.empty(scopeNames, transitive);
        }
    }

    private static Lockfile readLock(Path projectDir) {
        try {
            Path lf = LockPaths.lockFile(projectDir);
            if (Files.isRegularFile(lf)) return LockfileReader.read(lf);
        } catch (IOException | RuntimeException ignored) {
            // no lock — declared-only graph
        }
        return null;
    }

    private static Graph buildStandalone(
            Path dir,
            JkBuild build,
            List<Scope> scopes,
            boolean transitive,
            Map<String, Lockfile.Artifact> byModule) {
        Builder b = new Builder(scopes, transitive, byModule);
        String rootId = b.moduleNode(dir, build, ".");
        b.addDeclaredDeps(rootId, build, scopes);
        return b.finish(false);
    }

    private static Graph buildWorkspace(
            Path root,
            Map<Path, JkBuild> modulesByDir,
            List<Scope> scopes,
            boolean transitive,
            Map<String, Lockfile.Artifact> byModule) {
        Builder b = new Builder(scopes, transitive, byModule);
        Map<String, String> idByCoord = new LinkedHashMap<>();
        Map<String, String> idByName = new LinkedHashMap<>();
        Map<Path, String> idByDir = new LinkedHashMap<>();

        for (var e : modulesByDir.entrySet()) {
            Path dir = e.getKey().toAbsolutePath().normalize();
            JkBuild build = e.getValue();
            String rel = relLabel(root, dir);
            String id = b.moduleNode(dir, build, rel);
            idByDir.put(dir, id);
            String coord = build.project().group() + ":" + build.project().name();
            idByCoord.put(coord, id);
            idByName.put(build.project().name(), id);
        }

        for (var e : modulesByDir.entrySet()) {
            Path dir = e.getKey().toAbsolutePath().normalize();
            String fromId = idByDir.get(dir);
            JkBuild build = e.getValue();
            b.addDeclaredDeps(fromId, build, scopes, idByCoord, idByName);
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

    private static String gaLabel(String moduleOrKey) {
        if (moduleOrKey == null) return "";
        if (PackageId.isMavenPackageKey(moduleOrKey)) {
            try {
                return PackageId.parse(moduleOrKey).ga();
            } catch (RuntimeException ignored) {
                // fall through
            }
        }
        // Strip classifier/type if present as g:a:jar:
        String s = moduleOrKey;
        int at = s.indexOf('@');
        if (at > 0) s = s.substring(0, at);
        String[] parts = s.split(":");
        if (parts.length >= 2) return parts[0] + ":" + parts[1];
        return s;
    }

    private static final class Builder {
        private final List<Scope> scopes;
        private final boolean transitive;
        private final Map<String, Lockfile.Artifact> byModule;
        private final Map<String, Node> nodes = new LinkedHashMap<>();
        private final List<Edge> edges = new ArrayList<>();
        private final Set<String> edgeKeys = new LinkedHashSet<>();
        /** External modules declared in a selected scope (GA keys). */
        private final Set<String> declaredGas = new LinkedHashSet<>();

        Builder(List<Scope> scopes, boolean transitive, Map<String, Lockfile.Artifact> byModule) {
            this.scopes = scopes;
            this.transitive = transitive;
            this.byModule = byModule;
        }

        String moduleNode(Path dir, JkBuild build, String path) {
            String label = build.project().group() + ":" + build.project().name();
            if (label.equals(":")) {
                label = dir.getFileName() != null ? dir.getFileName().toString() : "module";
            }
            String id = "m" + nodes.size();
            String version = build.project().version();
            nodes.put(id, new Node(id, label, version, path, "module"));
            return id;
        }

        void addDeclaredDeps(String fromId, JkBuild build, List<Scope> scopes) {
            addDeclaredDeps(fromId, build, scopes, Map.of(), Map.of());
        }

        void addDeclaredDeps(
                String fromId,
                JkBuild build,
                List<Scope> scopes,
                Map<String, String> idByCoord,
                Map<String, String> idByName) {
            for (Scope scope : scopes) {
                String scopeName = scope.canonical();
                for (Dependency d : build.dependencies().of(scope)) {
                    // Workspace sibling → edge to module node
                    String wsId = resolveWorkspaceId(d, idByCoord, idByName);
                    if (wsId != null) {
                        addEdge(fromId, wsId, scopeName);
                        continue;
                    }
                    String ga = gaLabel(d.module());
                    if (ga.isEmpty()) continue;
                    declaredGas.add(ga);
                    String toId = externalNode(ga, "declared");
                    addEdge(fromId, toId, scopeName);
                    if (transitive) {
                        expandTransitive(toId, ga);
                    }
                }
            }
        }

        private String resolveWorkspaceId(
                Dependency d, Map<String, String> idByCoord, Map<String, String> idByName) {
            if (idByCoord.isEmpty() && idByName.isEmpty()) return null;
            String id = idByCoord.get(d.module());
            if (id != null) return id;
            if (d.isWorkspace()) {
                id = idByName.get(d.workspaceName());
                if (id != null) return id;
            }
            // Bare project name match (library handle sometimes equals module name)
            return idByName.get(d.library());
        }

        private String externalNode(String ga, String kind) {
            // Stable id by GA so diamond edges converge
            String id = "a:" + ga;
            Node existing = nodes.get(id);
            if (existing != null) {
                // Prefer declared over transitive if both appear
                if ("transitive".equals(existing.kind()) && "declared".equals(kind)) {
                    nodes.put(id, new Node(id, ga, existing.version(), null, "declared"));
                }
                return id;
            }
            String version = null;
            Lockfile.Artifact art = byModule.get(ga);
            if (art != null) version = art.version();
            nodes.put(id, new Node(id, ga, version, null, kind));
            return id;
        }

        private void expandTransitive(String fromId, String fromGa) {
            Queue<String> q = new ArrayDeque<>();
            Set<String> seen = new LinkedHashSet<>();
            q.add(fromGa);
            seen.add(fromGa);
            while (!q.isEmpty()) {
                String parentGa = q.remove();
                String parentId = parentGa.equals(fromGa) ? fromId : externalNode(parentGa, kindFor(parentGa));
                Lockfile.Artifact art = byModule.get(parentGa);
                if (art == null) {
                    // Try full package key lookup via index (already in byModule under several keys)
                    continue;
                }
                for (String depRef : art.deps()) {
                    String childKey = DependencyTree.stripVersion(depRef);
                    String childGa = gaLabel(childKey);
                    if (childGa.isEmpty()) continue;
                    String childId = externalNode(childGa, kindFor(childGa));
                    addEdge(parentId, childId, null);
                    if (seen.add(childGa)) {
                        q.add(childGa);
                    }
                }
            }
        }

        private String kindFor(String ga) {
            return declaredGas.contains(ga) ? "declared" : "transitive";
        }

        private void addEdge(String from, String to, String scope) {
            if (from.equals(to)) return;
            String key = from + "\0" + to + "\0" + (scope == null ? "" : scope);
            if (!edgeKeys.add(key)) return;
            edges.add(new Edge(from, to, scope));
        }

        Graph finish(boolean workspace) {
            List<String> scopeNames = scopes.stream().map(Scope::canonical).toList();
            return new Graph(
                    workspace, scopeNames, transitive, availableScopeNames(), new ArrayList<>(nodes.values()), edges);
        }
    }
}
