// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.repo.M2Dirs;
import cc.jumpkick.repo.RepoArtifactResolver;
import cc.jumpkick.repo.RepoArtifactStore;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * {@code cc.jumpkick:jk-guards-junit} for a {@code src/guard} suite: provisioned, never declared.
 * The version is the installed jk's, and the jar comes from the same place the worker jars do —
 * {@code repos/jk-local}, then the other stores, then a copy from {@code ~/.m2} staged into
 * {@code repos/jk-local} — so a suite compiles offline. In jk's own tree the library is the
 * workspace module named {@code jk-guards-junit}, and its class directory is the library: the tree
 * builds against itself, not against the last install, and the lock pins the module rather than a
 * jar digest (see {@link #pin}).
 */
public final class GuardSuiteLibrary {

    public static final String ARTIFACT = "jk-guards-junit";
    public static final String COORDINATE = "cc.jumpkick:" + ARTIFACT;

    /** Where the library was found, and the jar when it is one (a workspace module has no jar to pin). */
    public record Located(Path path, @Nullable Path jar) {}

    /** The workspace module that is the library: its directory and workspace-resolved manifest. */
    public record Module(Path dir, JkBuild manifest) {}

    private GuardSuiteLibrary() {}

    /** The m2-layout relative path of the jar at the installed jk's version. */
    public static String relativePath() {
        return "cc/jumpkick/" + ARTIFACT + "/" + JkVersion.VERSION + "/" + ARTIFACT + "-" + JkVersion.VERSION + ".jar";
    }

    /**
     * The {@code [[plugin]]} row a guard suite under {@code root} pins. A workspace that builds the
     * library itself pins the module by its path: the row is the same whatever jar is installed or
     * staged, so a re-lock on a clean checkout rewrites nothing. Elsewhere the row is the digest of
     * the stored jar the suite compiles against; {@code null} when no jar is stored yet.
     */
    public static Lockfile.@Nullable PluginEntry pin(Path root, Cas cas) throws IOException {
        Module own = workspaceModule(root);
        if (own != null) {
            String rel = root.toAbsolutePath()
                    .normalize()
                    .relativize(own.dir().toAbsolutePath().normalize())
                    .toString()
                    .replace(File.separatorChar, '/');
            return Lockfile.PluginEntry.workspace(
                    COORDINATE, own.manifest().project().version(), rel);
        }
        Path jar = stored(cas);
        if (jar == null) return null;
        return new Lockfile.PluginEntry(COORDINATE, JkVersion.VERSION, "sha256:" + Hashing.sha256Hex(jar));
    }

    /** The library for a suite under {@code root}, or a message saying what to do when there is none. */
    public static Located locate(Path root, Cas cas) throws IOException {
        Path own = workspaceClasses(root);
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
        for (RepoArtifactStore store : RepoArtifactStore.firstParty(cas.root())) {
            Optional<Path> hit = store.locate(relativePath());
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
    static @Nullable Path workspaceClasses(Path root) {
        Module own = workspaceModule(root);
        if (own == null) return null;
        Path classes =
                BuildLayout.moduleTargetDir(root, own.dir()).resolve("classes").resolve("main");
        return Files.isDirectory(classes) ? classes : null;
    }

    /** The workspace module named like the library, compiled or not; {@code null} when {@code root} has none. */
    public static @Nullable Module workspaceModule(Path root) {
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
                JkBuild module = JkBuildParser.parse(mm);
                if (ARTIFACT.equals(module.project().name())) return new Module(dir, module);
            } catch (IOException | RuntimeException e) {
                // an unparseable sibling is not the library
            }
        }
        return null;
    }
}
