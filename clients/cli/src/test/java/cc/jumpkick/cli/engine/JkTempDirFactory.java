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
 */
public final class JkTempDirFactory implements TempDirFactory {

    /** Longest root for which {@code root/jk-junit-<12 random>/…/<socket>} stays under sun_path. */
    static final int MAX_ROOT_LENGTH = 60;

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
     * Honor {@code java.io.tmpdir} when it is short enough for UDS paths — JUnitLauncher gives
     * each worker JVM a private tmpdir precisely so parallel workers don't share temp state, and
     * hard-coding a shared short root silently defeated that isolation. Fall back to
     * {@link ShortTempDirs#root()} only when the configured tmpdir would overflow {@code sun_path}.
     */
    static Path root(String configuredTmpdir) {
        if (configuredTmpdir != null && !configuredTmpdir.isBlank()) {
            Path configured = Path.of(configuredTmpdir);
            if (configured.toString().length() <= MAX_ROOT_LENGTH && Files.isDirectory(configured)) {
                return configured;
            }
        }
        try {
            return ShortTempDirs.root();
        } catch (IOException e) {
            return Path.of(configuredTmpdir);
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
