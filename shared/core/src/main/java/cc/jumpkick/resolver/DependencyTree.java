// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * Cargo-style dependency tree from a project + {@link Lockfile}. Revisited subtrees are marked
 * so diamond graphs do not explode the output.
 */
public final class DependencyTree {

    /**
     * Suffix appended to a node whose module has no resolved {@link Lockfile.Artifact} — it is
     * present in the graph but absent from the lockfile/local cache. Callers can scan rendered output
     * for this marker to decide whether to surface a hint.
     */
    public static final String MISSING_SUFFIX = " (missing)";

    /**
     * Optional ANSI styling for tree output. Each operator wraps the matching piece of text with
     * whatever escape sequence the caller prefers. Defaults to {@link #plain()} (identity everywhere)
     * so tests and non-color consumers get raw ASCII back.
     *
     * <p>The fields map directly to the rendered shape:
     *
     * <pre>
     *   {rail}└─ {/rail}{group}{group}{/group}:{artifact}{artifact}{/artifact}:{version}{version}{/version}
     * </pre>
     *
     * <p>{@code reference} styles a whole already-shown row (the {@code ⎋} back-reference lines): the
     * connector, coordinate, and marker are dimmed as one unit so the reader sees at a glance it's a
     * pointer to an earlier expansion, not a fresh node. {@code scopeBadge} styles a scope section
     * header (the {@code Main} / {@code Test} / … badges grouping a project's direct dependencies).
     */
    public record Styling(
            UnaryOperator<String> rail,
            UnaryOperator<String> group,
            UnaryOperator<String> artifact,
            UnaryOperator<String> version,
            UnaryOperator<String> reference,
            UnaryOperator<String> scopeBadge,
            UnaryOperator<String> boldCoord,
            UnaryOperator<String> rootLine) {

        /**
         * Back-compat 7-arg form (no {@code rootLine}): the root line is rendered as {@code " ● coord"}.
         */
        public Styling(
                UnaryOperator<String> rail,
                UnaryOperator<String> group,
                UnaryOperator<String> artifact,
                UnaryOperator<String> version,
                UnaryOperator<String> reference,
                UnaryOperator<String> scopeBadge,
                UnaryOperator<String> boldCoord) {
            this(
                    rail,
                    group,
                    artifact,
                    version,
                    reference,
                    scopeBadge,
                    boldCoord,
                    gav -> " " + rail.apply("●") + " " + boldCoord.apply(gav));
        }

        /**
         * Back-compat 6-arg form (no {@code boldCoord}): the root project coordinate renders with the
         * same {@code group/artifact/version} stylers as any other.
         */
        public Styling(
                UnaryOperator<String> rail,
                UnaryOperator<String> group,
                UnaryOperator<String> artifact,
                UnaryOperator<String> version,
                UnaryOperator<String> reference,
                UnaryOperator<String> scopeBadge) {
            this(rail, group, artifact, version, reference, scopeBadge, UnaryOperator.identity());
        }

        /**
         * The wire form (thin client): each styled piece wraps in {@code ⟦<kind>…⟧} marker tags
         * instead of ANSI escapes, so the engine can run the full composite-aware render — which
         * needs the parsed models — while the client, which owns the Theme, substitutes the tags
         * with its real stylers afterwards ({@link #applyStyling}). Tags never nest: every styler
         * in this class receives raw text.
         */
        public static Styling markers() {
            return new Styling(tag('r'), tag('g'), tag('a'), tag('v'), tag('f'), tag('b'), tag('c'));
        }

        private static UnaryOperator<String> tag(char kind) {
            return s -> String.valueOf(MARK_OPEN) + kind + s + MARK_CLOSE;
        }

        public static Styling plain() {
            return new Styling(
                    UnaryOperator.identity(),
                    UnaryOperator.identity(),
                    UnaryOperator.identity(),
                    UnaryOperator.identity(),
                    UnaryOperator.identity(),
                    UnaryOperator.identity());
        }
    }

    /** Marker-tag delimiters for {@link Styling#markers()} — printable, so they survive Jsonl. */
    static final char MARK_OPEN = '\u27e6'; // ⟦

    static final char MARK_CLOSE = '\u27e7'; // ⟧

    /**
     * Substitute {@link Styling#markers()} tags in an engine-rendered tree with this client's real
     * stylers. Unknown kinds render unstyled; an unterminated tag renders literally (defensive —
     * the engine only ever emits balanced tags).
     */
    public static String applyStyling(String rendered, Styling styling) {
        StringBuilder out = new StringBuilder(rendered.length());
        int i = 0;
        while (i < rendered.length()) {
            char c = rendered.charAt(i);
            if (c != MARK_OPEN || i + 1 >= rendered.length()) {
                out.append(c);
                i++;
                continue;
            }
            int close = rendered.indexOf(MARK_CLOSE, i + 1);
            if (close < 0) {
                out.append(c);
                i++;
                continue;
            }
            char kind = rendered.charAt(i + 1);
            String content = rendered.substring(i + 2, close);
            UnaryOperator<String> styler =
                    switch (kind) {
                        case 'r' -> styling.rail();
                        case 'g' -> styling.group();
                        case 'a' -> styling.artifact();
                        case 'v' -> styling.version();
                        case 'f' -> styling.reference();
                        case 'b' -> styling.scopeBadge();
                        case 'c' -> styling.boldCoord();
                        default -> UnaryOperator.identity();
                    };
            out.append(styler.apply(content));
            i = close + 1;
        }
        return out.toString();
    }

    private DependencyTree() {}

    public static String render(JkBuild project, Lockfile lock) {
        return render(project, lock, Integer.MAX_VALUE);
    }

    public static String render(JkBuild project, Lockfile lock, int maxDepth) {
        return render(project, lock, maxDepth, Styling.plain());
    }

    /** Back-compat overload — rail-only styling. */
    public static String render(JkBuild project, Lockfile lock, int maxDepth, UnaryOperator<String> railStyler) {
        return render(
                project,
                lock,
                maxDepth,
                new Styling(
                        railStyler,
                        UnaryOperator.identity(),
                        UnaryOperator.identity(),
                        UnaryOperator.identity(),
                        UnaryOperator.identity(),
                        UnaryOperator.identity()));
    }

    /**
     * Render with full styling. Labels are emitted in {@code group:artifact:version} shape (the Maven
     * coordinate convention Java developers expect), with each segment passed through its
     * corresponding {@link Styling} operator. Rail connectors ({@code ├─ }, {@code └─ }, {@code │ },
     * {@code " "}) are passed through {@link Styling#rail}.
     */
    public static String render(JkBuild project, Lockfile lock, int maxDepth, Styling styling) {
        Map<String, Lockfile.Artifact> byModule = indexByModule(lock);
        StringBuilder out = new StringBuilder();
        // Root project: group:artifact:version, styled like every other line.
        out.append(formatCoord(
                        project.project().group(),
                        project.project().name(),
                        project.project().version(),
                        styling))
                .append('\n');

        List<String> roots = collectRoots(project);
        Set<String> platformMods = platformModules(project);
        Map<String, String> declared = declaredVersions(project, java.util.Arrays.asList(Scope.values()));
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < roots.size(); i++) {
            String root = roots.get(i);
            renderNode(
                    byModule,
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
            List<Scope> scopeOrder) {
        return render(project, lock, projectDir, maxDepth, styling, flatten, scopeOrder, false);
    }

    /**
     * Composite-aware render with optional {@code flatten} / {@code stack} modes and an explicit
     * {@code scopeOrder}. {@code flatten} replaces each scope's nested tree with the deduplicated,
     * sorted set of all its (transitive) dependencies; {@code maxDepth} is ignored when flattening.
     * {@code stack} collapses the per-scope sections into a single header showing every scope badge
     * on one line, with all dependencies blended (regardless of scope) into one tree. When {@code
     * scopeOrder} is non-null, only those scopes are shown, in exactly that order, instead of the
     * default {@link #SCOPE_SECTIONS}.
     */
    public static String render(
            JkBuild project,
            Lockfile lock,
            Path projectDir,
            int maxDepth,
            Styling styling,
            boolean flatten,
            List<Scope> scopeOrder,
            boolean stack) {
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
        Set<String> seenDirs = new HashSet<>();
        int bodyStart = out.length();
        if (project.isWorkspaceRoot()) {
            if (flatten) {
                renderFlatWorkspaceScopes(project, projectDir, styling, scopeOrder, stack, out);
            } else {
                renderWorkspaceScopes(
                        project, projectDir, maxDepth, styling, scopeOrder, stack, seenModules, seenDirs, out);
            }
        } else if (flatten) {
            renderFlatScopes(project, lock, projectDir, styling, scopeOrder, stack, out);
        } else {
            renderScopeSections(
                    project,
                    lock,
                    projectDir,
                    0,
                    maxDepth,
                    "",
                    styling,
                    Map.of(),
                    scopeOrder,
                    stack,
                    seenModules,
                    seenDirs,
                    out);
        }
        if (out.length() == bodyStart) {
            appendEmptyScopesHint(project, projectDir, scopeOrder, styling, out);
        }
        return out.toString();
    }

    /**
     * The narrowed default ({@code export, main, runtime}) can select zero populated scopes — e.g.
     * a test-only project — and the early-return sections then print nothing under the root, which
     * reads as "this project has no dependencies". Name the scopes that <em>do</em> have deps and
     * how to show them instead (JK-1640).
     */
    private static void appendEmptyScopesHint(
            JkBuild project, Path projectDir, List<Scope> scopeOrder, Styling styling, StringBuilder out) {
        Set<Scope> selected = new HashSet<>(sectionOrder(scopeOrder));
        List<Scope> elsewhere = new ArrayList<>();
        List<LoadedModule> modules =
                project.isWorkspaceRoot() ? loadModules(project.workspace().modules(), projectDir) : List.of();
        for (Scope s : allScopeOrder()) {
            if (selected.contains(s)) continue;
            boolean populated = project.isWorkspaceRoot()
                    ? modules.stream().anyMatch(m -> !m.build().dependencies().of(s).isEmpty())
                    : !project.dependencies().of(s).isEmpty();
            if (populated) elsewhere.add(s);
        }
        String msg;
        if (elsewhere.isEmpty()) {
            msg = "(no dependencies)";
        } else {
            String names = elsewhere.stream().map(DependencyTree::scopeLabel).collect(java.util.stream.Collectors.joining(", "));
            String flag = elsewhere.size() == 1 ? "-s " + scopeLabel(elsewhere.get(0)) : "-s all";
            msg = "(no dependencies in the selected scopes — found in: " + names + "; try `" + flag + "`)";
        }
        out.append(styling.rail().apply("╰─ ")).append(msg).append('\n');
    }

    /**
     * Default scopes for {@code jk tree} (and empty {@code --scopes}): production runtime classpath
     * — {@code export}, {@code main}, {@code runtime}. Matches {@code ClasspathResolver.RUNTIME} /
     * the {@code exec}/{@code run} meta-scopes. Use {@link #allScopeOrder()} for every scope.
     */
    public static List<Scope> defaultScopeOrder() {
        return List.of(Scope.EXPORT, Scope.MAIN, Scope.RUNTIME);
    }

    /** Every scope in display order — the {@code --scopes all} expansion. */
    public static List<Scope> allScopeOrder() {
        return List.of(SCOPE_SECTIONS);
    }

    /** The scope sections to consider, in display order: an explicit override or the default set. */
    private static List<Scope> sectionOrder(List<Scope> override) {
        return override != null ? override : defaultScopeOrder();
    }

    /** All scope badges joined on one line — the {@code --stack} header. */
    private static String badgeRow(List<Scope> scopes, Styling styling) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < scopes.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(styling.scopeBadge().apply(scopeLabel(scopes.get(i))));
        }
        return sb.toString();
    }

    /**
     * Map of each workspace module's short name → its full {@code group:artifact} coord, used to
     * resolve a sibling's {@code workspace:<name>} dep reference back to a readable coordinate when
     * collapsing it in a module's subtree.
     */
    private static Map<String, String> workspaceModulesByName(List<String> modules, Path rootDir) {
        Map<String, String> byName = new HashMap<>();
        if (rootDir == null) return byName;
        for (String m : modules) {
            try {
                JkBuild b = JkBuildParser.parse(rootDir.resolve(m).normalize().resolve("jk.toml"));
                byName.put(
                        b.project().name(),
                        b.project().group() + ":" + b.project().name());
            } catch (Exception ignored) {
                // unreadable module jk.toml — sibling refs to it fall back to the raw module
            }
        }
        return byName;
    }

    /** A workspace module loaded for the scope-first view (build is required; lock may be absent). */
    private record LoadedModule(JkBuild build, Lockfile lock, Path dir) {}

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
            Set<String> seenDirs,
            StringBuilder out) {

        List<String> moduleRels = root.workspace().modules();
        Map<String, String> byName = workspaceModulesByName(moduleRels, rootDir);
        List<LoadedModule> modules = loadModules(moduleRels, rootDir);

        // Scope sections present anywhere in the workspace, in display order.
        List<Scope> sections = new ArrayList<>();
        for (Scope s : sectionOrder(scopeOrder)) {
            if (modules.stream().anyMatch(m -> !m.build().dependencies().of(s).isEmpty())) {
                sections.add(s);
            }
        }
        if (sections.isEmpty()) return;

        if (stack) {
            // One badge row, then each module (with deps in ANY selected scope) shown once
            // with its dependencies blended across all selected scopes.
            out.append(styling.rail().apply("╰─"))
                    .append(badgeRow(sections, styling))
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
                        byName,
                        seenModules,
                        seenDirs,
                        out);
            }
            return;
        }

        for (int si = 0; si < sections.size(); si++) {
            Scope s = sections.get(si);
            boolean lastScope = si == sections.size() - 1;
            out.append(styling.rail().apply(lastScope ? "╰─" : "├─"))
                    .append(styling.scopeBadge().apply(scopeLabel(s)))
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
                        byName,
                        seenModules,
                        seenDirs,
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
            Map<String, String> byName,
            Set<String> seenModules,
            Set<String> seenDirs,
            StringBuilder out) {

        String label = formatCoord(
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
        renderScopeDepList(
                m.build(),
                m.lock(),
                m.dir(),
                scopes,
                1,
                maxDepth,
                modPrefix,
                styling,
                byName,
                seenModules,
                seenDirs,
                out);
    }

    /** Scopes shown as sections, in display order; only non-empty ones render. */
    private static final Scope[] SCOPE_SECTIONS = {
        Scope.EXPORT,
        Scope.MAIN,
        Scope.RUNTIME,
        Scope.PROVIDED,
        Scope.PROCESSOR,
        Scope.PLATFORM,
        Scope.TEST,
        Scope.DEV,
        Scope.TEST_DEV,
    };

    /**
     * Single-project view: scope sections (Main, Test, …) are nodes, each listing its direct
     * dependencies. A scope section only appears when it has ≥1 dep.
     */
    private static void renderScopeSections(
            JkBuild project,
            Lockfile lock,
            Path dir,
            int depth,
            int maxDepth,
            String prefix,
            Styling styling,
            Map<String, String> modules,
            List<Scope> scopeOrder,
            boolean stack,
            Set<String> seenModules,
            Set<String> seenDirs,
            StringBuilder out) {

        List<Scope> sections = new ArrayList<>();
        for (Scope s : sectionOrder(scopeOrder)) {
            if (!project.dependencies().of(s).isEmpty()) sections.add(s);
        }
        if (sections.isEmpty()) return;

        if (stack) {
            // One badge row, all scopes' deps blended into a single tree.
            out.append(prefix)
                    .append(styling.rail().apply("╰─"))
                    .append(badgeRow(sections, styling))
                    .append('\n');
            String scopePrefix = prefix + styling.rail().apply("   ");
            renderScopeDepList(
                    project,
                    lock,
                    dir,
                    sections,
                    depth,
                    maxDepth,
                    scopePrefix,
                    styling,
                    modules,
                    seenModules,
                    seenDirs,
                    out);
            return;
        }
        for (int si = 0; si < sections.size(); si++) {
            Scope s = sections.get(si);
            boolean lastScope = si == sections.size() - 1;
            // Scope header: ├─/╰─ then the badge (no trailing space — badge abuts).
            out.append(prefix)
                    .append(styling.rail().apply(lastScope ? "╰─" : "├─"))
                    .append(styling.scopeBadge().apply(scopeLabel(s)))
                    .append('\n');
            // 4-wide continuation (matching a standard tree node) so the deps nest a
            // space further in than the 3-char scope connector — aligning under the badge.
            String scopePrefix = prefix + styling.rail().apply(lastScope ? "   " : "│  ");
            renderScopeDepList(
                    project,
                    lock,
                    dir,
                    List.of(s),
                    depth,
                    maxDepth,
                    scopePrefix,
                    styling,
                    modules,
                    seenModules,
                    seenDirs,
                    out);
        }
    }

    /**
     * Render {@code project}'s direct dependencies across {@code scopes} (deduped, sorted) under
     * {@code prefix}, each expanded transitively via {@code lock}. With a single scope this is one
     * section's deps; with several it blends them (the {@code --stack} view).
     */
    private static void renderScopeDepList(
            JkBuild project,
            Lockfile lock,
            Path dir,
            List<Scope> scopes,
            int depth,
            int maxDepth,
            String prefix,
            Styling styling,
            Map<String, String> modules,
            Set<String> seenModules,
            Set<String> seenDirs,
            StringBuilder out) {

        Map<String, Lockfile.Artifact> byModule = lock == null ? Map.of() : indexByModule(lock);
        Map<String, Dependency> composite = Map.of();
        // Declared versions (and which modules are PLATFORM-only pins / BOMs).
        Map<String, String> declaredVersions = declaredVersions(project, scopes);
        Set<String> platformModules = platformModules(project);
        List<String> mods = scopes.stream()
                .flatMap(s -> project.dependencies().of(s).stream())
                .map(Dependency::module)
                .distinct()
                .sorted()
                .toList();
        for (int di = 0; di < mods.size(); di++) {
            String mod = mods.get(di);
            renderDep(
                    mod,
                    composite,
                    byModule,
                    dir,
                    depth,
                    maxDepth,
                    di == mods.size() - 1,
                    prefix,
                    styling,
                    modules,
                    seenModules,
                    seenDirs,
                    out,
                    declaredVersions.get(mod),
                    platformModules.contains(mod));
        }
    }

    /** Concrete version literals from declared deps in {@code scopes} (Exact / caret-tilde anchors). */
    private static Map<String, String> declaredVersions(JkBuild project, List<Scope> scopes) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (Scope s : scopes) {
            for (Dependency d : project.dependencies().of(s)) {
                String v = versionLiteral(d.version());
                if (v != null) out.putIfAbsent(d.module(), v);
            }
        }
        return out;
    }

    private static Set<String> platformModules(JkBuild project) {
        return project.dependencies().of(Scope.PLATFORM).stream()
                .map(Dependency::module)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    /**
     * Concrete version from a selector when one is known (platform BOMs must pin). {@code null} for
     * {@code latest} / empty.
     */
    private static String versionLiteral(cc.jumpkick.model.VersionSelector sel) {
        if (sel == null) return null;
        return switch (sel) {
            case cc.jumpkick.model.VersionSelector.Exact e -> e.version();
            case cc.jumpkick.model.VersionSelector.Caret c -> c.version();
            case cc.jumpkick.model.VersionSelector.Tilde t -> t.version();
            default -> null;
        };
    }

    /**
     * Load each workspace module (declaration order); a module whose jk.toml can't be parsed is
     * dropped.
     */
    private static List<LoadedModule> loadModules(List<String> moduleRels, Path rootDir) {
        JkBuild rootBuild = null;
        if (rootDir != null) {
            try {
                Path rootToml = rootDir.resolve("jk.toml");
                if (Files.isRegularFile(rootToml)) rootBuild = JkBuildParser.parse(rootToml);
            } catch (Exception ignored) {
                // inheritance best-effort
            }
        }
        List<LoadedModule> modules = new ArrayList<>();
        for (String rel : moduleRels) {
            Path dir = rootDir == null ? null : rootDir.resolve(rel).normalize();
            JkBuild build = null;
            Lockfile lock = null;
            try {
                Path toml = dir == null ? null : dir.resolve("jk.toml");
                Path lf = dir == null ? null : cc.jumpkick.lock.LockPaths.lockFile(dir);
                if (toml != null && Files.isRegularFile(toml)) build = JkBuildParser.parseLocal(toml);
                if (lf != null && Files.isRegularFile(lf)) lock = LockfileReader.read(lf);
            } catch (Exception ignored) {
                // unreadable module — dropped (can't read its scopes)
            }
            if (build != null) {
                if (rootBuild != null) {
                    build = cc.jumpkick.config.WorkspaceLoader.inheritFromRoot(build, rootBuild);
                }
                modules.add(new LoadedModule(build, lock, dir));
            }
        }
        return modules;
    }

    // --- flatten mode --------------------------------------------------------

    /** One flattened dependency: {@code group:artifact}, optional resolved version, and a tag. */
    private record FlatDep(String module, String version, String tag) {}

    /** Single-project flatten: each scope lists its full transitive dep closure, flat + sorted. */
    private static void renderFlatScopes(
            JkBuild project,
            Lockfile lock,
            Path dir,
            Styling styling,
            List<Scope> scopeOrder,
            boolean stack,
            StringBuilder out) {

        Map<String, Lockfile.Artifact> byModule = lock == null ? Map.of() : indexByModule(lock);
        Map<String, Dependency> composite = Map.of();
        List<Scope> sections = new ArrayList<>();
        for (Scope s : sectionOrder(scopeOrder)) {
            if (!project.dependencies().of(s).isEmpty()) sections.add(s);
        }
        if (sections.isEmpty()) return;

        Set<String> platformMods = platformModules(project);
        Map<String, String> declared = declaredVersions(project, sections);
        if (stack) {
            Map<String, FlatDep> collected = new TreeMap<>();
            Set<String> visited = new HashSet<>();
            for (Scope s : sections) {
                for (String m : directModules(project, s)) {
                    collectFlat(
                            m,
                            composite,
                            byModule,
                            Map.of(),
                            visited,
                            collected,
                            declared.get(m),
                            platformMods.contains(m));
                }
            }
            renderFlatSection(badgeRow(sections, styling), true, collected, "", styling, out);
            return;
        }
        for (int si = 0; si < sections.size(); si++) {
            Scope s = sections.get(si);
            Map<String, FlatDep> collected = new TreeMap<>();
            Set<String> visited = new HashSet<>();
            for (String m : directModules(project, s)) {
                collectFlat(
                        m,
                        composite,
                        byModule,
                        Map.of(),
                        visited,
                        collected,
                        declared.get(m),
                        s == Scope.PLATFORM || platformMods.contains(m));
            }
            renderFlatSection(
                    styling.scopeBadge().apply(scopeLabel(s)), si == sections.size() - 1, collected, "", styling, out);
        }
    }

    /** Workspace-root flatten: each scope is the union of every module's closure for that scope. */
    private static void renderFlatWorkspaceScopes(
            JkBuild root, Path rootDir, Styling styling, List<Scope> scopeOrder, boolean stack, StringBuilder out) {

        Map<String, String> byName = workspaceModulesByName(root.workspace().modules(), rootDir);
        List<LoadedModule> modules = loadModules(root.workspace().modules(), rootDir);

        List<Scope> sections = new ArrayList<>();
        for (Scope s : sectionOrder(scopeOrder)) {
            if (modules.stream().anyMatch(m -> !m.build().dependencies().of(s).isEmpty())) {
                sections.add(s);
            }
        }
        if (sections.isEmpty()) return;

        if (stack) {
            Map<String, FlatDep> collected = new TreeMap<>();
            Set<String> visited = new HashSet<>();
            for (LoadedModule m : modules) {
                Map<String, Lockfile.Artifact> byModule = m.lock() == null ? Map.of() : indexByModule(m.lock());
                Map<String, Dependency> composite = Map.of();
                for (Scope s : sections) {
                    for (String dep : directModules(m.build(), s)) {
                        collectFlat(dep, composite, byModule, byName, visited, collected);
                    }
                }
            }
            renderFlatSection(badgeRow(sections, styling), true, collected, "", styling, out);
            return;
        }
        for (int si = 0; si < sections.size(); si++) {
            Scope s = sections.get(si);
            Map<String, FlatDep> collected = new TreeMap<>();
            Set<String> visited = new HashSet<>();
            for (LoadedModule m : modules) {
                if (m.build().dependencies().of(s).isEmpty()) continue;
                Map<String, Lockfile.Artifact> byModule = m.lock() == null ? Map.of() : indexByModule(m.lock());
                Map<String, Dependency> composite = Map.of();
                for (String dep : directModules(m.build(), s)) {
                    collectFlat(dep, composite, byModule, byName, visited, collected);
                }
            }
            renderFlatSection(
                    styling.scopeBadge().apply(scopeLabel(s)), si == sections.size() - 1, collected, "", styling, out);
        }
    }

    /** Emit a header badge (a single scope or a stacked badge row) then a flat, sorted dep list. */
    private static void renderFlatSection(
            String headerBadge,
            boolean last,
            Map<String, FlatDep> collected,
            String prefix,
            Styling styling,
            StringBuilder out) {

        out.append(prefix)
                .append(styling.rail().apply(last ? "╰─" : "├─"))
                .append(headerBadge)
                .append('\n');
        String scopePrefix = prefix + styling.rail().apply(last ? "   " : "│  ");
        List<FlatDep> deps = new ArrayList<>(collected.values());
        for (int i = 0; i < deps.size(); i++) {
            FlatDep d = deps.get(i);
            out.append(scopePrefix)
                    .append(styling.rail().apply(i == deps.size() - 1 ? "╰─ " : "├─ "))
                    .append(coordVersioned(d.module(), d.version(), styling))
                    .append(styling.rail().apply(d.tag()))
                    .append('\n');
        }
    }

    /**
     * Walk a dependency and its transitive closure, accumulating distinct coords into {@code out}.
     */
    private static void collectFlat(
            String module,
            Map<String, Dependency> composite,
            Map<String, Lockfile.Artifact> byModule,
            Map<String, String> byName,
            Set<String> visited,
            Map<String, FlatDep> out) {
        collectFlat(module, composite, byModule, byName, visited, out, null, false);
    }

    private static void collectFlat(
            String module,
            Map<String, Dependency> composite,
            Map<String, Lockfile.Artifact> byModule,
            Map<String, String> byName,
            Set<String> visited,
            Map<String, FlatDep> out,
            String declaredVersion,
            boolean platformPin) {

        if (Dependency.isWorkspaceRef(module)) {
            String name = Dependency.workspaceName(module);
            putFlat(out, new FlatDep(byName.getOrDefault(name, module), null, " [workspace]"));
            return;
        }
        if (!visited.add(module)) return;
        Lockfile.Artifact pkg = byModule.get(module);
        if (pkg == null) {
            if (platformPin) {
                putFlat(out, new FlatDep(module, declaredVersion, " (platform)"));
            } else {
                putFlat(out, new FlatDep(module, null, MISSING_SUFFIX));
            }
            return;
        }
        putFlat(out, new FlatDep(module, pkg.version(), ""));
        for (String child : pkg.deps()) {
            collectFlat(stripVersion(child), composite, byModule, byName, visited, out, null, false);
        }
    }

    /** Dedup by {@code group:artifact}, preferring an entry that carries a resolved version. */
    private static void putFlat(Map<String, FlatDep> out, FlatDep dep) {
        FlatDep existing = out.get(dep.module());
        if (existing == null || (existing.version() == null && dep.version() != null)) {
            out.put(dep.module(), dep);
        }
    }

    /** A project's direct dep modules in one scope, distinct + sorted. */
    private static List<String> directModules(JkBuild project, Scope scope) {
        return project.dependencies().of(scope).stream()
                .map(Dependency::module)
                .distinct()
                .sorted()
                .toList();
    }

    /** {@code group:artifact:version} when a version is known, else {@code group:artifact}. */
    private static String coordVersioned(String module, String version, Styling styling) {
        String groupId;
        String artifactId;
        if (cc.jumpkick.model.PackageId.isMavenPackageKey(module)) {
            try {
                var id = cc.jumpkick.model.PackageId.parse(module);
                groupId = id.group();
                artifactId = id.artifact();
            } catch (RuntimeException e) {
                int colon = module.indexOf(':');
                groupId = colon > 0 ? module.substring(0, colon) : module;
                artifactId = colon > 0 ? module.substring(colon + 1) : "";
            }
        } else {
            int colon = module.indexOf(':');
            groupId = colon > 0 ? module.substring(0, colon) : module;
            artifactId = colon > 0 ? module.substring(colon + 1) : "";
        }
        return version == null
                ? styling.group().apply(groupId) + ":" + styling.artifact().apply(artifactId)
                : formatCoord(groupId, artifactId, version, styling);
    }

    /**
     * Render one direct dependency, dispatching on its kind (maven coordinate, or a {@code
     * workspace = true} sibling reference).
     */
    private static void renderDep(
            String module,
            Map<String, Dependency> composite,
            Map<String, Lockfile.Artifact> byModule,
            Path dir,
            int depth,
            int maxDepth,
            boolean isLast,
            String prefix,
            Styling styling,
            Map<String, String> modules,
            Set<String> seenModules,
            Set<String> seenDirs,
            StringBuilder out,
            String declaredVersion,
            boolean platformPin) {

        if (Dependency.isWorkspaceRef(module)) {
            // Workspace sibling (a `<name>.workspace = true` dep) — already shown
            // at the top level, and its external deps aren't in this module's lock.
            // Resolve the synthetic "workspace:<name>" back to a real coord and
            // reference it (no recursion).
            String name = Dependency.workspaceName(module);
            String coord = modules.getOrDefault(name, module);
            out.append(prefix)
                    .append(styling.rail().apply(isLast ? "╰─ " : "├─ "))
                    .append(coordLabel(coord, styling))
                    .append(styling.rail().apply(" [workspace]"))
                    .append('\n');
            return;
        }
        renderNode(
                byModule,
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
     * The bare lowercase scope name for a section badge: {@code MAIN} → {@code "main"}. The {@link
     * Styling#scopeBadge} styler decides padding vs. pill caps.
     */
    private static String scopeLabel(Scope s) {
        return s.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** {@code group:artifact} styled (no version — a workspace sibling reference carries none here). */
    private static String coordLabel(String module, Styling styling) {
        int colon = module.indexOf(':');
        String groupId = colon > 0 ? module.substring(0, colon) : module;
        String artifactId = colon > 0 ? module.substring(colon + 1) : "";
        return styling.group().apply(groupId) + ":" + styling.artifact().apply(artifactId);
    }

    private static void renderNode(
            Map<String, Lockfile.Artifact> byModule,
            String module,
            int depth,
            int maxDepth,
            boolean isLast,
            String prefix,
            Styling styling,
            Set<String> seen,
            StringBuilder out) {
        renderNode(byModule, module, depth, maxDepth, isLast, prefix, styling, seen, out, null, false);
    }

    /**
     * @param declaredVersion version from jk.toml when known (platform BOMs)
     * @param platformPin true when this module is a PLATFORM BOM / pin source — not a lock jar row
     */
    private static void renderNode(
            Map<String, Lockfile.Artifact> byModule,
            String module,
            int depth,
            int maxDepth,
            boolean isLast,
            String prefix,
            Styling styling,
            Set<String> seen,
            StringBuilder out,
            String declaredVersion,
            boolean platformPin) {

        Lockfile.Artifact pkg = byModule.get(module);
        // module may be GA or full package key (g:a:type:classifier); display as GA.
        String groupId;
        String artifactId;
        if (cc.jumpkick.model.PackageId.isMavenPackageKey(module)) {
            try {
                var id = cc.jumpkick.model.PackageId.parse(module);
                groupId = id.group();
                artifactId = id.artifact();
            } catch (RuntimeException e) {
                int colon = module.indexOf(':');
                groupId = colon > 0 ? module.substring(0, colon) : module;
                artifactId = colon > 0 ? module.substring(colon + 1) : "";
            }
        } else {
            int colon = module.indexOf(':');
            groupId = colon > 0 ? module.substring(0, colon) : module;
            artifactId = colon > 0 ? module.substring(colon + 1) : "";
        }

        // Platform BOMs are pin sources (pinned-by on managed jars), not lock [[artifact]] rows.
        // Prefer declared version; never mark them "(missing)" solely because the lock has no BOM jar.
        String displayVersion = pkg != null ? pkg.version() : (platformPin ? declaredVersion : null);
        boolean missing = pkg == null && !platformPin;

        // ╰─ for the last child (rounded arc); ├─ for the rest.
        // Standard "rounded tree" convention used by eza, tre, etc.
        String connector = isLast ? "╰─ " : "├─ ";
        String coord;
        if (displayVersion != null) {
            coord = groupId + ":" + artifactId + ":" + displayVersion;
            if (platformPin && pkg == null) coord = coord + " (platform)";
        } else if (missing) {
            coord = groupId + ":" + artifactId + MISSING_SUFFIX;
        } else {
            coord = groupId + ":" + artifactId + " (platform)";
        }

        if (!seen.add(module)) {
            // Already shown higher up — dim the WHOLE row (connector + coord + ⎋)
            // so it reads as a back-reference, not a fresh expansion.
            out.append(prefix)
                    .append(styling.reference().apply(connector + coord + " ⎋"))
                    .append('\n');
            return;
        }

        String label;
        if (displayVersion != null) {
            label = formatCoord(groupId, artifactId, displayVersion, styling);
            if (platformPin && pkg == null) {
                label = label + styling.rail().apply(" (platform)");
            }
        } else if (missing) {
            // No version available — "group:artifact (missing)", marker unstyled.
            label = styling.group().apply(groupId) + ":" + styling.artifact().apply(artifactId) + MISSING_SUFFIX;
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
        List<String> children =
                pkg.deps().stream().map(DependencyTree::stripVersion).sorted().toList();
        for (int i = 0; i < children.size(); i++) {
            renderNode(
                    byModule,
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

    private static String formatCoord(String group, String artifact, String version, Styling styling) {
        return styling.group().apply(group)
                + ":"
                + styling.artifact().apply(artifact)
                + ":"
                + styling.version().apply(version);
    }

    static Map<String, Lockfile.Artifact> indexByModule(Lockfile lock) {
        Map<String, Lockfile.Artifact> result = new HashMap<>();
        for (Lockfile.Artifact pkg : lock.artifacts()) {
            result.put(pkg.name(), pkg);
            result.put(pkg.packageKey(), pkg);
            // Bare GA lookups (declared roots) resolve to the default-jar row when present.
            if (cc.jumpkick.model.PackageId.isMavenPackageKey(pkg.name())) {
                var id = cc.jumpkick.model.PackageId.parse(pkg.name());
                if (id.isDefaultJar()) {
                    result.put(id.ga(), pkg);
                } else {
                    result.putIfAbsent(id.ga(), pkg);
                }
            }
        }
        return result;
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
    static List<String> collectRoots(JkBuild project, Path projectDir) {
        if (project == null) return List.of();
        if (!project.isWorkspaceRoot() || projectDir == null) {
            return collectRoots(project);
        }
        java.util.LinkedHashSet<String> roots = new java.util.LinkedHashSet<>(collectRoots(project));
        for (LoadedModule m : loadModules(project.workspace().modules(), projectDir)) {
            roots.addAll(collectRoots(m.build()));
        }
        return new ArrayList<>(roots);
    }

    /** Strip the {@code @version} suffix from a lockfile dep ref. */
    static String stripVersion(String depRef) {
        int at = depRef.indexOf('@');
        return at > 0 ? depRef.substring(0, at) : depRef;
    }

    // Sorting helper exposed for tests that want a deterministic order.
    static Comparator<Lockfile.Artifact> byName() {
        return Comparator.comparing(Lockfile.Artifact::name);
    }
}
