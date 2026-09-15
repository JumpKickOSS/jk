// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildEditor;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.RequestScope;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.host.Log;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.model.Workspace;
import cc.jumpkick.model.WorkspaceMerge;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The manifest phase of {@code jk update}: every exact pin a manifest declares moves to the newest
 * stable release on its Maven major ({@code --major} lifts that gate), in every dependency scope
 * table, in {@code [workspace.dependencies]}, and in every workspace member. Ranges, {@code
 * latest}, git, path and platform-managed entries keep their text; the relock that follows floats
 * them. Candidates come from the project's declared repositories, revalidated past the metadata
 * TTL like the relock itself.
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
    }

    /**
     * One pin move: {@code handle} in {@code table} of the manifest under {@code dir} goes {@code
     * from} → {@code to}. {@code moduleLabel} is the workspace member's coordinate, empty for a
     * standalone project.
     */
    public record Rewrite(
            Path dir, String moduleLabel, String table, String handle, String module, String from, String to) {

        public Path manifest() {
            return dir.resolve(ManifestPaths.MANIFEST);
        }
    }

    /**
     * The rewrites and the manifest text they produce, keyed by manifest path. Nothing is on disk
     * until {@link #apply}.
     */
    public record Plan(List<Rewrite> rewrites, Map<Path, String> contents) {

        public static final Plan EMPTY = new Plan(List.of(), Map.of());

        public Plan {
            rewrites = List.copyOf(rewrites);
            contents = Map.copyOf(contents);
        }

        public boolean isEmpty() {
            return rewrites.isEmpty();
        }
    }

    /**
     * Plan the rewrites for the lock scope rooted at {@code lockDir} (a workspace root or a
     * standalone project). Throws like {@link JkBuildParser#parse} when a manifest does not parse.
     */
    public static Plan plan(Path lockDir, @Nullable URI repoUrl, Selection selection) throws IOException {
        JkBuild root = JkBuildParser.parse(lockDir.resolve(ManifestPaths.MANIFEST));
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

        List<Rewrite> rewrites = new ArrayList<>();
        Map<Path, String> contents = new LinkedHashMap<>();
        Map<String, List<String>> versionsByModule = new HashMap<>();
        for (Map.Entry<Path, JkBuild> scope : declared.entrySet()) {
            Path dir = scope.getKey();
            Path manifest = dir.resolve(ManifestPaths.MANIFEST);
            JkBuild build = scope.getValue();
            String moduleLabel = workspace ? LockPlans.coordLabel(build, dir) : "";
            RepoGroup repos =
                    RepoGroupBuilder.buildFor(Objects.requireNonNull(effective.get(dir)), repoUrl, JkStores.storeCas());
            String text = Files.readString(manifest, StandardCharsets.UTF_8);
            String before = text;

            for (Map.Entry<Scope, List<Dependency>> entry :
                    build.dependencies().byScope().entrySet()) {
                Scope table = entry.getKey();
                for (Dependency dep : entry.getValue()) {
                    if (dep.isPath() || dep.isWorkspace() || dep.isFile() || dep.isGit() || dep.isPlatformManaged()) {
                        continue;
                    }
                    if (!(dep.version() instanceof VersionSelector.Exact exact)) continue;
                    if (!selection.selects(dep.library(), dep.module())) continue;
                    String to =
                            newer(exact.version(), available(dep.module(), repos, versionsByModule), selection.major());
                    if (to == null) continue;
                    String rewritten;
                    try {
                        rewritten = JkBuildEditor.setDependencyVersion(text, table, dep.library(), to);
                    } catch (IllegalStateException inherited) {
                        // The version lives elsewhere (a `workspace = true` entry); its owner rewrites it.
                        Log.debug("update: " + table.tomlSection() + "." + dep.library() + " not rewritten", inherited);
                        continue;
                    }
                    text = rewritten;
                    rewrites.add(new Rewrite(
                            dir, moduleLabel, table.tomlSection(), dep.library(), dep.module(), exact.version(), to));
                }
            }
            for (Map.Entry<String, Workspace.WorkspaceDependency> e :
                    build.workspaceDependencies().entrySet()) {
                Workspace.WorkspaceDependency wd = e.getValue();
                if (!(wd.version() instanceof VersionSelector.Exact exact)) continue;
                if (!selection.selects(e.getKey(), wd.module())) continue;
                String to = newer(exact.version(), available(wd.module(), repos, versionsByModule), selection.major());
                if (to == null) continue;
                text = JkBuildEditor.setWorkspaceDependencyVersion(text, e.getKey(), to);
                rewrites.add(
                        new Rewrite(dir, moduleLabel, WORKSPACE_TABLE, e.getKey(), wd.module(), exact.version(), to));
            }
            if (!text.equals(before)) contents.put(manifest, text);
        }
        return new Plan(rewrites, contents);
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
    private static List<String> available(String module, RepoGroup repos, Map<String, List<String>> memo) {
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
