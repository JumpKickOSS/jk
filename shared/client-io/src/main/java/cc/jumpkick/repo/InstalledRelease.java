// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.util.FileLocks;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Takes the jars and POMs {@code jk install} put in place for a set of coordinates back out: the
 * whole version directory on the shelf ({@code <store>/repos/jk-local}), and in a Maven local
 * repository only the files jk wrote there ({@link M2InstallMemo}) and still hold its bytes.
 */
public final class InstalledRelease {

    private static final List<String> CHECKSUMS = List.of("", ".sha1", ".md5", ".sha256", ".sha512");

    private InstalledRelease() {}

    /** What {@link #withdraw} removed, and the Maven-local slots it left because jk did not write them. */
    public record Withdrawn(List<Path> removed, List<Path> foreign) {}

    /**
     * Remove {@code coordinates} from the shelf under {@code storeRoot} and from {@code
     * m2Repository}. A slot that holds nothing is skipped; a Maven-local slot whose files jk did
     * not write, or that changed since, is left and listed.
     */
    public static Withdrawn withdraw(Path storeRoot, Path m2Repository, Collection<Coordinate> coordinates)
            throws IOException {
        List<Path> removed = new ArrayList<>();
        List<Path> foreign = new ArrayList<>();
        Path shelf = storeRoot.resolve("repos").resolve(RepoArtifactResolver.JK_LOCAL);
        if (Files.isDirectory(shelf)) {
            FileLocks.withLock(shelf.resolve(RepoArtifactStore.SHELF_LOCK_NAME), () -> {
                for (Coordinate c : coordinates) {
                    Path slot = slot(shelf, c);
                    if (!Files.isDirectory(slot)) continue;
                    PathUtil.deleteRecursivelyOrThrow(slot);
                    removed.add(slot);
                }
            });
        }
        for (Coordinate c : coordinates) {
            Path jar = MavenLayout.safeResolve(m2Repository, MavenLayout.artifactPath(c));
            Path pom = MavenLayout.safeResolve(m2Repository, MavenLayout.pomPath(c));
            Path memoFile = M2InstallMemo.file(storeRoot, m2Repository, c);
            Optional<M2InstallMemo> memo = M2InstallMemo.read(memoFile);
            if (!Files.exists(jar) && !Files.exists(pom)) {
                Files.deleteIfExists(memoFile);
                continue;
            }
            if (memo.isEmpty() || !memo.get().owns(sha(jar), sha(pom))) {
                foreign.add(slot(m2Repository, c));
                continue;
            }
            List<Path> files = new ArrayList<>(List.of(jar, pom));
            for (String classifier : List.of("sources", "javadoc")) {
                Coordinate attachment = new Coordinate(c.group(), c.artifact(), c.version(), classifier, "jar");
                Path path = MavenLayout.safeResolve(m2Repository, MavenLayout.artifactPath(attachment));
                if (M2InstallMemo.installedByJk(path)) files.add(path);
            }
            for (Path file : files) {
                for (String suffix : CHECKSUMS) {
                    Path p = file.resolveSibling(file.getFileName() + suffix);
                    if (Files.deleteIfExists(p)) removed.add(p);
                }
                M2Clean.forgetRemote(file);
            }
            Files.deleteIfExists(memoFile);
            deleteIfEmpty(slot(m2Repository, c));
        }
        return new Withdrawn(List.copyOf(removed), List.copyOf(foreign));
    }

    private static Path slot(Path root, Coordinate c) {
        Path jar = MavenLayout.safeResolve(root, MavenLayout.artifactPath(c));
        return Objects.requireNonNull(jar.getParent(), "version directory");
    }

    private static @Nullable String sha(Path file) throws IOException {
        return Files.isRegularFile(file) ? Hashing.sha256Hex(file) : null;
    }

    private static void deleteIfEmpty(Path dir) {
        try {
            Files.deleteIfExists(dir);
        } catch (IOException notEmpty) {
            // left as it is
        }
    }
}
