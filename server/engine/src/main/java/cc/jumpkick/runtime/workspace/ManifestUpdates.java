// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildEditor;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.RequestScope;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.guard.rules.GuardPacks;
import cc.jumpkick.host.Log;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PackageId;
import cc.jumpkick.model.Variants;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.model.Workspace;
import cc.jumpkick.model.WorkspaceMerge;
import cc.jumpkick.node.NodeCatalog;
import cc.jumpkick.node.NodeRelease;
import cc.jumpkick.repo.MavenMetadataCache;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.runtime.LockPlans;
import cc.jumpkick.runtime.RepoGroupBuilder;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.version.Versions;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The manifest phase of {@code jk update}: every exact pin a manifest declares moves to the newest
 * stable release on its Maven major ({@code --major} lifts that gate), in every dependency scope
 * table, in each variant value's dependency tables, in {@code [workspace.dependencies]}, in the
 * language keys and the framework and tool tables ({@link ToolPins}: {@code kotlin}, {@code
 * scala}, {@code [spring-boot] version}, {@code [dokka] version}, {@code [protobuf] version}, a
 * {@code [generate.<name>] tool}, …), in the lock root's {@code [guards] extends} rule packs
 * ({@link GuardPackPins}) and in every workspace member. Ranges, {@code
 * latest}, git, path and platform-managed entries keep their text; the relock that follows floats
 * them. Candidates come from the project's declared repositories, revalidated past the metadata TTL
 * like the relock itself.
 */
public final class ManifestUpdates {

    private ManifestUpdates() {}

    /** The TOML table label reported for a {@code [workspace.dependencies]} rewrite. */
    public static final String WORKSPACE_TABLE = "workspace.dependencies";

    /**
     * Which pins to move: the handles or {@code group:artifact} coordinates in {@code deps} (empty =
     * every declared pin), crossing a Maven major only when {@code major} is set.
     */
    public record Selection(List<String> deps, boolean major) {

        public static final Selection ALL = new Selection(List.of(), false);

        public Selection {
            deps = deps == null ? List.of() : List.copyOf(deps);
        }

        boolean selects(String handle, String module) {
            return deps.isEmpty() || deps.contains(handle) || deps.contains(module);
        }

        /**
         * As {@link #selects(String, String)}, also by the tool pin's table path ({@code
         * protobuf.grpc-java}) or the artifact it pins ({@code micronaut-platform}).
         */
        boolean selects(ToolPins.ToolPin pin) {
            return selects(pin.handle(), pin.module())
                    || deps.contains(pin.table())
                    || deps.contains(pin.module().substring(pin.module().indexOf(':') + 1));
        }

        /** This selection widened by {@code modules}, so every pin of a selected coordinate moves with it. */
        Selection withModules(Set<String> modules) {
            if (deps.isEmpty() || modules.isEmpty()) return this;
            List<String> wider = new ArrayList<>(deps);
            wider.addAll(modules);
            return new Selection(wider, major);
        }
    }

    /**
     * One pin move: {@code handle} in {@code table} of {@code file}, the manifest under {@code dir}
     * or its {@code jk-guards.toml}, goes {@code from} → {@code to}. {@code table} is the scope
     * table's name, {@link #WORKSPACE_TABLE}, a tool pin's {@code <table>.<key>} ({@code
     * dokka.version}, {@code protobuf.grpc-java.plugin}), a root key ({@code kotlin}) or {@link
     * GuardPackPins#TABLE}. {@code moduleLabel} is the workspace member's coordinate, empty for a
     * standalone project.
     */
    public record Rewrite(
            Path file,
            Path dir,
            String moduleLabel,
            String table,
            String handle,
            String module,
            String from,
            String to) {}

    /**
     * The rewrites and the manifest text they produce, keyed by manifest path, and the moves {@code
     * available} past a pin's major that a {@link Selection} without {@code major} does not take:
     * each from the version its rewrite lands on (or the pin as written) to the newest stable on any
     * major. Nothing is on disk until {@link #apply}; until then a resolve reads {@code contents}
     * through {@link cc.jumpkick.config.ManifestOverlay}.
     */
    public record Plan(List<Rewrite> rewrites, Map<Path, String> contents, List<Rewrite> available) {

        public static final Plan EMPTY = new Plan(List.of(), Map.of());

        public Plan {
            rewrites = List.copyOf(rewrites);
            contents = Map.copyOf(contents);
            available = List.copyOf(available);
        }

        public Plan(List<Rewrite> rewrites, Map<Path, String> contents) {
            this(rewrites, contents, List.of());
        }

        public boolean isEmpty() {
            return rewrites.isEmpty();
        }
    }

    /** One dependency table of a manifest: its TOML path as written and the entries it declares. */
    private record DepTable(String path, List<Dependency> deps) {}

    /**
     * Every dependency table {@code build} declares: the scope tables, then each variant value's
     * ({@code variants.<dimension>.<value>.dependencies}).
     */
    private static List<DepTable> depTables(JkBuild build) {
        List<DepTable> out = new ArrayList<>();
        build.dependencies().byScope().forEach((scope, deps) -> out.add(new DepTable(scope.tomlSection(), deps)));
        for (Variants.Dimension dimension : build.variants().dimensions()) {
            dimension.values().forEach((value, overlay) -> overlay.dependencies()
                    .forEach((scope, deps) -> out.add(new DepTable(
                            "variants." + dimension.name() + "." + value + "." + scope.tomlSection(), deps))));
        }
        return out;
    }

    /** Whether {@code dep} carries a version this phase may rewrite. */
    private static boolean pinnable(Dependency dep) {
        return !(dep.isPath() || dep.isWorkspace() || dep.isFile() || dep.isGit() || dep.isPlatformManaged())
                && dep.version() instanceof VersionSelector.Exact;
    }

    /**
     * Where one pin goes: {@code to} the version written ({@code null} leaves the pin), {@code
     * available} a newer release past its major the run does not take ({@code null} when there is
     * none).
     */
    record Step(@Nullable String to, @Nullable String available) {

        static final Step NONE = new Step(null, null);
    }

    /**
     * Where one exact pin of the manifest under {@code dir} goes; {@code available} lists what the
     * repositories publish for it, read only when asked.
     */
    @FunctionalInterface
    private interface Move {
        Step to(Path dir, String module, String current, Supplier<List<String>> available);
    }

    /** One pin's text edit: {@code text} with the pin written as {@code version}. */
    @FunctionalInterface
    private interface Edit {
        String apply(String text, String version);
    }

    /** The {@link Rewrite} of one pin moving {@code from} → {@code to}. */
    @FunctionalInterface
    private interface Pin {
        Rewrite at(String from, String to);
    }

    /** The moves one planning pass takes, and those past a major it leaves available. */
    private record Moves(List<Rewrite> taken, List<Rewrite> available) {}

    /**
     * {@code text} with {@code step} taken through {@code edit}: the move to {@code step.to()} is
     * recorded as taken, the release past the major as available from the version the pin lands on.
     * Throws as {@code edit} does when the pin is not written where it was read, recording nothing.
     */
    private static String take(String text, String current, Step step, Edit edit, Pin pin, Moves moves) {
        String to = step.to();
        String available = step.available();
        if (to == null && available == null) return text;
        String edited = edit.apply(text, to != null ? to : Objects.requireNonNull(available));
        if (to != null) moves.taken().add(pin.at(current, to));
        if (available != null) moves.available().add(pin.at(to != null ? to : current, available));
        return to != null ? edited : text;
    }

    /**
     * Plan the rewrites for the lock scope rooted at {@code lockDir} (a workspace root or a
     * standalone project). Throws like {@link JkBuildParser#parse} when a manifest does not parse.
     */
    public static Plan plan(Path lockDir, @Nullable URI repoUrl, Selection selection) throws IOException {
        return plan(
                lockDir,
                repoUrl,
                selection,
                (dir, module, current, available) -> step(current, available.get(), selection),
                new HashMap<>());
    }

    /**
     * The pins a floors solve raised: each exact dependency pin in the lock scope at {@code
     * lockDir} that {@code probe}, a lock resolved with pins read as floors, holds at a higher
     * version moves to it. A workspace member's pin is read against the member's rows.
     */
    public static Plan raise(Path lockDir, Lockfile probe) throws IOException {
        return plan(
                lockDir,
                null,
                Selection.ALL,
                (dir, module, current, available) -> {
                    String locked = highest(lockDir, dir, probe, module);
                    return locked != null && Versions.compare(locked, current) > 0 ? new Step(locked, null) : Step.NONE;
                },
                null);
    }

    /**
     * The highest version {@code probe} holds for {@code module} as the manifest under {@code dir}
     * reads it: a member its own view, the root the workspace's plain rows.
     */
    private static @Nullable String highest(Path lockDir, Path dir, Lockfile probe, String module) {
        Path root = lockDir.toAbsolutePath().normalize();
        Path here = dir.toAbsolutePath().normalize();
        boolean atRoot = here.equals(root);
        Lockfile view = atRoot
                ? probe
                : probe.forMember(root.relativize(here).toString().replace('\\', '/'));
        String best = null;
        for (Lockfile.Artifact a : view.artifacts()) {
            if ((atRoot && a.isPartition()) || !PackageId.isMavenPackageKey(a.name())) continue;
            if (!PackageId.parse(a.name()).ga().equals(module)) continue;
            if (best == null || Versions.compare(a.version(), best) > 0) best = a.version();
        }
        return best;
    }

    /**
     * @param toolVersions the version-list memo the tool pins read, shared with {@code move}'s so a
     *     framework table and a dependency pin of one coordinate land on the same release; {@code
     *     null} leaves tool pins alone
     */
    private static Plan plan(
            Path lockDir,
            @Nullable URI repoUrl,
            Selection selection,
            Move move,
            @Nullable Map<String, List<String>> toolVersions)
            throws IOException {
        boolean toolPins = toolVersions != null;
        Map<String, List<String>> versions = toolVersions != null ? toolVersions : new HashMap<>();
        JkBuild root = JkBuildParser.parse(ManifestPaths.manifestIn(lockDir));
        JkBuild effectiveRoot = LockPlans.applyWorkspaceContextIfModule(lockDir, root);

        LinkedHashMap<Path, JkBuild> declared = new LinkedHashMap<>();
        LinkedHashMap<Path, JkBuild> effective = new LinkedHashMap<>();
        declared.put(lockDir, root);
        effective.put(lockDir, effectiveRoot);
        if (effectiveRoot.isWorkspaceRoot()) {
            Map<Path, JkBuild> modules = WorkspaceLoader.loadModules(lockDir, effectiveRoot);
            for (Map.Entry<Path, JkBuild> e : modules.entrySet()) {
                declared.put(e.getKey(), e.getValue());
                effective.put(e.getKey(), WorkspaceMerge.applyToModule(effectiveRoot, e.getValue(), modules.values()));
            }
        }
        boolean workspace = declared.size() > 1;
        Map<Path, String> texts = new LinkedHashMap<>();
        for (Path dir : declared.keySet()) {
            texts.put(dir, Files.readString(ManifestPaths.manifestIn(dir), StandardCharsets.UTF_8));
        }
        selection = selection.withModules(selectedModules(declared, texts, selection, toolPins));

        Moves moves = new Moves(new ArrayList<>(), new ArrayList<>());
        Map<Path, String> contents = new LinkedHashMap<>();
        AtomicReference<@Nullable List<NodeRelease>> nodeReleases = new AtomicReference<>();
        Supplier<List<NodeRelease>> releases =
                () -> nodeReleases.updateAndGet(r -> r != null ? r : NodeUpdates.releases(new NodeCatalog()));
        for (Map.Entry<Path, JkBuild> scope : declared.entrySet()) {
            Path dir = scope.getKey();
            Path manifest = ManifestPaths.manifestIn(dir);
            JkBuild build = scope.getValue();
            String moduleLabel = workspace ? LockPlans.coordLabel(build, dir) : "";
            RepoGroup repos =
                    RepoGroupBuilder.buildFor(Objects.requireNonNull(effective.get(dir)), repoUrl, JkStores.storeCas());
            String text = Objects.requireNonNull(texts.get(dir));
            String before = text;

            for (DepTable table : depTables(build)) {
                for (Dependency dep : table.deps()) {
                    if (!pinnable(dep) || !selection.selects(dep.library(), dep.module())) continue;
                    String current = ((VersionSelector.Exact) dep.version()).version();
                    Step step = move.to(dir, dep.module(), current, () -> available(dep.module(), repos, versions));
                    try {
                        text = take(
                                text,
                                current,
                                step,
                                (t, v) -> JkBuildEditor.setDependencyVersion(t, table.path(), dep.library(), v),
                                (from, to) -> new Rewrite(
                                        manifest,
                                        dir,
                                        moduleLabel,
                                        table.path(),
                                        dep.library(),
                                        dep.module(),
                                        from,
                                        to),
                                moves);
                    } catch (IllegalStateException inherited) {
                        // The entry is not written in this table (a `workspace = true` entry, a
                        // plugin-injected platform, a variant overlay folded at parse time): its owner
                        // rewrites it.
                        Log.debug("update: " + table.path() + "." + dep.library() + " not rewritten", inherited);
                    }
                }
            }
            for (Map.Entry<String, Workspace.WorkspaceDependency> e :
                    build.workspaceDependencies().entrySet()) {
                Workspace.WorkspaceDependency wd = e.getValue();
                if (!(wd.version() instanceof VersionSelector.Exact exact)) continue;
                if (!selection.selects(e.getKey(), wd.module())) continue;
                String handle = e.getKey();
                Step step = move.to(dir, wd.module(), exact.version(), () -> available(wd.module(), repos, versions));
                text = take(
                        text,
                        exact.version(),
                        step,
                        (t, v) -> JkBuildEditor.setWorkspaceDependencyVersion(t, handle, v),
                        (from, to) ->
                                new Rewrite(manifest, dir, moduleLabel, WORKSPACE_TABLE, handle, wd.module(), from, to),
                        moves);
            }
            if (toolPins) {
                text = moveToolPins(dir, moduleLabel, build, text, repos, selection, move, versions, moves);
            }
            if (toolPins && selection.major() && selection.selects(NodeUpdates.HANDLE, NodeUpdates.HANDLE)) {
                NodeUpdates.Move node = NodeUpdates.major(text, releases);
                if (node != null) {
                    Rewrite nodeMove = new Rewrite(
                            manifest,
                            dir,
                            moduleLabel,
                            node.table(),
                            NodeUpdates.HANDLE,
                            NodeUpdates.MODULE,
                            node.from(),
                            node.to());
                    text = node.text();
                    moves.taken().add(nodeMove);
                }
            }
            if (!text.equals(before)) contents.put(manifest, text);
        }
        if (toolPins) {
            movePacks(lockDir, effective.get(lockDir), repoUrl, selection, move, versions, moves, contents);
        }
        return new Plan(moves.taken(), contents, moves.available());
    }

    /**
     * Move every selected rule pack the lock root's {@code [guards] extends} pins (a pack is
     * selected by its artifact or coordinate) to its newest stable, writing the rules file.
     */
    private static void movePacks(
            Path lockDir,
            @Nullable JkBuild effectiveRoot,
            @Nullable URI repoUrl,
            Selection selection,
            Move move,
            Map<String, List<String>> versionsByModule,
            Moves moves,
            Map<Path, String> contents)
            throws IOException {
        List<GuardPacks.Coordinate> packs = GuardPackPins.of(lockDir);
        if (packs.isEmpty() || effectiveRoot == null) return;
        Path file = GuardPackPins.file(lockDir);
        String text = Files.readString(file, StandardCharsets.UTF_8);
        String before = text;
        Cas cas = JkStores.storeCas();
        RepoGroup repos = RepoGroupBuilder.buildFor(effectiveRoot, repoUrl, cas);
        for (GuardPacks.Coordinate pack : packs) {
            if (!selection.selects(pack.artifact(), pack.ga())) continue;
            Step step = move.to(
                    lockDir,
                    pack.ga(),
                    pack.version(),
                    () -> GuardPackPins.available(pack, cas.root(), repos, versionsByModule));
            try {
                text = take(
                        text,
                        pack.version(),
                        step,
                        (t, v) -> GuardPackPins.rewrite(t, pack, v),
                        (from, to) -> new Rewrite(
                                file, lockDir, "", GuardPackPins.TABLE, pack.artifact(), pack.ga(), from, to),
                        moves);
            } catch (IllegalStateException unwritable) {
                Log.debug("update: " + pack.gav() + " not rewritten", unwritable);
            }
        }
        if (!text.equals(before)) contents.put(file, text);
    }

    /**
     * The coordinates a named selection reaches anywhere in the lock scope: a handle picks its
     * module, so a member's {@code [spring-boot] version} and the root's {@code
     * spring-boot-dependencies} pin move together whichever of the two was named. Empty when
     * nothing was named, which already selects every pin.
     */
    private static Set<String> selectedModules(
            Map<Path, JkBuild> declared, Map<Path, String> texts, Selection selection, boolean toolPins) {
        Set<String> modules = new LinkedHashSet<>();
        if (selection.deps().isEmpty()) return modules;
        for (Map.Entry<Path, JkBuild> e : declared.entrySet()) {
            JkBuild build = e.getValue();
            for (DepTable table : depTables(build)) {
                for (Dependency dep : table.deps()) {
                    if (pinnable(dep) && selection.selects(dep.library(), dep.module())) modules.add(dep.module());
                }
            }
            build.workspaceDependencies().forEach((handle, wd) -> {
                if (selection.selects(handle, wd.module())) modules.add(wd.module());
            });
            if (!toolPins) continue;
            for (ToolPins.ToolPin pin : ToolPins.of(build, Objects.requireNonNull(texts.get(e.getKey())))) {
                if (selection.selects(pin)) modules.add(pin.module());
            }
        }
        return modules;
    }

    /**
     * {@code text} with every selected tool pin of {@code build} moved where {@code move} says, each
     * move recorded in {@code moves}; a pin whose key the editor cannot find is left as written.
     * A key that fills several coordinates ({@code [intellij] version} names the IDE and its test
     * framework) moves only to a version every one of them publishes.
     */
    private static String moveToolPins(
            Path dir,
            String moduleLabel,
            JkBuild build,
            String text,
            RepoGroup repos,
            Selection selection,
            Move move,
            Map<String, List<String>> versionsByModule,
            Moves moves) {
        Map<String, List<ToolPins.ToolPin>> byKey = new LinkedHashMap<>();
        for (ToolPins.ToolPin pin : ToolPins.of(build, text)) {
            byKey.computeIfAbsent(pin.path(), k -> new ArrayList<>()).add(pin);
        }
        for (List<ToolPins.ToolPin> pins : byKey.values()) {
            if (pins.stream().noneMatch(selection::selects)) continue;
            ToolPins.ToolPin pin = pins.getFirst();
            Step step = move.to(dir, pin.module(), pin.version(), () -> published(pins, repos, versionsByModule));
            try {
                text = take(
                        text,
                        pin.version(),
                        step,
                        pin::rewrite,
                        (from, to) -> new Rewrite(
                                ManifestPaths.manifestIn(dir),
                                dir,
                                moduleLabel,
                                pin.path(),
                                pin.handle(),
                                pin.module(),
                                from,
                                to),
                        moves);
            } catch (IllegalStateException unwritable) {
                Log.debug("update: " + pin.path() + " not rewritten", unwritable);
            }
        }
        return text;
    }

    /**
     * The versions every coordinate one table key fills publishes ({@code [intellij] version} names
     * the IDE and its test framework), so the key moves only to a release all of them carry.
     */
    static List<String> published(
            List<ToolPins.ToolPin> pinsOfOneKey, RepoGroup repos, Map<String, List<String>> versionsByModule) {
        return publishedByAll(pinsOfOneKey.stream()
                .map(p -> available(p.module(), repos, versionsByModule))
                .toList());
    }

    /** Write every manifest the plan changed. A build parsing concurrently never sees a torn file. */
    public static void apply(Plan plan) throws IOException {
        for (Map.Entry<Path, String> e : plan.contents().entrySet()) {
            AtomicWrites.replace(e.getKey(), e.getValue());
        }
        // The request's inputs changed under it: drop the manifest facts memoised from the old text.
        if (!plan.contents().isEmpty()) RequestScope.release();
    }

    /**
     * The newest stable version in {@code available} above {@code current}, on the same Maven major
     * unless {@code major} is set; {@code null} when the pin already is the newest such release.
     */
    static @Nullable String newer(String current, List<String> available, boolean major) {
        long line = major(current);
        return available.stream()
                .filter(Versions::isStable)
                .filter(v -> major || major(v) == line)
                .filter(v -> Versions.compare(v, current) > 0)
                .max(Versions::compare)
                .orElse(null);
    }

    /**
     * Where {@code selection} moves a pin at {@code current}: to the newest release {@link
     * #newer(String, List, boolean)} finds; without {@code major}, the newest stable past the pin's
     * major, when there is one, is left available.
     */
    static Step step(String current, List<String> available, Selection selection) {
        String newest = newer(current, available, selection.major());
        if (selection.major()) return new Step(newest, null);
        String beyond = newer(current, available, true);
        return new Step(newest, beyond != null && major(beyond) != major(current) ? beyond : null);
    }

    /** The versions every list in {@code published} carries, in the first list's order. */
    static List<String> publishedByAll(List<List<String>> published) {
        return published.getFirst().stream()
                .filter(v -> published.stream().allMatch(l -> l.contains(v)))
                .toList();
    }

    /** The Maven major: the first numeric segment ({@code 33.4.8-jre} → 33), or {@code -1} when there is none. */
    static long major(String version) {
        String core = Versions.numericCore(version);
        if (core.isEmpty()) return -1;
        int dot = core.indexOf('.');
        try {
            return Long.parseLong(dot < 0 ? core : core.substring(0, dot));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Every version the repositories publish for {@code module}, revalidated past the metadata TTL;
     * empty when the repositories cannot be reached (the relock reports that).
     */
    static List<String> available(String module, RepoGroup repos, Map<String, List<String>> memo) {
        return memo.computeIfAbsent(module, m -> {
            try {
                return MavenMetadataCache.withForceRevalidate(
                        () -> repos.availableVersions(Coordinate.ofModule(m, "any")));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            } catch (Exception e) {
                Log.debug("update: no version list for " + m, e);
                return List.of();
            }
        });
    }
}
