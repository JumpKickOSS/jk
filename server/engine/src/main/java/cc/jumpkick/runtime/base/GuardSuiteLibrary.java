// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.repo.M2Dirs;
import cc.jumpkick.repo.RepoArtifactResolver;
import cc.jumpkick.repo.RepoArtifactStore;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * {@code cc.jumpkick:jk-guards-junit} for a {@code src/guard} suite: provisioned, never declared.
 * The version is the installed jk's, and the jar comes from the same place the worker jars do —
 * {@code repos/jk-local}, then the other stores, then a copy from {@code ~/.m2} staged into
 * {@code repos/jk-local} — so a suite compiles offline. In jk's own tree the library is the
 * workspace module named {@code jk-guards-junit}, and its class directory is the library: the tree
 * builds against itself, not against the last install.
 */
public final class GuardSuiteLibrary {

    public static final String ARTIFACT = "jk-guards-junit";
    public static final String COORDINATE = "cc.jumpkick:" + ARTIFACT;

    /** Where the library was found, and the jar when it is one (a workspace module has no jar to pin). */
    public record Located(Path path, @Nullable Path jar) {}

    private GuardSuiteLibrary() {}

    /** The m2-layout relative path of the jar at the installed jk's version. */
    public static String relativePath() {
        return "cc/jumpkick/" + ARTIFACT + "/" + JkVersion.VERSION + "/" + ARTIFACT + "-" + JkVersion.VERSION + ".jar";
    }

    /** The library for a suite under {@code root}, or a message saying what to do when there is none. */
    public static Located locate(Path root, Cas cas) throws IOException {
        Path own = workspaceModule(root);
        if (own != null) return new Located(own, null);
        Path stored = stored(cas);
        if (stored != null) return new Located(stored, stored);
        Path staged = stageFromM2(cas);
        if (staged != null) return new Located(staged, staged);
        throw new IOException(
                COORDINATE + ":" + JkVersion.VERSION
                        + " is not in the store (repos/jk-local) or ~/.m2, so the guard suite cannot compile."
                        + " Reinstall jk (the installer stages it), or in jk's own tree run ./gradlew :guard-api:installLocal.");
    }

    /** Store-only: the jar in any repo store, or {@code null}. */
    public static @Nullable Path stored(Cas cas) {
        for (String repo :
                List.of(RepoArtifactResolver.JK_LOCAL, RepositorySpec.JUMPKICK_NAME, RepositorySpec.CENTRAL)) {
            Optional<Path> hit = new RepoArtifactStore(cas.root(), repo).locate(relativePath());
            if (hit.isPresent()) return hit.get();
        }
        return null;
    }

    /** Copy the jar from {@code ~/.m2} into {@code repos/jk-local}; {@code null} when it is not there either. */
    public static @Nullable Path stageFromM2(Cas cas) throws IOException {
        Path m2Jar = M2Dirs.localRepository().resolve(relativePath().replace('/', File.separatorChar));
        if (!Files.isRegularFile(m2Jar)) return null;
        RepoArtifactStore local = new RepoArtifactStore(cas.root(), RepoArtifactResolver.JK_LOCAL);
        local.materialize(relativePath(), m2Jar, Hashing.sha256Hex(m2Jar));
        return local.locate(relativePath()).orElse(null);
    }

    /**
     * The workspace module that is the library itself (jk's own tree), as its main class directory;
     * {@code null} elsewhere or when it has not been compiled yet.
     */
    static @Nullable Path workspaceModule(Path root) {
        Path manifest = root.resolve(ManifestPaths.MANIFEST);
        if (!Files.isRegularFile(manifest)) return null;
        JkBuild build;
        try {
            build = JkBuildParser.parse(manifest);
        } catch (IOException | RuntimeException e) {
            return null;
        }
        if (build.workspace() == null) return null;
        for (String m : build.workspace().modules()) {
            Path dir = root.resolve(m);
            Path mm = dir.resolve(ManifestPaths.MANIFEST);
            if (!Files.isRegularFile(mm)) continue;
            try {
                if (!ARTIFACT.equals(JkBuildParser.parseLocal(mm).project().name())) continue;
            } catch (IOException | RuntimeException e) {
                continue;
            }
            Path classes =
                    BuildLayout.moduleTargetDir(root, dir).resolve("classes").resolve("main");
            return Files.isDirectory(classes) ? classes : null;
        }
        return null;
    }
}
