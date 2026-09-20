// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.git.GitFetcher;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.Log;
import cc.jumpkick.library.LibraryCatalog;
import cc.jumpkick.lock.LockNativePin;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.GitRefSpec;
import cc.jumpkick.model.GitSource;
import cc.jumpkick.model.GitVersion;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PackageId;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.model.WorkspaceMerge;
import cc.jumpkick.repo.MavenMetadataCache;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolver.VersionSelectors;
import cc.jumpkick.resolver.pubgrub.VersionSet;
import cc.jumpkick.runtime.LockPlans;
import cc.jumpkick.runtime.RepoGroupBuilder;
import cc.jumpkick.runtime.base.ReachabilityMetadata;
import cc.jumpkick.version.Versions;
import cc.jumpkick.wire.protocol.OutdatedReport;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Read-only {@code jk outdated} report: locked / selector-compatible / latest-stable / tip per
 * direct dep (Maven metadata + git {@code ls-remote}). Cascades over workspace modules; skips
 * path/workspace/file/platform-managed deps.
 */
public final class OutdatedPlans {

    private OutdatedPlans() {}

    /**
     * Where the report's beats go: {@code checked} of {@code total} rows are done and {@code
     * coordinate} is about to be fetched. Called once before the first fetch with {@code checked =
     * 0}. A sink may throw to stop the report (the wire client hung up).
     */
    @FunctionalInterface
    public interface Progress {
        void checking(int checked, int total, String coordinate);

        /** Discard every beat: MCP and tests, where nobody watches. */
        Progress NONE = (checked, total, coordinate) -> {};
    }

    /**
     * Produce the report for the project (or workspace) rooted at {@code dir}. Every catalog is
     * read fresh — past the metadata TTL, the process version-list memo and the not-found memo —
     * because the question is what the repositories publish now, not what the last lock saw.
     */
    public static OutdatedReport compute(Path dir, Path cache, @Nullable URI repoUrl, Progress progress) {
        try {
            return MavenMetadataCache.withForceRevalidate(() -> fresh(dir, repoUrl, progress));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return OutdatedReport.error(Errors.text(e));
        } catch (Exception e) {
            return OutdatedReport.error(Errors.text(e));
        }
    }

    /** One direct dependency waiting for its version picture, with everything its row needs. */
    private record Pending(
            Dependency dep,
            String moduleLabel,
            String display,
            String scope,
            @Nullable String locked,
            RepoGroup repos) {}

    private static OutdatedReport fresh(Path dir, @Nullable URI repoUrl, Progress progress) {
        LinkedHashMap<Path, JkBuild> scopes = new LinkedHashMap<>();
        try {
            JkBuild root = JkBuildParser.parse(ManifestPaths.manifestIn(dir));
            JkBuild effectiveRoot = LockPlans.applyWorkspaceContextIfModule(dir, root);
            scopes.put(dir, effectiveRoot);
            if (effectiveRoot.isWorkspaceRoot()) {
                Map<Path, JkBuild> modules = WorkspaceLoader.loadModules(dir, effectiveRoot);
                for (Map.Entry<Path, JkBuild> e : modules.entrySet()) {
                    scopes.put(e.getKey(), WorkspaceMerge.applyToModule(effectiveRoot, e.getValue(), modules.values()));
                }
            }
        } catch (Exception e) {
            return OutdatedReport.error(Errors.text(e));
        }

        boolean workspace = scopes.size() > 1;
        Map<String, String> shortNames = reverseCatalog(dir);

        // Every row is listed before any repository is read, so the first beat carries the total.
        List<Pending> pending = new ArrayList<>();
        for (Map.Entry<Path, JkBuild> scope : scopes.entrySet()) {
            Path moduleDir = scope.getKey();
            JkBuild build = scope.getValue();
            String moduleLabel = workspace ? LockPlans.coordLabel(build, moduleDir) : "";
            Map<String, String> locked = lockedVersions(LockPaths.lockFile(moduleDir));
            Cas cas = JkStores.storeCas();
            RepoGroup repos = RepoGroupBuilder.buildFor(build, repoUrl, cas);
            Set<String> seen = new LinkedHashSet<>();
            for (Map.Entry<Scope, List<Dependency>> entry :
                    build.dependencies().byScope().entrySet()) {
                String scopeName = entry.getKey().canonical();
                for (Dependency dep : entry.getValue()) {
                    if (dep.isPath() || dep.isWorkspace() || dep.isFile() || dep.isPlatformManaged()) {
                        continue; // no enumerable version frontier for these
                    }
                    if (!seen.add(dep.module())) continue; // one row per coordinate — first scope wins
                    String display = shortNames.getOrDefault(dep.module(), "");
                    pending.add(new Pending(dep, moduleLabel, display, scopeName, locked.get(dep.module()), repos));
                }
            }
        }
        Optional<VersionSelector> nativeDeclared = nativeSelector(dir);
        int total = pending.size() + (nativeDeclared.isPresent() ? 1 : 0);

        GitFetcher git = new GitFetcher(JkStores.resolve("git"));
        Map<String, GitFetcher.RemoteRefs> gitRefsCache = new HashMap<>();
        List<OutdatedReport.Row> rows = new ArrayList<>();
        for (Pending p : pending) {
            progress.checking(rows.size(), total, p.dep().module());
            rows.add(
                    p.dep().isGit()
                            ? gitRow(p.dep(), p.moduleLabel(), p.display(), p.scope(), git, gitRefsCache)
                            : mavenRow(p.dep(), p.moduleLabel(), p.display(), p.scope(), p.locked(), p.repos()));
        }
        if (nativeDeclared.isPresent()) {
            progress.checking(
                    rows.size(), total, ReachabilityMetadata.coordinate("any").module());
            rows.add(nativeMetadataRow(dir, repoUrl, scopes.values().iterator().next(), nativeDeclared.get()));
        }
        return OutdatedReport.of(workspace, rows);
    }

    /** The {@code [native] metadata-repository} selector, when the project declares one. */
    private static Optional<VersionSelector> nativeSelector(Path dir) {
        try {
            return LockNativePin.selector(dir);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * The {@code [native] metadata-repository} pin's row. It is not a dependency, but it is a
     * floating selector the lock pinned, and a pin nobody can see is a pin nobody bumps.
     */
    private static OutdatedReport.Row nativeMetadataRow(
            Path dir, @Nullable URI repoUrl, JkBuild build, VersionSelector declared) {
        Lockfile.NativeMetadata pin = null;
        try {
            Path lockFile = LockPaths.lockFile(dir);
            if (Files.isRegularFile(lockFile))
                pin = LockfileReader.read(lockFile).nativeMetadata();
        } catch (IOException | RuntimeException e) {
            // No lock, or unreadable: Current is simply empty, exactly as for an unlocked dep.
            Log.debug("nativeMetadataRow: No lock, or unreadable", e);
        }
        RepoGroup repos = RepoGroupBuilder.buildFor(build, repoUrl, JkStores.storeCas());
        List<String> available;
        try {
            available = repos.availableVersions(ReachabilityMetadata.coordinate("any"));
        } catch (IOException e) {
            available = List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            available = List.of();
        }
        VersionSet set = VersionSelectors.toVersionSet(declared);
        String compatible = available.stream()
                .filter(set::contains)
                .filter(Versions::isStable)
                .max(Versions::compare)
                .orElse("");
        String latest = available.stream()
                .filter(Versions::isStable)
                .max(Versions::compare)
                .orElse("");
        return new OutdatedReport.Row(
                "",
                ReachabilityMetadata.coordinate("any").module(),
                "reachability metadata",
                "native",
                pin == null ? "" : pin.version(),
                compatible,
                latest,
                "");
    }

    // ---- Maven --------------------------------------------------------------

    private static OutdatedReport.Row mavenRow(
            Dependency dep,
            String moduleLabel,
            String display,
            String scope,
            @Nullable String current,
            RepoGroup repos) {
        final List<String> available = enumerate(dep, repos);
        VersionSet set = VersionSelectors.toVersionSet(dep.version());
        String compatible = available.stream()
                .filter(set::contains)
                .filter(Versions::isStable)
                .max(Versions::compare)
                .or(() -> available.stream().filter(set::contains).max(Versions::compare))
                .orElse("");
        String latest = available.stream()
                .filter(Versions::isStable)
                .max(Versions::compare)
                .orElse("");
        final String stable = latest;
        String tip = available.stream()
                .filter(v -> !Versions.isStable(v))
                .max(Versions::compare)
                .filter(t -> stable.isEmpty() || Versions.compare(t, stable) > 0)
                .orElse("");
        return new OutdatedReport.Row(
                moduleLabel, dep.module(), display, scope, current == null ? "" : current, compatible, latest, tip);
    }

    /** Merged available versions across repos; empty on any I/O error (offline / unreachable). */
    private static List<String> enumerate(Dependency dep, RepoGroup repos) {
        try {
            return repos.availableVersions(Coordinate.ofModule(dep.module(), "any"));
        } catch (IOException e) {
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    // ---- git ----------------------------------------------------------------

    private static OutdatedReport.Row gitRow(
            Dependency dep,
            String moduleLabel,
            String display,
            String scope,
            GitFetcher git,
            Map<String, GitFetcher.RemoteRefs> cache) {
        GitSource src = Objects.requireNonNull(dep.gitSource(), "gitSource");
        String current = gitCurrent(src.ref());
        GitFetcher.RemoteRefs refs = cache.computeIfAbsent(src.canonicalUrl(), u -> listRefsQuiet(git, src));
        String[] latestAndTip = gitLatestAndTip(refs.tags());
        return new OutdatedReport.Row(
                moduleLabel,
                dep.module(),
                display,
                scope,
                current,
                /* compatible = the immutable pin */ current,
                latestAndTip[0],
                latestAndTip[1]);
    }

    /**
     * From a git remote's tag names, pick {@code [latest, tip]}: {@code latest} is the newest
     * version-like <em>stable</em> tag (original name), {@code tip} is the newest non-stable tag
     * ahead of it, or the literal {@code "tip"} for the moving HEAD when no prerelease tag is newer.
     * Non-version-like tags (which {@link GitVersion#fromTag} returns verbatim, not starting with a
     * digit) are ignored. Package-private for tests.
     */
    static String[] gitLatestAndTip(List<String> tags) {
        String latestTag = "";
        String latestVer = null;
        String tipTag = "";
        String tipVer = null;
        for (String tag : tags) {
            String v = GitVersion.fromTag(tag);
            if (v.isEmpty() || !Character.isDigit(v.charAt(0))) continue; // not version-like (raw fallback)
            if (Versions.isStable(v)) {
                if (latestVer == null || Versions.compare(v, latestVer) > 0) {
                    latestVer = v;
                    latestTag = tag;
                }
            } else if (tipVer == null || Versions.compare(v, tipVer) > 0) {
                tipVer = v;
                tipTag = tag;
            }
        }
        String tip =
                (tipVer != null && (latestVer == null || Versions.compare(tipVer, latestVer) > 0)) ? tipTag : "tip";
        return new String[] {latestTag, tip};
    }

    /** Display string for a git dependency's currently-pinned ref. */
    private static @Nullable String gitCurrent(GitRefSpec ref) {
        return switch (ref) {
            case GitRefSpec.Tag t -> t.name();
            case GitRefSpec.Branch b -> "tip"; // tracks the moving branch HEAD
            case GitRefSpec.Rev r -> r.sha().length() > 12 ? r.sha().substring(0, 12) : r.sha();
        };
    }

    private static GitFetcher.RemoteRefs listRefsQuiet(GitFetcher git, GitSource src) {
        try {
            return git.listRefs(src);
        } catch (IOException e) {
            return new GitFetcher.RemoteRefs(List.of(), null); // offline / unreachable → no tags
        }
    }

    // ---- shared -------------------------------------------------------------

    /** Build a {@code group:artifact -> short catalog name} index; shortest name wins per coord. */
    private static Map<String, String> reverseCatalog(Path dir) {
        Map<String, String> reverse = new HashMap<>();
        // Full chain incl. the workspace jk-libs.toml layer — the system-only view hid
        // project-layer names and showed stale system mappings for overridden ones.
        LibraryCatalog catalog = LibraryCatalog.forProject(dir);
        for (String name : catalog.names()) {
            var mod = catalog.lookup(name);
            if (mod.isEmpty()) continue;
            String coord = mod.get().moduleKey();
            String existing = reverse.get(coord);
            if (existing == null
                    || name.length() < existing.length()
                    || (name.length() == existing.length() && name.compareTo(existing) < 0)) {
                reverse.put(coord, name);
            }
        }
        return reverse;
    }

    /**
     * Locked versions keyed by package key and by GA so declared deps ({@code group:artifact}) still
     * match lock rows written as {@code group:artifact:type:classifier}.
     */
    private static Map<String, String> lockedVersions(Path lockFile) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(lockFile)) return out;
        try {
            for (Lockfile.Artifact a : LockfileReader.read(lockFile).artifacts()) {
                out.putIfAbsent(a.name(), a.version());
                out.putIfAbsent(a.packageKey(), a.version());
                try {
                    if (PackageId.isMavenPackageKey(a.name())) {
                        out.putIfAbsent(PackageId.parse(a.name()).ga(), a.version());
                    }
                } catch (RuntimeException e) {
                    // non-Maven lock name
                    Log.debug("lockedVersions: non-Maven lock name", e);
                }
            }
        } catch (Exception e) {
            // unreadable lock — treat as no locked versions
            Log.debug("lockedVersions: unreadable lock", e);
        }
        return out;
    }
}
