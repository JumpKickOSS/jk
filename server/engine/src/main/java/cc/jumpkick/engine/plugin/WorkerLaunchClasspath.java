// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.host.Classpaths;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.repo.ArtifactMemo;
import cc.jumpkick.repo.PomRuntimeClasspath;
import cc.jumpkick.repo.RepoArtifactResolver;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Classpath used to fork a thin plugin worker: the worker jar plus the Maven runtime closure from
 * its POM ({@code repos/jk-local} / {@code jumpkick} / {@code central} / {@code google}). A workspace {@code target/}
 * worker also gets plugin-sdk and host from {@code target/shared/} (the codec a shipped worker jar
 * vendors).
 *
 * <p>{@code repos/jk-local} jars are pinned into the artifact CAS before the fork sees them: a
 * concurrent {@code cache-install} replaces the shelf path in place, and on Windows that rename
 * is refused while a worker still has the shelf file mapped. A content-addressed copy is never
 * overwritten, so the shelf can move under the workers.
 */
public final class WorkerLaunchClasspath {

    private WorkerLaunchClasspath() {}

    public static List<Path> paths(Path workerJar) {
        // A CAS blob can only reach a fork as a path-pinned plugin jar — coordinate pins and
        // first-party workers resolve to Maven-layout paths. A path pin carries no POM, so the
        // sha-verified jar is its whole classpath here; the SDK floor it compiled against joins at
        // the launch from the consumer's lock (PluginSdkFloor).
        if (Cas.isBlobPath(workerJar)) return List.of(workerJar);
        Path worker = workerJar.toAbsolutePath().normalize();
        List<Path> resolved = pinJkLocal(PomRuntimeClasspath.resolve(worker));
        List<Path> codec = workspaceCodec(worker);
        if (codec.isEmpty()) return resolved;
        // Codec dirs FIRST so a just-compiled classes/main wins over the copy the worker jar vendors
        // — first-match-wins otherwise let a stale vendored codec shadow the fresh SDK, the exact
        // codec skew this path exists to avoid.
        LinkedHashSet<Path> out = new LinkedHashSet<>();
        out.addAll(codec);
        out.addAll(resolved);
        return List.copyOf(out);
    }

    public static String resolve(Path workerJar) {
        return Classpaths.join(paths(workerJar));
    }

    /**
     * Copy each {@code repos/jk-local} jar into the artifact CAS (idempotent by sha). POM
     * resolution still reads the shelf; only the paths handed to the forked JVM change.
     */
    static List<Path> pinJkLocal(List<Path> resolved) {
        Cas cas = JkStores.storeCas();
        List<Path> out = new ArrayList<>(resolved.size());
        for (Path p : resolved) {
            out.add(isJkLocalJar(p) ? pinOne(cas, p) : p);
        }
        return List.copyOf(out);
    }

    /**
     * True when {@code path} sits under the live artifact store's {@code repos/jk-local} — the
     * shelf {@code cache-install} overwrites. Test fixtures and alternate store roots are left
     * alone.
     */
    static boolean isJkLocalJar(Path path) {
        if (path == null) return false;
        Path shelf = JkStores.store()
                .resolve("repos")
                .resolve(RepoArtifactResolver.JK_LOCAL)
                .toAbsolutePath()
                .normalize();
        return path.toAbsolutePath().normalize().startsWith(shelf);
    }

    private static Path pinOne(Cas cas, Path jar) {
        try {
            String hex = shaOf(jar);
            return cas.putFile(jar, hex);
        } catch (IOException e) {
            // Launch with the shelf path: cache-install may still race, but a pin failure must not
            // refuse every worker on a full disk.
            return jar;
        }
    }

    /**
     * The shelf memo's sha when the memo still describes this file — its recorded size and mtime
     * are the jar's, the pair the memo is keyed on — otherwise the bytes are hashed. A memo that
     * outlived a same-length replacement must not pin the previous jar's blob under a worker.
     */
    static String shaOf(Path jar) throws IOException {
        long size = Files.size(jar);
        long mtime = Files.getLastModifiedTime(jar).toMillis();
        Path memo = jar.resolveSibling(ArtifactMemo.jkFileName(jar.getFileName().toString()));
        var pinned = ArtifactMemo.read(memo)
                .filter(m -> m.size() == size && m.mtimeMillis() == mtime)
                .map(ArtifactMemo::sha256);
        if (pinned.isPresent()) return pinned.get();
        return Hashing.sha256Hex(jar);
    }

    /**
     * plugin-sdk + host next to a workspace-built worker. Prefer {@code classes/main} when present
     * so a just-compiled SDK is used even if the sibling jar is stale.
     */
    static List<Path> workspaceCodec(Path workerJar) {
        Path target = workspaceTarget(workerJar);
        if (target == null) return List.of();
        List<Path> out = new ArrayList<>();
        addCodecModule(out, target.resolve("shared").resolve("plugin-sdk"), "jk-plugin-sdk-");
        addCodecModule(out, target.resolve("shared").resolve("host"), "jk-host-");
        return List.copyOf(out);
    }

    static @Nullable Path workspaceTarget(Path workerJar) {
        // The jar has to be something jk built, not merely something sitting under a build tree.
        // A `shared/` child proves the directory is a workspace out tree; it does not prove this
        // file came out of one, and jk's own test scratch now lives inside that same tree — so a
        // CAS blob or a @TempDir jar would otherwise pick up the workspace codec modules.
        if (!BuildLayout.isBuildOutput(workerJar)) return null;
        Path cur = workerJar.toAbsolutePath().normalize().getParent();
        while (cur != null) {
            Path name = cur.getFileName();
            if (name != null
                    && BuildLayout.TARGET.equals(name.toString())
                    && Files.isDirectory(cur.resolve("shared"))) {
                return cur;
            }
            cur = cur.getParent();
        }
        return null;
    }

    private static void addCodecModule(List<Path> out, Path moduleTarget, String jarPrefix) {
        Path classes = moduleTarget.resolve("classes").resolve("main");
        if (Files.isDirectory(classes)) {
            out.add(classes);
            return;
        }
        Path lib = moduleTarget.resolve("lib");
        if (!Files.isDirectory(lib)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(lib, "*.jar")) {
            for (Path jar : stream) {
                String n = jar.getFileName().toString();
                if (n.startsWith(jarPrefix)) out.add(jar.toAbsolutePath().normalize());
            }
        } catch (IOException ignored) {
            // Launch still fails clearly if PluginMain is missing.
        }
    }
}
