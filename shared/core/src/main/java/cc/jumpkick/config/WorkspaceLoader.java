// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.StatIdentity;
import cc.jumpkick.layout.NodeShape;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Profile;
import cc.jumpkick.model.Profiles;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.WorkspaceMerge;
import cc.jumpkick.run.JkThreads;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Loads each {@code workspace.modules} entry's {@code jk.toml} (globs expanded by
 * {@link WorkspaceModules}). Missing
 * modules raise {@link JkBuildParseException}. Resolves Cargo-style {@code <field>.workspace
 * = true} against the workspace root before returning.
 */
public final class WorkspaceLoader {

    private WorkspaceLoader() {}

    /** How often the module list was built rather than answered from a memo. */
    private static final LongAdder LOADS = new LongAdder();

    /**
     * Test seam: module lists built since process start. The ratio of this to the calls made is the
     * property under test — a memo that rebuilt the list on every call would pass every correctness
     * test there is.
     */
    public static long loads() {
        return LOADS.sum();
    }

    /**
     * The workspace's member manifests, parsed, by module directory.
     *
     * @throws UserConfigParseException when {@code ~/.jk/config.toml} does not parse: every member is a
     *     full parse
     */
    public static Map<Path, JkBuild> loadModules(Path workspaceRoot, JkBuild root) throws IOException {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        Objects.requireNonNull(root, "root");
        if (!root.isWorkspaceRoot()) return Map.of();
        if (root.project().inheritsFromWorkspace()) {
            throw new JkBuildParseException("workspace root cannot use `workspace = true`; set the values");
        }
        MemoKey key = MemoKey.of(workspaceRoot, root);
        if (key == null) return Collections.unmodifiableMap(load(workspaceRoot, root));
        try {
            return RequestScope.current().get(key, k -> {
                try {
                    return Collections.unmodifiableMap(load(workspaceRoot, root));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /**
     * The request-scoped key of {@code root}'s module list, or {@code null} when it is not
     * remembered: what a fact derived from the members and the root path alone may key on too.
     */
    static @Nullable Object requestKey(Path workspaceRoot, JkBuild root) {
        return MemoKey.of(workspaceRoot, root);
    }

    /**
     * What one module list is a function of: the root directory, the root manifest as it stands
     * (its stat identity — a request's inputs are fixed at launch, so a stamp is enough), and the two
     * parts of the parsed root the load reads, its project and its module entries. Two callers in one
     * request handing in different root objects for the same manifest share a list only when those
     * agree. {@code null} when the root manifest cannot be stat'ed — a root parsed from text in a
     * test, say — in which case the list is built for the caller and not remembered.
     */
    private record MemoKey(Path root, StatIdentity manifest, Project project, List<String> modules) {
        static @Nullable MemoKey of(Path workspaceRoot, JkBuild root) {
            Path dir = workspaceRoot.toAbsolutePath().normalize();
            try {
                return new MemoKey(
                        dir,
                        StatIdentity.of(ManifestPaths.manifestIn(dir)),
                        root.project(),
                        List.copyOf(root.workspaceModules()));
            } catch (IOException | RuntimeException unstattable) {
                return null;
            }
        }
    }

    /**
     * The uncached build of the list: expand the entries, parse each member's manifest, inherit from
     * the root, refuse nested workspaces and identity collisions. {@code loadModules} answers this
     * once per request; the cost is a directory listing per glob segment plus a stat and a parse per
     * member, and a request parsing every member would otherwise pay it once per member.
     */
    private static Map<Path, JkBuild> load(Path workspaceRoot, JkBuild root) throws IOException {
        LOADS.increment();
        Map<Path, JkBuild> modules = new LinkedHashMap<>();
        List<String> bad = new ArrayList<>();
        List<String> names = WorkspaceModules.expand(workspaceRoot, root.workspaceModules());
        // In module order, so the first failure raised and the missing list read as a serial load's.
        for (Member member : members(workspaceRoot, root, names)) {
            if (member.build() == null) bad.add(member.module());
            else modules.put(member.dir(), member.build());
        }
        if (!bad.isEmpty()) {
            throw new JkBuildParseException("workspace modules missing jk.toml: " + bad);
        }
        checkIdentityCollisions(workspaceRoot, root, modules);
        // A member's shared-library edges are coordinates for every reader, as its own parse sees
        // them: a consumer's classpath inherits them through the sibling closure.
        Collection<JkBuild> members = List.copyOf(modules.values());
        modules.replaceAll((dir, member) -> WorkspaceMerge.resolveSharedDependencies(root, member, members));
        return modules;
    }

    /** One member as loaded: its manifest with the root applied, or null when it has no jk.toml. */
    private record Member(String module, Path dir, @Nullable JkBuild build) {}

    /** Below this many members a parse on the caller is cheaper than a hop to the pool. */
    private static final int PARALLEL_FROM = 16;

    /**
     * Every member of {@code names}, in order. Each parse is independent of the others, and a
     * fresh engine's first request parses all of them — 223 manifests one after another were 0.8 s
     * of a Helidon build's first second — so a wide workspace parses them on the CPU pool. The first
     * failure in module order is the one raised, as the loop it replaces raised it.
     */
    private static List<Member> members(Path workspaceRoot, JkBuild root, List<String> names) throws IOException {
        if (names.size() < PARALLEL_FROM) {
            List<Member> out = new ArrayList<>(names.size());
            for (String module : names) out.add(member(workspaceRoot, root, module));
            return out;
        }
        List<CompletableFuture<Member>> pending = new ArrayList<>(names.size());
        for (String module : names) {
            pending.add(CompletableFuture.supplyAsync(
                    () -> {
                        try {
                            return member(workspaceRoot, root, module);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    },
                    JkThreads.cpu()));
        }
        List<Member> out = new ArrayList<>(names.size());
        for (CompletableFuture<Member> f : pending) {
            try {
                out.add(f.join());
            } catch (CompletionException e) {
                for (CompletableFuture<Member> rest : pending) rest.cancel(false);
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (cause instanceof UncheckedIOException io) throw io.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                throw new IllegalStateException(cause);
            }
        }
        return out;
    }

    private static Member member(Path workspaceRoot, JkBuild root, String module) throws IOException {
        Path moduleDir = workspaceRoot.resolve(module).normalize();
        Path moduleJkToml = ManifestPaths.manifestIn(moduleDir);
        if (!Files.exists(moduleJkToml)) return new Member(module, moduleDir, null);
        // parseLocal: avoid WorkspaceResolve recursion (loadModules is called from applyWorkspace).
        JkBuild moduleBuild = JkBuildParser.parseLocal(moduleJkToml);
        // Nested workspaces are forbidden (ambiguous shared target/).
        if (moduleBuild.isWorkspaceRoot()) {
            throw new JkBuildParseException(
                    "nested workspace: module `" + module + "` has its own [workspace]; remove it");
        }
        JkBuild member = inheritImage(inheritPublish(inheritFromRoot(moduleBuild, root), root), root, moduleDir);
        NodeShape.check(member, moduleDir);
        PluginJavaFloor.check(member, moduleDir);
        return new Member(module, moduleDir, member);
    }

    /**
     * Apply every pending {@code *.workspace = true} field from the workspace root.
     * Unchanged when the module has no inheritance flags.
     */
    public static JkBuild inheritFromRoot(JkBuild module, JkBuild root) {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(root, "root");
        if (!module.project().inheritsFromWorkspace()) return module;
        try {
            return module.withProject(module.project().resolveFromWorkspaceRoot(root.project()));
        } catch (IllegalArgumentException e) {
            throw new JkBuildParseException("module `" + module.project().name() + "`: " + e.getMessage(), e);
        }
    }

    /**
     * {@code module} carrying the workspace root's {@code [publish]} table when it declares none
     * of its own. The metadata is a workspace fact — one home page, one license, one team — so
     * the root answers for it; a member's own table wins wholesale.
     */
    public static JkBuild inheritPublish(JkBuild module, JkBuild root) {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(root, "root");
        if (module.publish() != null || root.publish() == null) return module;
        return module.withPublish(root.publish());
    }

    /**
     * {@code module} carrying the workspace root's {@code [image]} registry facts under its own
     * table — {@link ManifestImage#inheritFromRoot}. One registry, one base image, one set of
     * labels is a workspace fact; the image's name, entry point, ports and Dockerfile are not.
     */
    public static JkBuild inheritImage(JkBuild module, JkBuild root, Path moduleDir) {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(root, "root");
        boolean node = NodeShape.kind(module, moduleDir) == NodeShape.Kind.MODULE;
        return module.withImage(ManifestImage.inheritFromRoot(module.image(), root.image(), node));
    }

    /**
     * {@code module} carrying the workspace root's profiles: the one place a member's profiles
     * resolve. A profile is a workspace fact, so naming one the root alone declares works in every
     * member, and a profile means the same in every member.
     *
     * <p>Where the member declares a profile of the same name, the two merge key by key, and the
     * root wins unless the key is additive per member:
     *
     * <ul>
     *   <li>{@code include-tags}, {@code exclude-tags}, {@code all-suites}: the root's. They pick
     *       the tier, and a workspace has one tier table ({@link TierTags} reads them off the root).
     *   <li>{@code env}: additive. A member adds the variables only its tests need (its own
     *       database, its own port); a variable both set takes the root's value.
     *   <li>{@code jvm-args}: additive. A member adds flags only its test JVMs need; the root's
     *       follow the member's, so a flag both set (a later {@code -D} or {@code -Xmx} wins)
     *       takes the root's value.
     *   <li>{@code javac}: the root's when the root's profile sets it, else the member's. The
     *       compiler flags a profile names (say {@code -Werror} on CI) are what the profile means
     *       for the whole workspace; flags one module always needs belong in its own {@code [javac]}.
     *   <li>{@code inherits}: the root's when the root's profile sets it, else the member's.
     * </ul>
     *
     * A profile only one side declares is that side's, whole. Not a {@code key.workspace = true}
     * inherit: those are opt-in per field because they say what a module IS, and a profile names an
     * invocation.
     */
    public static JkBuild inheritProfiles(JkBuild module, JkBuild root) {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(root, "root");
        if (root.profiles().byName().isEmpty()) return module;
        Map<String, Profile> merged = new LinkedHashMap<>(root.profiles().byName());
        module.profiles().byName().forEach((name, own) -> merged.merge(name, own, WorkspaceLoader::mergeProfile));
        return module.withProfiles(new Profiles(merged));
    }

    /** {@code [profiles.<name>]} as the root and a member both declare it, by the rules on {@link #inheritProfiles}. */
    static Profile mergeProfile(Profile fromRoot, Profile fromMember) {
        List<String> jvmArgs = new ArrayList<>(fromMember.jvmArgs());
        jvmArgs.addAll(fromRoot.jvmArgs());
        Map<String, String> env = new LinkedHashMap<>(fromMember.env());
        env.putAll(fromRoot.env());
        return new Profile(
                fromRoot.name(),
                fromRoot.inherits() != null ? fromRoot.inherits() : fromMember.inherits(),
                fromRoot.javacArgs().isEmpty() ? fromMember.javacArgs() : fromRoot.javacArgs(),
                jvmArgs,
                fromRoot.includeTags(),
                fromRoot.excludeTags(),
                fromRoot.includeTagsSet(),
                fromRoot.excludeTagsSet(),
                env,
                fromRoot.allSuites());
    }

    /**
     * Two members may share an artifact name: each member's output lives under its own {@code
     * target/<module-rel>/}, so their jars never meet on disk. What a workspace cannot hold twice is a
     * {@code group:name:version} coordinate, since it publishes one artifact per coordinate. A
     * shared name is refused only when some unit's bare {@code workspace = true} edge or {@code
     * [build] order-after} names it, because that edge is spelled by name alone and would otherwise
     * pick one member silently; an edge that also names the {@code group} picks one member and is
     * refused only when no member carries that pair. The root counts as a unit when it declares a
     * name of its own.
     */
    private static void checkIdentityCollisions(Path workspaceRoot, JkBuild root, Map<Path, JkBuild> modules) {
        List<Entry> all = new ArrayList<>(modules.size() + 1);
        if (!root.project().name().isBlank()) all.add(new Entry(null, root));
        for (Map.Entry<Path, JkBuild> e : modules.entrySet()) all.add(new Entry(e.getKey(), e.getValue()));

        Map<String, Entry> byCoord = new LinkedHashMap<>();
        Map<String, List<Entry>> byName = new LinkedHashMap<>();
        for (Entry e : all) {
            String coord = e.build.project().group() + ":" + e.build.project().name() + ":"
                    + e.build.project().version();
            Entry previous = byCoord.putIfAbsent(coord, e);
            if (previous != null) {
                throw new JkBuildParseException("`" + coord + "` is declared by both `"
                        + moduleLabel(workspaceRoot, previous.dir) + "` and `" + moduleLabel(workspaceRoot, e.dir)
                        + "`; change the name or group of one");
            }
            byName.computeIfAbsent(e.build.project().name(), k -> new ArrayList<>())
                    .add(e);
        }
        for (Entry from : all) checkQualifiedEdges(workspaceRoot, from.dir, from.build, byName);
        byName.values().removeIf(entries -> entries.size() < 2);
        if (byName.isEmpty()) return;
        for (Entry from : all) {
            for (String name : bareEdgeNames(from.build)) {
                List<Entry> carriers = byName.get(name);
                if (carriers == null) continue;
                String paths = carriers.stream()
                        .map(c -> "`" + moduleLabel(workspaceRoot, c.dir) + "`")
                        .collect(Collectors.joining(" and "));
                String groups = carriers.stream()
                        .map(c -> "`" + c.build.project().group() + "`")
                        .collect(Collectors.joining(" or "));
                throw new JkBuildParseException("`" + moduleLabel(workspaceRoot, from.dir) + "` depends on `" + name
                        + "`, which matches " + paths + "; add group = " + groups + " to the dependency");
            }
        }
    }

    /**
     * Every group-qualified edge of {@code unit} names a member carrying that {@code group:name}
     * pair; one that names none is refused, listing the groups the name is carried under.
     */
    private static void checkQualifiedEdges(
            Path workspaceRoot, @Nullable Path fromDir, JkBuild unit, Map<String, List<Entry>> byName) {
        for (Scope scope : Scope.values()) {
            for (Dependency d : unit.dependencies().of(scope)) {
                String group = d.workspaceGroup();
                if (group == null) continue;
                String name = Objects.requireNonNull(d.workspaceName());
                List<Entry> carriers = byName.getOrDefault(name, List.of());
                boolean found = carriers.stream().anyMatch(c -> c.group().equals(group));
                if (found) continue;
                String groups =
                        carriers.stream().map(c -> "`" + c.group() + "`").collect(Collectors.joining(", "));
                throw new JkBuildParseException("`" + moduleLabel(workspaceRoot, fromDir) + "` depends on `" + group
                        + ":" + name + "`, but no module has that group"
                        + (groups.isEmpty() ? "" : "; " + name + " has group " + groups));
            }
        }
    }

    /** One unit of the workspace: the root ({@code dir} null) or a member under its directory. */
    private record Entry(@Nullable Path dir, JkBuild build) {
        String group() {
            return build.project().group();
        }
    }

    /**
     * The sibling names a unit's bare {@code workspace = true} edges and {@code [build] order-after}
     * spell; an edge that names the group is resolved by the pair and is not listed.
     */
    private static List<String> bareEdgeNames(JkBuild unit) {
        List<String> names = new ArrayList<>();
        for (Scope scope : Scope.values()) {
            for (Dependency d : unit.dependencies().of(scope)) {
                String name = d.workspaceName();
                if (name != null && d.workspaceGroup() == null) names.add(name);
            }
        }
        names.addAll(unit.build().allOrderAfter());
        return names;
    }

    /**
     * Workspace-relative path with {@code /} separators, or {@code <workspace root>} for the root's
     * own artifact.
     *
     * <p>A module declared with an absolute path (or, on Windows, one on another drive) is labelled
     * with its full path: {@code relativize} throws {@link IllegalArgumentException} when the two
     * paths differ in absoluteness or root, and a collision message must not become a crash.
     */
    private static String moduleLabel(Path workspaceRoot, @Nullable Path moduleDir) {
        if (moduleDir == null) return "<workspace root>";
        Path label = moduleDir.startsWith(workspaceRoot) ? workspaceRoot.relativize(moduleDir) : moduleDir;
        return label.toString().replace('\\', '/');
    }
}
