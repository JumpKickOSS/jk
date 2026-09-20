// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Where a forked test JVM writes its temporary files: under the module's build output, not the
 * host's temp dir.
 *
 * <p>{@link cc.jumpkick.runtime.base.TestEnv} decides the directory and puts it in {@code TMPDIR};
 * this makes it exist, splits it per worker, and hands {@link JUnitLauncher} the path to mirror
 * into {@code java.io.tmpdir}. Its own owner because those are three steps on one fact, spread
 * across a launcher that is already at its size baseline, and because the second of them —
 * isolation without leaving {@code target/} — is a rule that reads as an implementation detail
 * right up until someone reaches for {@code createTempDirectory} again.
 */
final class TestTmpDir {

    private TestTmpDir() {}

    /**
     * The module's temp root, made to exist.
     *
     * <p>The directory has to be there before a worker starts: a {@code java.io.tmpdir} naming
     * nothing fails the first {@code createTempFile}, and that failure reads as a broken test
     * rather than a missing directory. Null in, null out — {@link JUnitLauncher} is also driven
     * directly by tests with no module layout to write under, and inventing a directory for them
     * would be worse than the JVM default it is trying to replace. A failure to create is the same
     * answer: fall back to the default, which is where these files went before this existed.
     */
    static @Nullable Path ensure(@Nullable String configured) {
        if (configured == null || configured.isBlank()) return null;
        try {
            return Files.createDirectories(Path.of(configured));
        } catch (IOException | InvalidPathException unusable) {
            return null;
        }
    }

    /**
     * {@link #ensure}, emptied first: what the previous launch's workers left — JUnit trees a
     * crashed fork never deleted, a suite's scratch files — is not this launch's to inherit, and the
     * module's tmp root is nobody else's. A tree that will not delete (a file a dying process still
     * holds) is left; the launch still gets the directory.
     */
    static @Nullable Path fresh(@Nullable String configured) {
        Path dir = ensure(configured);
        if (dir == null) return null;
        try {
            // Quiet delete: a tree a dying process still holds stays until the next launch.
            PathUtil.forEachChild(dir, (child, attrs) -> {
                PathUtil.deleteRecursively(child);
                return true;
            });
        } catch (IOException unreadable) {
            // the directory exists; a listing that fails leaves it as it was
        }
        return dir;
    }

    /**
     * Worker {@code workerId}'s temp root: its own subdirectory of the module's when the pool is
     * split, the module's itself when it is not.
     *
     * <p>Mill-class isolation, but a subdirectory rather than a fresh directory under the
     * host temp dir. The point of pointing the temp root into {@code target/} is that nothing a
     * suite writes escapes the build output — where {@code jk clean} can reach it, and where no
     * other checkout shares it — and splitting the pool is not a reason to leave. Isolation is
     * best-effort: when the subdirectory cannot be made, the shared module root is still the better
     * of the two answers available, so it is the one the worker gets.
     */
    static @Nullable Path forWorker(@Nullable Path moduleTmp, int workerId, int totalWorkers) {
        if (moduleTmp == null || totalWorkers <= 1) return moduleTmp;
        try {
            return Files.createDirectories(moduleTmp.resolve("w" + workerId));
        } catch (IOException isolationFailed) {
            return moduleTmp;
        }
    }
}
