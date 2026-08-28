// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testing;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The address budget for a test that binds a Unix domain socket, and the root that fits it.
 *
 * <p>One number, in one place, proven by binding. It used to be four numbers in four files — 104,
 * "~104", "~108", and a bare {@code 60} derived from none of them — and the one that mattered was
 * none of those: {@code sun_path} is 104 bytes on macOS and 108 on Linux, but the JDK reserves a
 * byte and refuses at {@value #MAX_PATH_LENGTH}+1 with {@code SocketException: Unix domain path too
 * long}. A macOS per-user {@code $TMPDIR} composed a 103-byte engine socket path, which is inside
 * every cap those comments quoted and one byte outside the one the JDK enforces, so an entire test
 * tier failed to start an engine.
 *
 * <p>{@value #MAX_PATH_LENGTH} is the conservative floor across supported platforms, not the exact
 * maximum on any one: macOS is the tightest and measures exactly this, Linux's larger
 * {@code sun_path} leaves more. A budget wants the floor, so this is the floor.
 *
 * <p>Only tests need this. Production sockets live under {@code ~/.local/state/jk/engine/}, which is
 * short by construction; it is {@code @TempDir} — nested under a build directory, under a checkout,
 * under a home directory — that overruns. {@code jk-cli}'s own suites no longer bind one at all
 * (the tier speaks loopback TCP), but the engine's still do.
 */
public final class UnixSocketPaths {

    private UnixSocketPaths() {}

    /**
     * Longest path the JDK will bind as a Unix domain socket on every platform jk supports.
     *
     * <p>Verified by binding, not by arithmetic — see {@code UnixSocketPathsTest}. A cap that is
     * only ever asserted against itself is how the four wrong numbers survived.
     */
    public static final int MAX_PATH_LENGTH = 102;

    /** Short, present on POSIX, and outside both the per-user temp path and any checkout. */
    private static final Path SHORT_ROOT = Path.of("/tmp");

    /**
     * The root to create socket-bearing temp directories under.
     *
     * <p>{@code /tmp} when it exists, else the configured temp dir. Two reasons to prefer it and
     * both still hold: it leaves the whole budget above free for the socket name, and it is not
     * inside the checkout — a fixture project written under a build directory sits inside jk's own
     * workspace, where {@code WorkspaceLocator.findRoot} walks up and finds the real
     * {@code jk.toml} (JK-2329).
     */
    public static Path shortRoot() {
        return Files.isDirectory(SHORT_ROOT) ? SHORT_ROOT : Path.of(System.getProperty("java.io.tmpdir"));
    }
}
