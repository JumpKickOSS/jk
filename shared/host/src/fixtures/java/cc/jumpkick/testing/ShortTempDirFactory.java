// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testing;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDirFactory;

/**
 * A {@code @TempDir} rooted at {@link ShortTempDirs#root()} — {@code ~/.jk-test-tmp/<pid>} on every
 * OS — so a fixture's paths stay short: a Unix-domain socket path has a hard cap, and a git
 * checkout under {@code target/<module>/tmp/w<n>/junit-<20 digits>/…/<sha>/} runs past Windows'
 * 260 characters. The test launcher names this factory for every module whose test classpath
 * carries it. If the root has no inodes left (tmpfs), stale {@code jk-junit-*} / {@code junit-*}
 * dirs we own are swept and the create retried once.
 *
 * <p>Always re-rooted rather than using {@code java.io.tmpdir}: the launcher points that at
 * {@code target/tmp} inside the checkout, and a {@code @TempDir} fixture must not sit there — jk's
 * own {@code jk.toml} would become the workspace root as {@code WorkspaceLocator.findRoot} walks
 * up. Worker isolation is kept: each worker JVM has a private tmpdir, and {@link
 * Files#createTempDirectory} still makes a distinct {@code jk-junit-*} directory per request.
 */
public final class ShortTempDirFactory implements TempDirFactory {

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

    /** {@link ShortTempDirs#root()}, falling back to the configured temp dir only when it cannot be had. */
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
                    // a file a live process still holds; the next sweep gets it
                }
            });
        } catch (IOException ignored) {
            // the tree is already gone or unreadable; nothing to sweep
        }
    }
}
