// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Profile;
import cc.jumpkick.model.Profiles;
import cc.jumpkick.model.Project;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;
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

    public static Map<Path, JkBuild> loadModules(Path workspaceRoot, JkBuild root) throws IOException {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        Objects.requireNonNull(root, "root");
        if (!root.isWorkspaceRoot()) return Map.of();
        if (root.project().inheritsFromWorkspace()) {
            throw new JkBuildParseException("workspace root must set concrete project values"
                    + " (`*.workspace = true` is only valid on workspace modules)");
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
     * What one module list is a function of: the root directory, the root manifest as it stands
     * (size and mtime — a request's inputs are fixed at launch, so a stamp is enough), and the two
     * parts of the parsed root the load reads, its project and its module entries. Two callers in one
     * request handing in different root objects for the same manifest share a list only when those
     * agree. {@code null} when the root manifest cannot be stat'ed — a root parsed from text in a
     * test, say — in which case the list is built for the caller and not remembered.
     */
    private record MemoKey(Path root, long size, FileTime mtime, Project project, List<String> modules) {
        static @Nullable MemoKey of(Path workspaceRoot, JkBuild root) {
            Path dir = workspaceRoot.toAbsolutePath().normalize();
            try {
                BasicFileAttributes attrs =
                        Files.readAttributes(ManifestPaths.manifestIn(dir), BasicFileAttributes.class);
                return new MemoKey(
                        dir,
                        attrs.size(),
                        attrs.lastModifiedTime(),
                        root.project(),
                        List.copyOf(root.workspaceModules()));
            } catch (IOException | RuntimeException unstattable) {
                return null;
            }
        }
    }

    /**
     * The uncached build of the list: expand the entries, parse each member's manifest, inherit from
     * the root, refuse nested workspaces and artifact collisions. {@code loadModules} answers this
     * once per request; the cost is a directory listing per glob segment plus a stat and a parse per
     * member, and a request parsing every member would otherwise pay it once per member.
     */
    private static Map<Path, JkBuild> load(Path workspaceRoot, JkBuild root) throws IOException {
        LOADS.increment();
        Map<Path, JkBuild> modules = new LinkedHashMap<>();
        List<String> bad = new ArrayList<>();
        for (String module : WorkspaceModules.expand(workspaceRoot, root.workspaceModules())) {
            Path moduleDir = workspaceRoot.resolve(module).normalize();
            Path moduleJkToml = ManifestPaths.manifestIn(moduleDir);
            if (!Files.exists(moduleJkToml)) {
                bad.add(module);
                continue;
            }
            // parseLocal: avoid WorkspaceResolve recursion (loadModules is called from applyWorkspace).
            JkBuild moduleBuild = JkBuildParser.parseLocal(moduleJkToml);
            // Nested workspaces are forbidden (ambiguous shared target/).
            if (moduleBuild.isWorkspaceRoot()) {
                throw new JkBuildParseException("workspaces cannot be nested — `"
                        + module
                        + "` declares its own `[workspace]` block "
                        + "while being a module of `"
                        + workspaceRoot
                        + "`.");
            }
            modules.put(moduleDir, inheritPublish(inheritFromRoot(moduleBuild, root), root));
        }
        if (!bad.isEmpty()) {
            throw new JkBuildParseException("workspace modules missing jk.toml: " + bad);
        }
        checkArtifactCollisions(workspaceRoot, root, modules);
        return modules;
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
     * {@code module} carrying the workspace root's profiles, its own winning by name.
     *
     * <p>A profile is a workspace fact — {@code docs/user/test.md} says the root's
     * {@code [profiles.<name>]} tag lists "are read once and apply to every member" — but the
     * table itself was only ever read off the manifest being built. The tag half already behaved:
     * the CLI rehomes to the root before it scans them. The other half did not, so naming a
     * profile the root alone declares failed the build on the first member that did not:
     * {@code jk test --profile integration} died with "no profile named `integration`" against a
     * root whose {@code jk.toml} defines it, and the documented pre-merge command could not run.
     *
     * <p>Not a {@code key.workspace = true} inherit: those are opt-in per field because they say
     * what a module IS. A profile names an invocation, so the workspace answers for it, and a
     * member that declares its own keeps it — the merge is by name, not wholesale replacement.
     */
    public static JkBuild inheritProfiles(JkBuild module, JkBuild root) {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(root, "root");
        if (root.profiles().byName().isEmpty()) return module;
        Map<String, Profile> merged = new LinkedHashMap<>(root.profiles().byName());
        merged.putAll(module.profiles().byName());
        return module.withProfiles(new Profiles(merged));
    }

    /**
     * Final artifacts land in a shared {@code <workspaceRoot>/target/} directory keyed by {@code
     * <artifact>-<version>.jar} — so two modules declaring the same artifact + version would race to
     * write the same jar and the second one would silently win. Reject at workspace-parse time so the
     * failure is loud and the file paths in the error point at the conflict.
     *
     * <p>Modules and the workspace root itself can collide (a workspace root that's <i>also</i> a
     * runnable project is rare but legal, so we include the root in the uniqueness set).
     */
    private static void checkArtifactCollisions(Path workspaceRoot, JkBuild root, Map<Path, JkBuild> modules) {
        Map<String, Path> claimed = new LinkedHashMap<>();
        record Entry(@Nullable Path dir, JkBuild build) {}
        List<Entry> all = new ArrayList<>(modules.size() + 1);
        // Only include the root if it could plausibly produce its own jar
        // (i.e., it declares a non-blank artifact). Many workspace roots
        // are pure coordinators with no own artifact; skip those.
        if (!root.project().name().isBlank()) {
            all.add(new Entry(null, root));
        }
        for (Map.Entry<Path, JkBuild> e : modules.entrySet()) {
            all.add(new Entry(e.getKey(), e.getValue()));
        }
        for (Entry e : all) {
            String key = e.build.project().name() + "-" + e.build.project().version();
            // containsKey, not the put return value: the workspace root
            // stores `null` as its dir, and Map.put can't distinguish a
            // returned null between "no prior entry" and "prior entry's
            // value was null".
            if (claimed.containsKey(key)) {
                Path previous = claimed.get(key);
                String prevLabel = moduleLabel(workspaceRoot, previous);
                String thisLabel = moduleLabel(workspaceRoot, e.dir);
                throw new JkBuildParseException("workspace artifact collision: `"
                        + key
                        + ".jar` would be "
                        + "produced by both `"
                        + prevLabel
                        + "` and `"
                        + thisLabel
                        + "`. Final artifacts share <workspaceRoot>/target/, so two "
                        + "modules can't emit the same `<artifact>-<version>.jar`. "
                        + "Differentiate via the modules' name or version.");
            }
            claimed.put(key, e.dir);
        }
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
