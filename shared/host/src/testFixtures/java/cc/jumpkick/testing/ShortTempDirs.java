// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testing;

import cc.jumpkick.host.Os;
import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Per-test throwaway directories short enough to hold a Unix-domain socket path.
 *
 * <p>{@code @TempDir} cannot serve here. A UDS address is capped by {@code sun_path} — 104 bytes on
 * macOS, 108 on Linux — and jk's engine socket is {@code <root>/engine/<key>.genN.sock}. On macOS
 * the default {@code TMPDIR} is already {@code /var/folders/xx/yy…/T/}, and JUnit's own random
 * suffix pushes the total past the cap, so {@code bind} fails with a message about the *path*
 * rather than about the test. Rooting at {@link #root()} leaves ~90 bytes of headroom.
 *
 * <p>Register it and ask for as many roots as the test needs; every one is deleted after the test:
 *
 * <pre>{@code
 * @RegisterExtension
 * final ShortTempDirs dirs = new ShortTempDirs("jkd-");
 * ...
 * Path state = dirs.create();
 * }</pre>
 *
 * <p>Deletion is best-effort and never fails a test: an engine daemon that outlives the method can
 * hold a socket open, and losing a temp directory is not the defect the test is looking for. That
 * daemon is also why the delete has to tolerate an entry disappearing <em>mid-walk</em> — a lazy
 * {@code Files.walk} surfaces that as an {@code UncheckedIOException}, not an {@code IOException}.
 * {@link PathUtil#deleteRecursively} owns both halves of that catch; the hand-rolled cleanups this
 * replaced each carried their own {@code catch (IOException | UncheckedIOException)} and a comment
 * saying why, and dropping the unchecked half turns another process's normal teardown into an
 * intermittent failure in ours.
 */
public final class ShortTempDirs implements AfterEachCallback {

    private final String prefix;
    private final List<Path> created = new ArrayList<>();

    /** @param prefix directory-name prefix, so a leaked root is attributable to its suite */
    public ShortTempDirs(String prefix) {
        this.prefix = prefix;
    }

    /**
     * Short throwaway parent for tests that mkdir outside the checkout.
     *
     * <p>POSIX: {@code /tmp} when present, else {@code java.io.tmpdir}. Windows:
     * {@code %USERPROFILE%\Temp} (created if missing) — never {@code C:\tmp}.
     */
    public static Path root() throws IOException {
        if (Os.isWindows()) {
            Path homeTemp = Path.of(System.getProperty("user.home"), "Temp");
            Files.createDirectories(homeTemp);
            return homeTemp;
        }
        Path tmp = Path.of("/tmp");
        if (Files.isDirectory(tmp)) {
            return tmp;
        }
        return Path.of(System.getProperty("java.io.tmpdir"));
    }

    /**
     * Synthetic absolute path root (no mkdir). Same locations as {@link #root()}: {@code /tmp} on
     * POSIX, {@code %USERPROFILE%\Temp} on Windows.
     */
    public static Path path() {
        return Os.isWindows() ? Path.of(System.getProperty("user.home"), "Temp") : Path.of("/tmp");
    }

    /** A fresh directory under the shortest usable root, deleted after the current test. */
    public Path create() throws IOException {
        Path dir = Files.createTempDirectory(root(), prefix);
        created.add(dir);
        return dir;
    }

    @Override
    public void afterEach(ExtensionContext context) {
        for (Path dir : created) {
            PathUtil.deleteRecursively(dir);
        }
        created.clear();
    }
}
