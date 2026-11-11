// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.git;

import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.util.FileLocks;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The git cache's directories (a bare mirror under {@code db/}, a checkout under {@code co/}) are
 * published whole: built in a sibling staging directory, renamed into place, then marked complete
 * by a sibling {@code <name>.complete} file, all under a per-directory lock. A directory without
 * its marker is a clone or checkout that was cut short (a timeout, a cancel, a killed engine) and is
 * built again, never served; two jobs asking for the same one wait for each other instead of one
 * deleting the other's work.
 */
final class GitCacheDirs {

    private GitCacheDirs() {}

    /** Fills a staging directory that does not exist yet. */
    @FunctionalInterface
    interface Build {
        void into(Path staging) throws IOException;
    }

    /** Runs while the directory's lock is held. */
    @FunctionalInterface
    interface Locked<T> {
        T run() throws IOException;
    }

    /** Whether {@code dir} was published whole. */
    static boolean complete(Path dir) {
        return Files.isDirectory(dir) && Files.exists(marker(dir));
    }

    /** {@code body} while holding {@code dir}'s lock, which every publish of {@code dir} takes. */
    static <T> T locked(Path dir, Locked<T> body) throws IOException {
        Path lock = dir.toAbsolutePath().resolveSibling(dir.getFileName() + ".lock");
        Files.createDirectories(Objects.requireNonNull(lock.getParent(), "a cache directory has a parent"));
        return FileLocks.withLock(lock, body::run);
    }

    /**
     * {@code dir}, built by {@code build} unless it is already complete; {@code fresh} discards a
     * complete one first. Call under {@link #locked}.
     */
    static Path publish(Path dir, boolean fresh, Build build) throws IOException {
        if (fresh) discard(dir);
        if (complete(dir)) return dir;
        discard(dir);
        Path staging = dir.resolveSibling(
                "." + dir.getFileName() + ".staging-" + ProcessHandle.current().pid());
        JGitExtension.deleteRecursively(staging);
        try {
            build.into(staging);
            AtomicWrites.publish(staging, dir);
            Files.createFile(marker(dir));
            return dir;
        } finally {
            JGitExtension.deleteRecursively(staging);
        }
    }

    /** Removes {@code dir} and its marker. */
    static void discard(Path dir) throws IOException {
        Files.deleteIfExists(marker(dir));
        JGitExtension.deleteRecursively(dir);
    }

    private static Path marker(Path dir) {
        return dir.resolveSibling(dir.getFileName() + ".complete");
    }
}
