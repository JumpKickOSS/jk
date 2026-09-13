// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.Scope;
import cc.jumpkick.resolver.DependencyTreeStyle.Styling;
import cc.jumpkick.resolver.WorkspaceGraph.LoadedModule;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Cargo-style dependency tree from a project + {@link Lockfile}: the nested, rail-drawn walk.
 *
 * <p>Revisit-marking is the invariant here. A module already shown higher up prints as a dimmed
 * {@code ⎋} back-reference instead of expanding again, which is what stops a diamond graph from
 * exploding the output — and it is only correct because the recursion that adds to {@code seen} is
 * the same recursion that reads it. Anything that would put the mark in one object and the render
 * in another stays out.
 *
 * <p>What is <em>not</em> that invariant lives elsewhere, one owner each: {@link
 * DependencyTreeStyle} (the operators, the marker wire form, the scope order and badge),
 * {@link TreeCoords} (a module key to a printable coordinate), {@link WorkspaceGraph} (which
 * siblings exist, and the disk reads that found them), {@link DeclaredDeps} (what the manifest
 * declares) and {@link DependencyFlatten} (the {@code --flatten} closure, whose "already seen" set
 * means cycle protection rather than a back-reference).
 */
public final class DependencyTree {

    private DependencyTree() {}

    public static String render(JkBuild project, Lockfile lock) {
        return render(project, lock, Integer.MAX_VALUE);
    }

    public static String render(JkBuild project, Lockfile lock, int maxDepth) {
        return render(project, lock, maxDepth, Styling.plain());
    }

    /**
     * Render with full styling. Labels are emitted in {@code group:artifact:version} shape (the Maven
     * coordinate convention Java developers expect), with each segment passed through its
     * corresponding {@link Styling} operator. Rail connectors ({@code ├─ }, {@code └─ }, {@code │ },
     * {@code " "}) are passed through {@link Styling#rail}.
     */
    public static String render(JkBuild project, Lockfile lock, int maxDepth, Styling styling) {
        LockGraph graph = LockGraph.forLock(lock);
        StringBuilder out = new StringBuilder();
        // Root project: group:artifact:version, styled like every other line.
        out.append(TreeCoords.formatCoord(
                        project.project().group(),
                        project.project().name(),
                        project.project().version(),
                        styling))
                .append('\n');

        List<String> roots = collectRoots(project);
        Set<String> platformMods = DeclaredDeps.platformModules(project);
        Map<String, String> declared = DeclaredDeps.versions(project, Arrays.asList(Scope.values()));
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < roots.size(); i++) {
            String root = roots.get(i);
            renderNode(
                    graph,
                    root,
                    0,
                    maxDepth,
                    i == roots.size() - 1,
                    "",
                    styling,
                    seen,
                    out,
                    declared.get(root),
                    platformMods.contains(root));
        }
        return out.toString();
    }

    /**
     * Composite-aware render: walks the full graph including {@code path} (and <em>branch</em> git)
     * dependencies, not just lockfile Maven coords. {@code projectDir} anchors {@code path} deps;
     * each path target's own tree (its {@code jk.toml} + {@code jk-lock.toml}) is recursed into. Branch
     * git deps are annotated but not recursed (resolving them needs a clone). Immutable (tag/rev) git
     * deps are materialized into the lock and render normally.
     *
     * <p>When {@code project} is a <em>workspace root</em>, the scope sections ({@code main}/{@code
     * test}/…) are the top-level nodes. Under each scope sit the workspace modules that declare at
     * least one dependency in that scope, and each such module node expands into its own deps for
     * that scope (read from the module's {@code jk.toml} + {@code jk-lock.toml}). Workspace-sibling deps
     * (a module's {@code <name>.workspace = true} entries, which point at another module) are shown
     * as a collapsed {@code [workspace]} reference rather than re-expanded.
     *
     * <p>When {@code project} is a <em>workspace member</em> ({@code jk tree .} / {@code :name}),
     * those same sibling edges are resolved to the adjacent module (version from its {@code
     * jk.toml}) instead of {@code (missing)}. Depth {@code 0} stops at the sibling; a deeper walk
     * ({@code --transitive}) continues through the sibling's declared deps and lockfile transitives.
     */
    public static String render(JkBuild project, Lockfile lock, Path projectDir, int maxDepth, Styling styling) {
        return render(project, lock, projectDir, maxDepth, styling, false);
    }

    public static String render(
            JkBuild project, Lockfile lock, Path projectDir, int maxDepth, Styling styling, boolean flatten) {
        return render(project, lock, projectDir, maxDepth, styling, flatten, null);
    }

    public static String render(
            JkBuild project,
            Lockfile lock,
            Path projectDir,
            int maxDepth,
            Styling styling,
            boolean flatten,
            @Nullable List<Scope> scopeOrder) {
        return render(project, lock, projectDir, maxDepth, styling, flatten, scopeOrder, false);
    }

    /**
     * Composite-aware render with optional {@code flatten} / {@code stack} modes and an explicit
     * {@code scopeOrder}. {@code flatten} replaces each scope's nested tree with the deduplicated,
     * sorted set of all its (transitive) dependencies; {@code maxDepth} is ignored when flattening.
     * {@code stack} collapses the per-scope sections into a single header showing every scope badge
     * on one line, with all dependencies blended (regardless of scope) into one tree. When {@code
     * scopeOrder} is non-null, only those scopes are shown, in exactly that order, instead of
     * {@link DependencyTreeStyle#defaultScopeOrder()}.
     */
    public static String render(
            JkBuild project,
            Lockfile lock,
            Path projectDir,
            int maxDepth,
            Styling styling,
            boolean flatten,
            @Nullable List<Scope> scopeOrder,
            boolean stack) {
        List<Scope> order = DependencyTreeStyle.sectionOrder(scopeOrder);
        StringBuilder out = new StringBuilder();
        // Root node: styled via rootLine, which defaults to " ● boldCoord" but
        // callers (e.g. jk tree) can override to a pill-wrapped form.
        out.append(styling.rootLine()
                        .apply(project.project().group()
                                + ":"
                                + project.project().name()
                                + ":"
                                + project.project().version()))
                .append('\n');
        Set<String> seenModules = new HashSet<>();
        int bodyStart = out.length();
        if (project.isWorkspaceRoot()) {
            if (flatten) {
                DependencyFlatten.renderWorkspaceScopes(project, projectDir, styling, order, stack, out);
            } else {
                renderWorkspaceScopes(project, projectDir, maxDepth, styling, order, stack, seenModules, out);
            }
        } else if (flatten) {
            DependencyFlatten.renderScopes(
                    project, lock, styling, order, stack, WorkspaceGraph.forMember(projectDir, lock), out);
        } else {
            renderScopeSections(
                    project,
                    lock,
                    0,
                    maxDepth,
                    "",
                    styling,
                    WorkspaceGraph.forMember(projectDir, lock),
                    order,
                    stack,
                    seenModules,
                    out);
        }
        if (out.length() == bodyStart) {
            appendEmptyScopesHint(project, projectDir, order, styling, out);
        }
        return out.toString();
    }

    /**
     * The narrowed default ({@code export, main, runtime}) can select zero populated scopes — e.g.
     * a test-only project — and the early-return sections then print nothing under the root, which
     * reads as "this project has no dependencies". Name the scopes that <em>do</em> have deps and
     * how to show them instead.
     */
    private static void appendEmptyScopesHint(
            JkBuild project, Path projectDir, List<Scope> scopeOrder, Styling styling, StringBuilder out) {
        Set<Scope> selected = new HashSet<>(DependencyTreeStyle.sectionOrder(scopeOrder));
        List<Scope> elsewhere = new ArrayList<>();
        List<LoadedModule> modules = project.isWorkspaceRoot()
                ? WorkspaceGraph.loadModules(project.workspaceModules(), projectDir)
                : List.of();
        for (Scope s : DependencyTreeStyle.allScopeOrder()) {
            if (selected.contains(s)) continue;
            boolean populated = project.isWorkspaceRoot()
                    ? modules.stream()
                            .anyMatch(m -> !m.build().dependencies().of(s).isEmpty())
                    : !project.dependencies().of(s).isEmpty();
            if (populated) elsewhere.add(s);
        }
        String msg;
        if (elsewhere.isEmpty()) {
            msg = "(no dependencies)";
        } else {
            String names =
                    elsewhere.stream().map(DependencyTreeStyle::scopeLabel).collect(Collectors.joining(", "));
            String flag = elsewhere.size() == 1 ? "-s " + DependencyTreeStyle.scopeLabel(elsewhere.get(0)) : "-s all";
            msg = "(no dependencies in the selected scopes — found in: " + names + "; try `" + flag + "`)";
        }
        out.append(styling.rail().apply("╰─ ")).append(msg).append('\n');
    }

    /**
     * Workspace-root view: scope sections (main/test/…) are the top-level nodes. Under each scope sit
     * the workspace modules that declare at least one dependency in that scope, in declaration
     * (build) order; each module node expands into its own deps for that scope, with sibling modules
     * collapsed to a {@code [workspace]} reference.
     */
    private static void renderWorkspaceScopes(
            JkBuild root,
            Path rootDir,
            int maxDepth,
            Styling styling,
            List<Scope> scopeOrder,
            boolean stack,
            Set<String> seenModules,
            StringBuilder out) {

        List<String> moduleRels = root.workspaceModules();
        WorkspaceGraph ws = WorkspaceGraph.collapse(WorkspaceGraph.modulesByName(moduleRels, rootDir));
        List<LoadedModule> modules = WorkspaceGraph.loadModules(moduleRels, rootDir);

        // Scope sections present anywhere in the workspace, in display order.
        List<Scope> sections = new ArrayList<>();
        for (Scope s : DependencyTreeStyle.sectionOrder(scopeOrder)) {
            if (modules.stream().anyMatch(m -> !m.build().dependencies().of(s).isEmpty())) {
                sections.add(s);
            }
        }
        if (sections.isEmpty()) return;

        if (stack) {
            // One badge row, then each module (with deps in ANY selected scope) shown once
            // with its dependencies blended across all selected scopes.
            out.append(styling.rail().apply("╰─"))
                    .append(DependencyTreeStyle.badgeRow(sections, styling))
                    .append('\n');
            String scopePrefix = styling.rail().apply("   ");
            List<LoadedModule> inAny = modules.stream()
                    .filter(m -> sections.stream()
                            .anyMatch(s -> !m.build().dependencies().of(s).isEmpty()))
                    .toList();
            for (int mi = 0; mi < inAny.size(); mi++) {
                renderWorkspaceModuleNode(
                        inAny.get(mi),
                        mi == inAny.size() - 1,
                        sections,
                        maxDepth,
                        scopePrefix,
                        styling,
                        ws,
                        seenModules,
                        out);
            }
            return;
        }

        for (int si = 0; si < sections.size(); si++) {
            Scope s = sections.get(si);
            boolean lastScope = si == sections.size() - 1;
            out.append(styling.rail().apply(lastScope ? "╰─" : "├─"))
                    .append(styling.scopeBadge().apply(DependencyTreeStyle.scopeLabel(s)))
                    .append('\n');
            String scopePrefix = styling.rail().apply(lastScope ? "   " : "│  ");

            List<LoadedModule> inScope = modules.stream()
                    .filter(m -> !m.build().dependencies().of(s).isEmpty())
                    .toList();
            for (int mi = 0; mi < inScope.size(); mi++) {
                renderWorkspaceModuleNode(
                        inScope.get(mi),
                        mi == inScope.size() - 1,
                        List.of(s),
                        maxDepth,
                        scopePrefix,
                        styling,
                        ws,
                        seenModules,
                        out);
            }
        }
    }

    /** A workspace module node: its coordinate, then its deps in {@code scopes} (blended). */
    private static void renderWorkspaceModuleNode(
            LoadedModule m,
            boolean lastMod,
            List<Scope> scopes,
            int maxDepth,
            String scopePrefix,
            Styling styling,
            WorkspaceGraph ws,
            Set<String> seenModules,
            StringBuilder out) {

        String label = TreeCoords.formatCoord(
                m.build().project().group(),
                m.build().project().name(),
                m.build().project().version(),
                styling);
        String tag = m.lock() == null ? " [not locked]" : "";
        out.append(scopePrefix)
                .append(styling.rail().apply(lastMod ? "╰─ " : "├─ "))
                .append(label)
                .append(styling.rail().apply(tag))
                .append('\n');
        String modPrefix = scopePrefix + styling.rail().apply(lastMod ? "   " : "│  ");
        renderScopeDepList(m.build(), m.lock(), scopes, 1, maxDepth, modPrefix, styling, ws, seenModules, out);
    }

    /**
     * Single-project view: scope sections (Main, Test, …) are nodes, each listing its direct
     * dependencies. A scope section only appears when it has ≥1 dep.
     */
    private static void renderScopeSections(
            JkBuild project,
            Lockfile lock,
            int depth,
            int maxDepth,
            String prefix,
            Styling styling,
            WorkspaceGraph ws,
            List<Scope> scopeOrder,
            boolean stack,
            Set<String> seenModules,
            StringBuilder out) {

        List<Scope> sections = new ArrayList<>();
        for (Scope s : DependencyTreeStyle.sectionOrder(scopeOrder)) {
            if (!project.dependencies().of(s).isEmpty()) sections.add(s);
        }
        if (sections.isEmpty()) return;

        if (stack) {
            // One badge row, all scopes' deps blended into a single tree.
            out.append(prefix)
                    .append(styling.rail().apply("╰─"))
                    .append(DependencyTreeStyle.badgeRow(sections, styling))
                    .append('\n');
            String scopePrefix = prefix + styling.rail().apply("   ");
            renderScopeDepList(project, lock, sections, depth, maxDepth, scopePrefix, styling, ws, seenModules, out);
            return;
        }
        for (int si = 0; si < sections.size(); si++) {
            Scope s = sections.get(si);
            boolean lastScope = si == sections.size() - 1;
            // Scope header: ├─/╰─ then the badge (no trailing space — badge abuts).
            out.append(prefix)
                    .append(styling.rail().apply(lastScope ? "╰─" : "├─"))
                    .append(styling.scopeBadge().apply(DependencyTreeStyle.scopeLabel(s)))
                    .append('\n');
            // 4-wide continuation (matching a standard tree node) so the deps nest a
            // space further in than the 3-char scope connector — aligning under the badge.
            String scopePrefix = prefix + styling.rail().apply(lastScope ? "   " : "│  ");
            renderScopeDepList(project, lock, List.of(s), depth, maxDepth, scopePrefix, styling, ws, seenModules, out);
        }
    }

    /**
     * Render {@code project}'s direct dependencies across {@code scopes} (deduped, sorted) under
     * {@code prefix}, each expanded transitively via {@code lock}. With a single scope this is one
     * section's deps; with several it blends them (the {@code --stack} view).
     */
    private static void renderScopeDepList(
            JkBuild project,
            @Nullable Lockfile lock,
            List<Scope> scopes,
            int depth,
            int maxDepth,
            String prefix,
            Styling styling,
            WorkspaceGraph ws,
            Set<String> seenModules,
            StringBuilder out) {
        renderScopeDepList(project, lock, scopes, depth, maxDepth, prefix, styling, ws, seenModules, out, false);
    }

    /**
     * {@code siblingSurface}: rendering a consumed sibling's contributed deps — its module
     * (workspace) edges then chain only through {@code WorkspaceClasspath.SIBLING_MODULE_SCOPES}
     *; external deps keep the full export/main/runtime surface.
     */
    private static void renderScopeDepList(
            JkBuild project,
            @Nullable Lockfile lock,
            List<Scope> scopes,
            int depth,
            int maxDepth,
            String prefix,
            Styling styling,
            WorkspaceGraph ws,
            Set<String> seenModules,
            StringBuilder out,
            boolean siblingSurface) {

        LockGraph graph = LockGraph.forLock(lock);
        // Declared versions (and which modules are PLATFORM-only pins / BOMs).
        Map<String, String> declaredVersions = DeclaredDeps.versions(project, scopes);
        Set<String> platformModules = DeclaredDeps.platformModules(project);
        List<String> mods = scopes.stream()
                .flatMap(s -> project.dependencies().of(s).stream()
                        .filter(d ->
                                !siblingSurface || WorkspaceGraph.chainsModuleEdges(s) || !ws.isSiblingDep(d.module()))
                        .map(Dependency::module))
                .distinct()
                .sorted()
                .toList();
        for (int di = 0; di < mods.size(); di++) {
            String mod = mods.get(di);
            renderDep(
                    mod,
                    graph,
                    depth,
                    maxDepth,
                    di == mods.size() - 1,
                    prefix,
                    styling,
                    ws,
                    seenModules,
                    out,
                    declaredVersions.get(mod),
                    platformModules.contains(mod));
        }
    }

    /**
     * Render one direct dependency, dispatching on its kind (maven coordinate, or a {@code
     * workspace = true} sibling reference).
     */
    private static void renderDep(
            String module,
            LockGraph graph,
            int depth,
            int maxDepth,
            boolean isLast,
            String prefix,
            Styling styling,
            WorkspaceGraph ws,
            Set<String> seenModules,
            StringBuilder out,
            @Nullable String declaredVersion,
            boolean platformPin) {

        LoadedModule sibling = ws.sibling(module);
        if (sibling != null) {
            // Member graph only: collapsed [workspace] rows come from isWorkspaceRef.
            renderSiblingModule(sibling, depth, maxDepth, isLast, prefix, styling, ws, seenModules, out);
            return;
        }
        if (Dependency.isWorkspaceRef(module)) {
            out.append(prefix)
                    .append(styling.rail().apply(isLast ? "╰─ " : "├─ "))
                    .append(TreeCoords.coordLabel(ws.collapsedCoord(module), styling))
                    .append(styling.rail().apply(" [workspace]"))
                    .append('\n');
            return;
        }
        renderNode(
                graph,
                module,
                depth,
                maxDepth,
                isLast,
                prefix,
                styling,
                seenModules,
                out,
                declaredVersion,
                platformPin);
    }

    /**
     * A workspace sibling as a real module node (version from its {@code jk.toml}). When depth
     * allows, walk its contributed surface ({@link WorkspaceGraph#siblingContributedScopes()}) —
     * and those deps' lockfile transitives.
     */
    private static void renderSiblingModule(
            LoadedModule sibling,
            int depth,
            int maxDepth,
            boolean isLast,
            String prefix,
            Styling styling,
            WorkspaceGraph ws,
            Set<String> seenModules,
            StringBuilder out) {

        String ga = WorkspaceGraph.moduleGa(sibling.build());
        String connector = isLast ? "╰─ " : "├─ ";
        Project p = sibling.build().project();
        if (!seenModules.add(ga)) {
            String coord = p.group() + ":" + p.name() + ":" + p.version();
            out.append(prefix)
                    .append(styling.reference().apply(connector + coord + " ⎋"))
                    .append('\n');
            return;
        }
        out.append(prefix)
                .append(styling.rail().apply(connector))
                .append(TreeCoords.formatCoord(p.group(), p.name(), p.version(), styling))
                .append('\n');
        if (depth >= maxDepth) return;
        String childPrefix = prefix + styling.rail().apply(isLast ? "   " : "│  ");
        renderScopeDepList(
                sibling.build(),
                sibling.lock(),
                WorkspaceGraph.siblingContributedScopes(),
                depth + 1,
                maxDepth,
                childPrefix,
                styling,
                ws,
                seenModules,
                out,
                /* siblingSurface= */ true);
    }

    /**
     * @param declaredVersion version from jk.toml when known (platform BOMs)
     * @param platformPin true when this module is a PLATFORM BOM / pin source — not a lock jar row
     */
    private static void renderNode(
            LockGraph graph,
            String module,
            int depth,
            int maxDepth,
            boolean isLast,
            String prefix,
            Styling styling,
            Set<String> seen,
            StringBuilder out,
            @Nullable String declaredVersion,
            boolean platformPin) {

        Lockfile.Artifact pkg = graph.artifact(module);
        // module may be GA or full package key (g:a:type:classifier); display as GA.
        TreeCoords.Ga ga = TreeCoords.split(module);
        String groupId = ga.group();
        String artifactId = ga.artifact();

        // Platform BOMs are pin sources (pinned-by on managed jars), not lock [[artifact]] rows.
        // Prefer declared version; never mark them "(missing)" solely because the lock has no BOM jar.
        String displayVersion = pkg != null ? pkg.version() : (platformPin ? declaredVersion : null);
        boolean missing = pkg == null && !platformPin;

        // ╰─ for the last child (rounded arc); ├─ for the rest.
        // Standard "rounded tree" convention used by eza, tre, etc.
        String connector = isLast ? "╰─ " : "├─ ";

        if (!seen.add(module)) {
            // Already shown higher up — dim the WHOLE row (connector + coord + ⎋)
            // so it reads as a back-reference, not a fresh expansion.
            String coord;
            if (displayVersion != null) {
                coord = groupId + ":" + artifactId + ":" + displayVersion;
                if (platformPin && pkg == null) coord = coord + " (platform)";
            } else if (missing) {
                coord = groupId + ":" + artifactId + DependencyTreeStyle.MISSING_SUFFIX;
            } else {
                coord = groupId + ":" + artifactId + " (platform)";
            }
            out.append(prefix)
                    .append(styling.reference().apply(connector + coord + " ⎋"))
                    .append('\n');
            return;
        }

        String label;
        if (displayVersion != null) {
            label = TreeCoords.formatCoord(groupId, artifactId, displayVersion, styling);
            if (platformPin && pkg == null) {
                label = label + styling.rail().apply(" (platform)");
            }
        } else if (missing) {
            // No version available — "group:artifact (missing)", marker unstyled.
            label = styling.group().apply(groupId)
                    + ":"
                    + styling.artifact().apply(artifactId)
                    + DependencyTreeStyle.MISSING_SUFFIX;
        } else {
            label = styling.group().apply(groupId)
                    + ":"
                    + styling.artifact().apply(artifactId)
                    + styling.rail().apply(" (platform)");
        }

        out.append(prefix).append(styling.rail().apply(connector)).append(label).append('\n');

        // Platform pin sources are leaves in the tree (no jar deps to expand).
        if (pkg == null || depth >= maxDepth || platformPin) return;

        String childPrefix = prefix + styling.rail().apply(isLast ? "   " : "│  ");
        List<String> children = graph.forwardSorted(module);
        for (int i = 0; i < children.size(); i++) {
            renderNode(
                    graph,
                    children.get(i),
                    depth + 1,
                    maxDepth,
                    i == children.size() - 1,
                    childPrefix,
                    styling,
                    seen,
                    out,
                    null,
                    false);
        }
    }

    static List<String> collectRoots(JkBuild project) {
        return Stream.of(Scope.values())
                .flatMap(s -> project.dependencies().of(s).stream())
                .map(Dependency::module)
                .sorted()
                .distinct()
                .collect(ArrayList::new, List::add, List::addAll);
    }

    /**
     * Declared dependency modules for provenance walks. For a single project this is the same as
     * {@link #collectRoots(JkBuild)}. For a workspace root, also unions every workspace module's
     * declared deps (read from each module's {@code jk.toml}) so {@code jk why} can walk from the
     * real roots — the root {@code jk.toml} typically has none.
     */
    static List<String> collectRoots(JkBuild project, @Nullable Path projectDir) {
        if (project == null) return List.of();
        if (!project.isWorkspaceRoot() || projectDir == null) {
            return collectRoots(project);
        }
        Set<String> roots = new LinkedHashSet<>(collectRoots(project));
        for (LoadedModule m : WorkspaceGraph.loadModules(project.workspaceModules(), projectDir)) {
            roots.addAll(collectRoots(m.build()));
        }
        return new ArrayList<>(roots);
    }

    /**
     * The selector each declared Maven root was written with, by {@code group:artifact} — the
     * same roots as {@link #collectRoots(JkBuild, Path)}, with the manifest text ({@code ^2.21},
     * {@code =1.15.0}, …) beside each. The first declaration of a module wins; workspace, git,
     * path and file edges have no Maven selector and are left out.
     */
    static Map<String, String> collectRootSelectors(@Nullable JkBuild project, @Nullable Path projectDir) {
        Map<String, String> out = new LinkedHashMap<>();
        if (project == null) return out;
        putRootSelectors(project, out);
        if (project.isWorkspaceRoot() && projectDir != null) {
            for (LoadedModule m : WorkspaceGraph.loadModules(project.workspaceModules(), projectDir)) {
                putRootSelectors(m.build(), out);
            }
        }
        return out;
    }

    private static void putRootSelectors(JkBuild build, Map<String, String> out) {
        for (Scope s : Scope.values()) {
            for (Dependency d : build.dependencies().of(s)) {
                if (d.isWorkspace() || d.isGit() || d.isPath() || d.isFile()) continue;
                out.putIfAbsent(LockGraph.ga(d.module()), d.version().raw().trim());
            }
        }
    }
}
