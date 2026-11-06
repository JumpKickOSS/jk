// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.UserConfigParseException;
import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.config.WorkspaceModules;
import cc.jumpkick.host.Log;
import cc.jumpkick.library.LibraryCatalog;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.MavenLayout;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolver.PlatformConstraints;
import cc.jumpkick.version.Versions;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The number a writer pins when the user named a coordinate without a version: the newest stable
 * release the repositories advertise. {@code jk add}, {@code jk new} and MCP {@code deps} write
 * this number into {@code jk.toml}; nothing writes {@code latest}.
 */
public final class StableVersions {

    private StableVersions() {}

    /**
     * The selector a manifest edit writes for {@code group:artifact}: any selector but {@code
     * latest} as given; {@code latest} becomes {@code managed} when a BOM or {@code
     * [managed-dependencies]} entry of the manifest's platform table — a workspace root's BOMs
     * count for a member — supplies the coordinate's version, else the newest stable release in
     * the repositories {@code manifest} declares.
     */
    public static String versionToWrite(Path manifest, String group, String artifact, String selector)
            throws IOException {
        if (!(VersionSelector.parse(selector) instanceof VersionSelector.Latest)) return selector;
        JkBuild project = JkBuildParser.parse(manifest);
        Path dir = Objects.requireNonNull(manifest.toAbsolutePath().getParent(), "manifest directory");
        JkBuild effective = LockPlans.applyWorkspaceContextIfModule(dir, project);
        RepoGroup repos = RepoGroupBuilder.buildFor(effective, null, JkStores.storeCas());
        if (platformManages(effective, repos, group + ":" + artifact)) return Dependency.MANAGED_KEYWORD;
        return newest(repos, group, artifact);
    }

    /** True when a BOM or managed entry of {@code project}'s table manages {@code module}. */
    private static boolean platformManages(JkBuild project, RepoGroup repos, String module) {
        if (project.dependencies().of(Scope.PLATFORM).isEmpty()
                && project.dependencies().of(Scope.MANAGED).isEmpty()) {
            return false;
        }
        try {
            return PlatformConstraints.managedVersions(project, repos).containsKey(module);
        } catch (IOException | IllegalStateException e) {
            Log.debug("versionToWrite: the platform table could not be read; the coordinate is pinned", e);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** True when {@code group:artifact} is a module of the workspace {@code dir} belongs to: it is built, not fetched. */
    private static boolean workspaceMember(Path dir, JkBuild project, String group, String artifact) {
        try {
            Path root = project.isWorkspaceRoot()
                    ? dir
                    : WorkspaceLocator.findRoot(dir).orElse(null);
            if (root == null) return false;
            JkBuild rootBuild = root.equals(dir) ? project : JkBuildParser.parse(ManifestPaths.manifestIn(root));
            if (!rootBuild.isWorkspaceRoot()) return false;
            // Module by module: one listed member without a jk.toml yet must not hide the rest.
            for (String rel : WorkspaceModules.expand(root, rootBuild.workspaceModules())) {
                Path toml = ManifestPaths.manifestIn(root.resolve(rel));
                if (!Files.isRegularFile(toml)) continue;
                JkBuild module = JkBuildParser.parse(toml);
                String moduleGroup = module.project().group();
                if (moduleGroup == null || moduleGroup.isBlank())
                    moduleGroup = rootBuild.project().group();
                if (group.equals(moduleGroup)
                        && artifact.equals(module.project().name())) return true;
            }
        } catch (UserConfigParseException unparsed) {
            throw unparsed; // config.toml's own error, with its position — never this site's fallback
        } catch (IOException | RuntimeException e) {
            Log.debug("requireExists: workspace unreadable; checking the repositories", e);
        }
        return false;
    }

    /**
     * Refuses an exact {@code version} of {@code group:artifact} that no repository {@code
     * manifest} declares serves, before it is written: a POM lookup, not a resolve. Any other
     * selector, a snapshot, or a repository that cannot be reached passes; the lock reports those.
     * Offline the check runs only when every repository the coordinate is asked of is {@linkplain
     * MavenRepo#local local}, so it reads the disk and never the network; otherwise it passes too.
     *
     * @throws IOException naming the newest release when the coordinate has others, else the
     *     message of {@link #newest} for a coordinate with no versions at all
     */
    public static void requireExists(Path manifest, String group, String artifact, String selector) throws IOException {
        if (!(VersionSelector.parse(selector) instanceof VersionSelector.Exact exact)) return;
        String version = exact.version();
        if (Dependency.MANAGED_KEYWORD.equals(version)
                || UnresolvedPins.LITERAL.equals(version)
                || version.endsWith("-SNAPSHOT")) {
            return;
        }
        JkBuild project = JkBuildParser.parse(manifest);
        Path dir = Objects.requireNonNull(manifest.toAbsolutePath().getParent(), "manifest directory");
        if (workspaceMember(dir, project, group, artifact)) return;
        JkBuild effective = LockPlans.applyWorkspaceContextIfModule(dir, project);
        RepoGroup repos = RepoGroupBuilder.buildFor(effective, null, JkStores.storeCas());
        if (offline()) {
            List<MavenRepo> asked = repos.repositoriesFor(Coordinate.of(group, artifact, version));
            if (asked.isEmpty() || !asked.stream().allMatch(MavenRepo::local)) return;
        }
        List<String> available;
        try {
            if (repos.tryFetchPom(Coordinate.of(group, artifact, version)).isPresent()) return;
            available = repos.availableVersions(Coordinate.of(group, artifact, "0"));
        } catch (IOException e) {
            Log.debug("requireExists: no repository answered; the lock reports " + group + ":" + artifact, e);
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while looking up " + group + ":" + artifact + ":" + version, e);
        }
        if (available.isEmpty()) throw new IOException(noSuchModule(group, artifact));
        String newest = newestOf(available, true);
        if (newest == null) newest = newestOf(available, false);
        throw new IOException("no " + group + ":" + artifact + ":" + version
                + " in the configured repositories; the newest release is " + newest);
    }

    /**
     * Where {@code group:artifact} is served from — its POM's artifact at {@code version}, or its
     * metadata when {@code version} is null — in the repositories {@code manifest} declares, or,
     * with no manifest, the user's repositories over the public baseline. Either way the group is
     * {@link RepoGroupBuilder}'s, so the user's mirrors, credentials and group bindings apply: a
     * firewalled machine asks its mirror, not Central. Empty when no repository serves it.
     *
     * @throws IOException when a repository could not be reached, naming it
     */
    public static Optional<URI> probe(Path manifest, String group, String artifact, @Nullable String version)
            throws IOException {
        RepoGroup repos;
        if (Files.isRegularFile(manifest)) {
            Path dir = Objects.requireNonNull(manifest.toAbsolutePath().getParent(), "manifest directory");
            JkBuild effective = LockPlans.applyWorkspaceContextIfModule(dir, JkBuildParser.parse(manifest));
            repos = RepoGroupBuilder.buildFor(effective, null, JkStores.storeCas());
        } else {
            repos = RepoGroupBuilder.buildDefault(JkStores.storeCas());
        }
        Coordinate coord = Coordinate.of(group, artifact, version == null ? "0" : version);
        try {
            Optional<RepoGroup.RepoFetched> hit =
                    version == null ? repos.tryFetchMetadata(coord) : repos.tryFetchPom(coord);
            String path = version == null ? MavenLayout.metadataPath(coord) : MavenLayout.artifactPath(coord);
            return hit.map(found -> found.repo().fetchBase().resolve(path));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while looking up " + group + ":" + artifact, e);
        }
    }

    /**
     * Newest stable release of {@code group:artifact} in {@code repos}. Offline only the {@linkplain
     * MavenRepo#local local} repositories the coordinate is asked of answer, from the disk: what the
     * store holds from a remote repository is no account of its current release.
     *
     * @throws IOException when no repository could be reached, none advertises the coordinate, or
     *     every advertised release is a pre-release; offline, when no local repository lists it; a
     *     coordinate with no versions names the near miss the local store or the catalog knows
     *     ({@link NearMisses})
     */
    public static String newest(RepoGroup repos, String group, String artifact) throws IOException {
        String coord = group + ":" + artifact;
        @Nullable List<String> available;
        try {
            available = offline()
                    ? localVersions(repos, group, artifact)
                    : repos.availableVersions(Coordinate.of(group, artifact, "0"));
        } catch (IOException e) {
            throw new IOException(
                    "could not look up the current version of " + coord + " (" + e.getMessage()
                            + "); pass the version you want explicitly as " + coord + ":<version>",
                    e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while looking up the current version of " + coord, e);
        }
        if (available == null) {
            throw new IOException("offline: no file: repository lists " + coord
                    + ", and a remote one is asked nothing; pass the version you want explicitly as " + coord
                    + ":<version>");
        }
        String stable = newestOf(available, true);
        if (stable != null) return stable;
        String any = newestOf(available, false);
        if (any != null) {
            throw new IOException(coord + " has no stable release; the newest is the pre-release " + any
                    + " — pass it explicitly, e.g. " + coord + ":" + any);
        }
        throw new IOException(noSuchModule(group, artifact));
    }

    /**
     * The releases the {@linkplain MavenRepo#local local} repositories {@code group:artifact} is
     * asked of list, read from the disk; null when none of them is local, or none lists the
     * coordinate while a remote one, asked nothing offline, might.
     */
    private static @Nullable List<String> localVersions(RepoGroup repos, String group, String artifact)
            throws IOException, InterruptedException {
        Coordinate coord = Coordinate.of(group, artifact, "0");
        List<MavenRepo> asked = repos.repositoriesFor(coord);
        LinkedHashSet<String> union = new LinkedHashSet<>();
        boolean anyLocal = false;
        for (MavenRepo repo : asked) {
            if (!repo.local() || !repo.servesReleases()) continue;
            anyLocal = true;
            union.addAll(repo.availableVersions(coord));
        }
        if (!anyLocal || (union.isEmpty() && !asked.stream().allMatch(MavenRepo::local))) return null;
        return List.copyOf(union);
    }

    private static boolean offline() {
        return SessionContext.current().config().offlineOr(false);
    }

    /**
     * {@code no g:a in the configured repositories; did you mean g2:a?}, or {@code check the group
     * and artifact} when no near miss is known.
     */
    static String noSuchModule(String group, String artifact) {
        List<String> near = NearMisses.of(group, artifact, JkStores.store(), LibraryCatalog.layered());
        String tail =
                near.isEmpty() ? "check the group and artifact" : "did you mean " + String.join(" or ", near) + "?";
        return "no " + group + ":" + artifact + " in the configured repositories; " + tail;
    }

    private static @Nullable String newestOf(List<String> versions, boolean stableOnly) {
        String best = null;
        for (String v : versions) {
            if (stableOnly && !Versions.isStable(v)) continue;
            if (best == null || Versions.compare(v, best) > 0) best = v;
        }
        return best;
    }
}
