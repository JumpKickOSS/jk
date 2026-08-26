// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Deferred JDK deletion queue ({@code <jdks-root>/.to-be-removed}) for Windows file locks after
 * {@link StableJdkPointer} repoint. Best-effort {@link #enqueue}/{@link #drain}.
 */
public final class JdkGarbage {

    private static final String QUEUE_FILE = ".to-be-removed";

    private final Path jdksRoot;

    public JdkGarbage(Path jdksRoot) {
        this.jdksRoot = Objects.requireNonNull(jdksRoot, "jdksRoot");
    }

    public static JdkGarbage atDefaultRoot() {
        return new JdkGarbage(JkDirs.jdks());
    }

    /** Record {@code dir} for later deletion. No-op if it's not under the JDK root. */
    public void enqueue(Path dir) {
        Path abs = canonical(dir);
        if (!abs.startsWith(canonicalRoot())) return; // never queue anything outside the managed JDK root
        try {
            Files.createDirectories(jdksRoot);
            Files.writeString(
                    queueFile(),
                    abs + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Best-effort; a missed enqueue just leaves a stale dir behind.
        }
    }

    /** Delete every queued dir that's no longer in use; rewrite the queue with survivors. */
    public void drain() {
        Path queue = queueFile();
        if (!Files.isRegularFile(queue)) return;
        List<String> lines;
        try {
            lines = Files.readAllLines(queue, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return;
        }
        List<String> survivors = new ArrayList<>();
        Path root = canonicalRoot();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            Path dir = canonical(Path.of(trimmed));
            if (!dir.startsWith(root)) continue; // defensive: never wander outside the managed JDK root
            if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) continue; // already gone
            deleteRecursively(dir);
            if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
                survivors.add(trimmed); // still locked — try again next run
            }
        }
        try {
            if (survivors.isEmpty()) {
                Files.deleteIfExists(queue);
            } else {
                Files.write(queue, survivors, StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
            // Another jk may have rewritten it concurrently — fine.
        }
    }

    private Path queueFile() {
        return jdksRoot.resolve(QUEUE_FILE);
    }

    private Path canonicalRoot() {
        return canonical(jdksRoot);
    }

    /**
     * Resolve symlinks when the path exists (macOS {@code /var}→{@code /private/var}); else
     * normalize.
     */
    private static Path canonical(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }

    private static void deleteRecursively(Path root) {
        PathUtil.deleteRecursively(root);
    }
}
