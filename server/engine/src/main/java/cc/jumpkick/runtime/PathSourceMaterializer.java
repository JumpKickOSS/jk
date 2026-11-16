// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.WalkSkip;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PathSource;
import cc.jumpkick.model.Scope;
import cc.jumpkick.repo.RepoGroup;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Materializes a local-path dependency into a locally-published Maven artifact — the path-source
 * analogue of {@link GitSourceMaterializer}. It resolves the target directory against the consuming
 * project's directory, builds it via {@link SourceProjectBuilder} (jk / Gradle / Maven, compile or
 * package only, never tests), and writes the jar + POM into a {@code file://} Maven repo so the
 * PubGrub solver treats it as an ordinary coordinate.
 *
 * <p>Unlike a git dep there is no commit SHA to key the cache on, so the repo is keyed by a
 * <b>content fingerprint</b> of the target's source tree (build-output directories excluded): the
 * artifact is rebuilt exactly when the sources change and reused otherwise. The repo lives under
 * {@code $JK_CACHE_DIR/path-artifacts/<pathHash>/<fingerprint>/repo}.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class PathSourceMaterializer {

    /** Outcome: the published coordinate and the {@code file://} repo. */
    record Materialized(
            @Nullable String group,
            @Nullable String artifact,
            @Nullable String version,
            URI repoUrl) {
        String coordinate() {
            return group + ":" + artifact;
        }
    }

    private final Path lockRootDir;
    private final Path artifactsRoot;
    private final Cas cas;
    private final RepoGroup buildRepos;
    private final Path javaHome;
    private final String jkVersion;

    /** Production wiring: caches under {@code $JK_CACHE_DIR}. */
    PathSourceMaterializer(Path lockRootDir, Cas cas, RepoGroup buildRepos, Path javaHome, String jkVersion) {
        this(lockRootDir, CacheTree.PATH_ARTIFACTS.under(JkDirs.cache()), cas, buildRepos, javaHome, jkVersion);
    }

    Materialized materialize(PathSource source) throws IOException, InterruptedException {
        Path projectDir = source.dir(lockRootDir);
        if (!Files.isDirectory(projectDir)) {
            throw new IOException(
                    "path dependency `" + source.rawPath() + "` does not resolve to a directory (" + projectDir + ")");
        }

        String pathHash = shortHash(projectDir.toAbsolutePath().toString());
        String fingerprint = fingerprint(projectDir);
        Path fpDir = artifactsRoot.resolve(pathHash).resolve(fingerprint);
        Path repo = fpDir.resolve("repo");
        Path marker = fpDir.resolve("coordinate.txt");

        boolean isJk = Files.isRegularFile(ManifestPaths.manifestIn(projectDir));

        // Coordinate: read cheaply from a jk target's project identity; a foreign target reveals it only
        // after building (cached in the marker for a fingerprint hit).
        String group = null;
        String artifact = null;
        String version = null;
        if (isJk) {
            JkBuild project = SourceProjectBuilder.declared(projectDir);
            refuseSiblingEdges(source, projectDir, project);
            group = project.project().group();
            artifact = project.project().name();
            version = project.project().version();
        } else if (Files.isRegularFile(marker)) {
            GitSourceMaterializer.Gav cached = GitSourceMaterializer.readCoordinateMarker(marker);
            group = cached.group();
            artifact = cached.artifact();
            version = cached.version();
        }

        if (group != null
                && Files.isRegularFile(artifactPath(repo, group, artifact, version, "jar"))
                && Files.isRegularFile(artifactPath(repo, group, artifact, version, "pom"))) {
            return new Materialized(group, artifact, version, repo.toUri());
        }

        // Path deps never override the target's declared version (there's no ref to derive from).
        SourceProjectBuilder.Built built =
                SourceProjectBuilder.build(projectDir, null, javaHome, cas, buildRepos, jkVersion);
        GitSourceMaterializer.installArtifact(
                repo, built.group(), built.artifact(), built.version(), built.jar(), built.pomXml());
        if (!isJk) {
            GitSourceMaterializer.writeCoordinateMarker(marker, built);
        }
        return new Materialized(built.group(), built.artifact(), built.version(), repo.toUri());
    }

    /**
     * A path dependency builds its target alone; a workspace module whose own edges name siblings
     * would need them built and published first, so it is refused naming them and the fix.
     */
    private static void refuseSiblingEdges(PathSource source, Path projectDir, JkBuild project) throws IOException {
        // The manifest as written: the resolved parse has already turned sibling edges into coordinates.
        JkBuild written = JkBuildParser.parseLocal(ManifestPaths.manifestIn(projectDir));
        Set<String> siblings = new TreeSet<>();
        for (Scope scope : Scope.values()) {
            for (Dependency d : written.dependencies().of(scope)) {
                if (d.isWorkspace()) siblings.add(d.library());
            }
        }
        if (siblings.isEmpty()) return;
        throw new IOException("path dependency `" + source.rawPath() + "` is a workspace module that depends on "
                + String.join(", ", siblings) + " of its own workspace, and a path dependency builds one module"
                + " alone; run `jk install` in that workspace and depend on `"
                + project.project().group() + ":"
                + project.project().name() + ":" + project.project().version() + "` instead");
    }

    private static Path artifactPath(
            Path repo, String group, @Nullable String artifact, @Nullable String version, String ext) {
        return repo.resolve(
                group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-" + version + "." + ext);
    }

    /**
     * A stable content fingerprint of the target's source tree: SHA-256 over each source file's
     * {@code (relative-path, size, content-hash)}, sorted for order-independence, with build-output
     * and tool-metadata directories excluded so a rebuild doesn't invalidate the cache, and a
     * checkout nested below the target ({@link WalkSkip#nestedCheckout}) left out: its files are
     * another branch's, which the target's build does not read.
     */
    private static String fingerprint(Path projectDir) throws IOException {
        List<String> lines = new ArrayList<>();
        // Judged with the directory in hand, not the bare segment: Gradle's build/ is known by the
        // script beside it, and a package named build is a package.
        PathUtil.forEachRegularFile(
                projectDir, dir -> WalkSkip.pathSource(dir) || WalkSkip.nestedCheckout(dir), (p, attrs) -> {
                    if (WalkSkip.pathSource(p)) return;
                    String rel = projectDir.relativize(p).toString().replace('\\', '/');
                    lines.add(rel + "\0" + attrs.size() + "\0" + Hashing.sha256Hex(p));
                });
        lines.sort(null);
        MessageDigest md = Hashing.newSha256();
        for (String line : lines) {
            md.update(line.getBytes(StandardCharsets.UTF_8));
            md.update((byte) '\n');
        }
        return Hashing.hex(md.digest()).substring(0, 24);
    }

    private static String shortHash(String value) {
        return Hashing.sha256Hex(value).substring(0, 16);
    }
}
