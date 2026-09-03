// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.testing.ShortTempDirs;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDirFactory;

/**
 * Prefer short paths for UDS-friendly state ({@link ShortTempDirs#root()}). Cleanup is owned by
 * {@link JkTempDirDeletionStrategy}. If the root has no inodes left (tmpfs), sweep stale
 * {@code jk-junit-*} / {@code junit-*} dirs we own and retry once.
 *
 * <p>Always re-root rather than using {@code java.io.tmpdir}. The shared convention points that
 * at {@code build/tmp} / {@code target/tmp} inside the checkout, and a {@code @TempDir} fixture
 * must not sit there: jk's own {@code jk.toml} would become the workspace root as
 * {@code WorkspaceLocator.findRoot} walks up.
 */
public final class JkTempDirFactory implements TempDirFactory {

    @Override
    public Path createTempDirectory(AnnotatedElementContext elementContext, ExtensionContext extensionContext)
            throws Exception {
        Path root = root(System.getProperty("java.io.tmpdir"));
        try {
            return Files.createTempDirectory(root, "jk-junit-");
        } catch (IOException first) {
            sweepStale(root);
            return Files.createTempDirectory(root, "jk-junit-");
        }
    }

    /**
     * {@link ShortTempDirs#root()} — {@code /tmp} on POSIX, {@code %USERPROFILE%\Temp} on Windows
     * — falling back to the configured temp dir only when that root cannot be had.
     *
     * <p>Worker isolation is not lost by ignoring the configured value: {@code JUnitLauncher} gives
     * each worker JVM a private tmpdir so parallel workers don't share temp state, and
     * {@link Files#createTempDirectory} still makes a distinct {@code jk-junit-*} directory per
     * request under whichever root this returns.
     */
    static Path root(String configuredTmpdir) {
        try {
            return ShortTempDirs.root();
        } catch (IOException e) {
            if (configuredTmpdir != null && !configuredTmpdir.isBlank()) return Path.of(configuredTmpdir);
            return Path.of(System.getProperty("java.io.tmpdir"));
        }
    }

    /** Best-effort: drop leftover JUnit trees so a tmpfs inode exhaustion can recover. */
    static void sweepStale(Path root) {
        if (root == null || !Files.isDirectory(root)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                if (!name.startsWith("jk-junit-") && !name.startsWith("junit-")) continue;
                deleteQuietly(p);
            }
        } catch (IOException ignored) {
            // next createTempDirectory surfaces a real error
        }
    }

    private static void deleteQuietly(Path root) {
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
