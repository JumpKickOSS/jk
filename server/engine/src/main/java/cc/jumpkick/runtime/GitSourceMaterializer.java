// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.forge.ForgeGitCredentials;
import cc.jumpkick.git.GitFetcher;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.GitRefSpec;
import cc.jumpkick.model.GitSource;
import cc.jumpkick.model.GitVersion;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.repo.MavenMetadata;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.util.GitUrl;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Materializes a git dep into a per-commit {@code file://} Maven repo via {@link
 * SourceProjectBuilder} (compile/package only). PubGrub only sees a normal coordinate + repo URL.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public final class GitSourceMaterializer {

    /** Outcome: the published coordinate, the {@code file://} repo, and lock provenance. */
    record Materialized(
            @Nullable String group,
            @Nullable String artifact,
            @Nullable String version,
            URI repoUrl,
            Lockfile.Artifact.GitInfo gitInfo) {
        String coordinate() {
            return group + ":" + artifact;
        }
    }

    private final Path gitRoot;
    private final Path artifactsRoot;
    private final Cas cas;
    private final RepoGroup buildRepos;
    private final Path javaHome;
    private final String jkVersion;
    private final ForgeGitCredentials credentials;

    /** Production wiring: caches under {@code $JK_CACHE_DIR}, forge-auth git credentials. */
    GitSourceMaterializer(Cas cas, RepoGroup buildRepos, Path javaHome, String jkVersion) {
        this(
                JkDirs.store().resolve("git"),
                JkDirs.store().resolve("git-artifacts"),
                cas,
                buildRepos,
                javaHome,
                jkVersion,
                new ForgeGitCredentials());
    }

    /**
     * Fail if {@code source}'s ref no longer resolves to {@code expectedSha} (force-moved tag).
     * Used on {@code jk lock}; {@code jk update} skips it.
     */
    void verifyLocked(GitSource source, String expectedSha) throws IOException {
        new GitFetcher(gitRoot, credentials).verifyLocked(source, expectedSha);
    }

    Materialized materialize(GitSource source) throws IOException, InterruptedException {
        GitFetcher fetcher = new GitFetcher(gitRoot, credentials);
        GitFetcher.Fetched fetched = fetcher.fetch(source);
        String sha = fetched.sha();

        Path projectDir = source.path() != null && !source.path().isBlank()
                ? fetched.checkoutPath().resolve(source.path())
                : fetched.checkoutPath();

        // Per-commit dirs; reused on a cache hit (immutable tag/rev).
        Path shaDir = artifactsRoot
                .resolve(GitUrl.canonicalHash(source.canonicalUrl()))
                .resolve(sha);
        Path repo = shaDir.resolve("repo");
        Lockfile.Artifact.GitInfo gitInfo = new Lockfile.Artifact.GitInfo(
                source.canonicalUrl(), sha, source.ref().token());

        boolean isJk = Files.isRegularFile(projectDir.resolve(ManifestPaths.MANIFEST));

        // Determine the coordinate. For a jk target it's read cheaply from project identity (+ the
        // ref-derived version), so an already-built commit is a cache hit with no build. A foreign
        // (Gradle/Maven) target only reveals its GAV once built — cache it in a coordinate marker.
        String group = null;
        String artifact = null;
        String version = null;
        String versionOverride = null;
        Path marker = shaDir.resolve("coordinate.txt");
        if (isJk) {
            JkBuild project = JkBuildParser.parse(Files.readString(projectDir.resolve(ManifestPaths.MANIFEST)));
            group = project.project().group();
            artifact = project.project().name();
            version = deriveVersion(fetcher, source, sha);
            versionOverride = version; // git deps override the jk.toml version with the ref-derived one
        } else if (Files.isRegularFile(marker)) {
            Gav cached = readCoordinateMarker(marker);
            group = cached.group();
            artifact = cached.artifact();
            version = cached.version();
        }

        // Cache hit: coordinate known and the artifact is already installed.
        if (group != null
                && Files.isRegularFile(artifactJar(repo, group, artifact, version))
                && Files.isRegularFile(artifactPom(repo, group, artifact, version))) {
            return new Materialized(group, artifact, version, repo.toUri(), gitInfo);
        }

        SourceProjectBuilder.Built built =
                SourceProjectBuilder.build(projectDir, versionOverride, javaHome, cas, buildRepos, jkVersion);
        group = built.group();
        artifact = built.artifact();
        version = built.version();
        installArtifact(repo, group, artifact, version, built.jar(), built.pomXml());
        if (!isJk) {
            writeCoordinateMarker(marker, built);
        }
        return new Materialized(group, artifact, version, repo.toUri(), gitInfo);
    }

    private static Path artifactJar(
            Path repo, @Nullable String group, @Nullable String artifact, @Nullable String version) {
        return repo.resolve(artifactRel(group, artifact, version) + ".jar");
    }

    private static Path artifactPom(
            Path repo, @Nullable String group, @Nullable String artifact, @Nullable String version) {
        return repo.resolve(artifactRel(group, artifact, version) + ".pom");
    }

    /** {@code <group as dirs>/<artifact>/<version>/<artifact>-<version>}, extension-less. */
    private static String artifactRel(@Nullable String group, @Nullable String artifact, @Nullable String version) {
        String groupPath = group == null ? "" : group.replace('.', '/');
        return groupPath + "/" + artifact + "/" + version + "/" + artifact + "-" + version;
    }

    /** The coordinate a foreign (Gradle/Maven) target only reveals once it has been built. */
    record Gav(
            String group,
            @Nullable String artifact,
            @Nullable String version) {}

    /**
     * Cache a foreign target's coordinate beside its built artifacts. Both source materializers
     * write and read this file, so the format has one owner: {@code group:artifact:version} and
     * nothing else. {@link SourceProjectBuilder.Built#coordinate()} already carries the version;
     * appending it a second time yielded {@code g:a:v:v}, which no artifact path can match, so
     * every foreign path target rebuilt on every resolve.
     */
    static void writeCoordinateMarker(Path marker, SourceProjectBuilder.Built built) throws IOException {
        Files.writeString(marker, built.coordinate());
    }

    /** Inverse of {@link #writeCoordinateMarker}. */
    static Gav readCoordinateMarker(Path marker) throws IOException {
        String text = Files.readString(marker).strip();
        String[] gav = text.split(":");
        if (gav.length != 3) {
            throw new IOException(marker + ": expected group:artifact:version, got " + text);
        }
        return new Gav(gav[0], gav[1], gav[2]);
    }

    /** Copy the built jar + POM into the {@code file://} repo and (re)write maven-metadata.xml. */
    static void installArtifact(
            Path repo,
            @Nullable String group,
            @Nullable String artifact,
            @Nullable String version,
            Path builtJar,
            String pomXml)
            throws IOException {
        Path jarPath = artifactJar(repo, group, artifact, version);
        Path pomPath = artifactPom(repo, group, artifact, version);
        Files.createDirectories(jarPath.getParent());
        // Streaming copy from the build-output jar — never buffers the whole jar in the heap.
        Files.copy(builtJar, jarPath, StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(pomPath, pomXml);

        // maven-metadata.xml lets the resolver enumerate this artifact's versions through the
        // file:// repo (one version per source dir). MavenMetadata is the one writer: a git tag is
        // free-form text and reaches the version string verbatim (GitVersion.fromTag returns a
        // non-version-like tag unchanged, and keeps a coercible tag's suffix), so a tag carrying
        // `&` or `<` must be escaped here or the resolver cannot parse what we just wrote.
        String groupPath = group == null ? "" : group.replace('.', '/');
        Path metaPath = repo.resolve(groupPath + "/" + artifact + "/maven-metadata.xml");
        Files.createDirectories(metaPath.getParent());
        Files.write(
                metaPath,
                MavenMetadata.empty(group, Objects.requireNonNull(artifact, "artifact"))
                        .withVersion(Objects.requireNonNull(version, "version"))
                        .render());
    }

    private static String deriveVersion(GitFetcher fetcher, GitSource source, String sha) throws IOException {
        return switch (source.ref()) {
            case GitRefSpec.Tag t -> GitVersion.fromTag(t.name());
            case GitRefSpec.Branch b -> GitVersion.forBranch(b.name());
            case GitRefSpec.Rev ignored -> {
                // Explicit commit: tag-anchored timestamp pseudo-version.
                GitFetcher.RefInfo info = fetcher.resolveRef(source);
                yield GitVersion.pseudo(info.nearestTag(), info.commitTime(), shortSha(sha));
            }
        };
    }

    private static String shortSha(String sha) {
        return sha.length() > 12 ? sha.substring(0, 12) : sha;
    }
}
