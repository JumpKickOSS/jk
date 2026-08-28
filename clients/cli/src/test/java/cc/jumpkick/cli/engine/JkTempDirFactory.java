// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDirFactory;

/**
 * Roots {@code @TempDir} at {@code /tmp} rather than under the build directory. Cleanup is owned by
 * {@link JkTempDirDeletionStrategy}. If {@code /tmp} has no inodes left (tmpfs), sweep stale
 * {@code jk-junit-*} / {@code junit-*} dirs we own and retry once.
 *
 * <p>This used to gate on the configured temp dir being longer than 60 characters, and the number
 * was about Unix-domain socket paths. That is no longer the reason: the tier that spawns engines
 * speaks loopback TCP, which has no path budget. The reason that remains is the one the length
 * gate was only ever approximating — a {@code @TempDir} fixture project must not sit
 * <em>inside the checkout</em>, because jk's own {@code jk.toml} is then its workspace root and
 * {@code WorkspaceLocator.findRoot} walks up into it (JK-2329). The shared convention points
 * {@code java.io.tmpdir} at {@code build/tmp} / {@code target/tmp}, both inside the checkout, so
 * this always re-roots rather than asking how long that path happens to be.
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
     * {@code /tmp}, falling back to the configured temp dir when there is no {@code /tmp}.
     *
     * <p>Worker isolation is not lost by ignoring the configured value: {@code JUnitLauncher} gives
     * each worker JVM a private tmpdir so parallel workers don't share temp state (JK-2183), and
     * {@link Files#createTempDirectory} still makes a distinct {@code jk-junit-*} directory per
     * request under whichever root this returns.
     */
    static Path root(String configuredTmpdir) {
        if (Files.isDirectory(Path.of("/tmp"))) return Path.of("/tmp");
        if (configuredTmpdir != null && !configuredTmpdir.isBlank()) return Path.of(configuredTmpdir);
        return Path.of(System.getProperty("java.io.tmpdir"));
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
