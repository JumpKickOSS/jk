// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testing;

/**
 * The address budget for a test that binds a Unix domain socket.
 *
 * <p>One number, in one place, proven by binding. {@code sun_path} is 104 bytes on macOS and 108
 * on Linux, but the JDK reserves a byte and refuses at {@value #MAX_PATH_LENGTH}+1 with
 * {@code SocketException: Unix domain path too long}. A macOS per-user {@code $TMPDIR} can compose
 * a 103-byte engine socket path, which is one byte outside that JDK limit.
 *
 * <p>{@value #MAX_PATH_LENGTH} is the conservative floor across supported platforms, not the exact
 * maximum on any one: macOS is the tightest and measures exactly this, Linux's larger
 * {@code sun_path} leaves more. A budget wants the floor, so this is the floor.
 *
 * <p>The root that fits this budget is {@link ShortTempDirs#root()} — {@code /tmp} on POSIX, and
 * {@code %USERPROFILE%\Temp} on Windows. It lives there rather than here because it is wanted by
 * every test that mkdirs outside the checkout, not only the ones that bind a socket; this class
 * owns the number, that one owns the place.
 *
 * <p>Only tests need this. Production sockets live under {@code ~/.jk/state/engine/}, which is
 * short by construction; it is {@code @TempDir} — nested under a build directory, under a checkout,
 * under a home directory — that overruns. {@code jk-cli}'s suites speak loopback TCP; the engine's
 * still bind Unix sockets.
 */
public final class UnixSocketPaths {

    private UnixSocketPaths() {}

    /**
     * Longest path the JDK will bind as a Unix domain socket on every platform jk supports.
     *
     * <p>Verified by binding, not by arithmetic — see {@code UnixSocketPathsTest}.
     */
    public static final int MAX_PATH_LENGTH = 102;
}
