// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testing;

import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Per-test throwaway directories short enough to hold a Unix-domain socket path.
 *
 * <p>{@code @TempDir} cannot serve here. jk's engine socket is
 * {@code <root>/engine/<key>.genN.sock}, and on macOS the default {@code TMPDIR} is already
 * {@code /var/folders/xx/yy…/T/}; JUnit's own random suffix pushes the total past what the JDK
 * will bind, so {@code bind} fails with a message about the *path* rather than about the test.
 * The budget is {@link UnixSocketPaths#MAX_PATH_LENGTH} — one number, proven by binding.
 * {@link #root()} is {@code ~/.jk-test-tmp/<pid>}: under the user home on every OS, never a drive
 * root ({@code C:\tmp}, {@code C:\opt}, {@code /opt}, …).
 *
 * <p>Register it and ask for as many roots as the test needs. Each directory is deleted after the
 * test; a shutdown hook deletes this JVM's {@code <pid>} tree (success or failure); the first
 * {@link #root()} call reaps leftover trees from dead PIDs so a crashed run cannot poison the next.
 *
 * <pre>{@code
 * @RegisterExtension
 * final ShortTempDirs dirs = new ShortTempDirs("jkd-");
 * ...
 * Path state = dirs.create();
 * }</pre>
 *
 * <p>Deletion is best-effort and never fails a test: an engine daemon that outlives the method can
 * hold a socket open, and losing a temp directory is not the defect the test is looking for.
 * {@link PathUtil#deleteRecursively} owns that catch.
 */
public final class ShortTempDirs implements AfterEachCallback {

    /** Namespaced directory under {@code user.home}. */
    public static final String DIR = ".jk-test-tmp";

    private static final List<Path> LIVE = Collections.synchronizedList(new ArrayList<>());

    private static volatile @Nullable Path jvmRoot;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(ShortTempDirs::reapLive, "jk-test-tmp-cleanup"));
    }

    private final String prefix;
    private final List<Path> created = new ArrayList<>();

    /** @param prefix directory-name prefix, so a leaked root is attributable to its suite */
    public ShortTempDirs(String prefix) {
        this.prefix = prefix;
    }

    /**
     * This JVM's throwaway parent: {@code ~/.jk-test-tmp/<pid>}. Created once per JVM. The first
     * call sweeps stale siblings (dead PIDs, leftover names) so a crashed run cannot leave the
     * namespace dirty. Parallel test JVMs keep their own {@code <pid>} trees.
     */
    public static Path root() throws IOException {
        Path mine = jvmRoot;
        if (mine != null) return mine;
        synchronized (ShortTempDirs.class) {
            mine = jvmRoot;
            if (mine != null) return mine;
            Path ns = namespace();
            Files.createDirectories(ns);
            PathUtil.deleteRecursively(ns.resolveSibling(".jk-test--tmp"));
            sweepStale(ns);
            mine = ns.resolve(Long.toString(ProcessHandle.current().pid()));
            PathUtil.deleteRecursively(mine);
            Files.createDirectories(mine);
            jvmRoot = mine;
            return mine;
        }
    }

    /**
     * Synthetic absolute path root (no mkdir): {@code ~/.jk-test-tmp} on every OS. Never a drive
     * root. Tests that only need an absolute fixture, not a real directory, should use this.
     */
    public static Path path() {
        return namespace();
    }

    /** A fresh directory under {@link #root()}, deleted after the current test. */
    public Path create() throws IOException {
        Path dir = Files.createTempDirectory(root(), prefix);
        created.add(dir);
        LIVE.add(dir);
        return dir;
    }

    @Override
    public void afterEach(ExtensionContext context) {
        for (Path dir : created) {
            LIVE.remove(dir);
            PathUtil.deleteRecursively(dir);
        }
        created.clear();
    }

    private static Path namespace() {
        return Path.of(System.getProperty("user.home"), DIR);
    }

    /**
     * Drop every child of {@code ns} that is not a live test JVM's {@code <pid>} directory. Crash
     * leftovers, unrecognized names, and PID-reuse dirt all go; concurrent workers stay.
     */
    static void sweepStale(Path ns) {
        if (!Files.isDirectory(ns)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(ns)) {
            for (Path child : stream) {
                if (isLiveJvmDir(child)) continue;
                PathUtil.deleteRecursively(child);
            }
        } catch (IOException ignored) {
            // next createTempDirectory surfaces a real error
        }
    }

    private static boolean isLiveJvmDir(Path child) {
        try {
            long pid = Long.parseLong(child.getFileName().toString());
            return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static void reapLive() {
        List<Path> dirs;
        synchronized (LIVE) {
            dirs = new ArrayList<>(LIVE);
            LIVE.clear();
        }
        for (Path dir : dirs) {
            PathUtil.deleteRecursively(dir);
        }
        Path mine = jvmRoot;
        if (mine != null) PathUtil.deleteRecursively(mine);
        Path ns = namespace();
        sweepStale(ns);
        try {
            Files.deleteIfExists(ns);
        } catch (IOException ignored) {
            // another JVM still has a <pid> child
        }
    }
}
