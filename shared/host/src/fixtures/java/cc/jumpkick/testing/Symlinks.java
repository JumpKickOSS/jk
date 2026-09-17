// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testing;

import static org.junit.jupiter.api.Assumptions.abort;

import cc.jumpkick.host.Os;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A symbolic link for a test: created on Linux and macOS, skipped on Windows.
 *
 * <p>A symlink is a POSIX-only shape in jk — a directory pointer is a junction on Windows
 * ({@code DirLinks}), a file is materialised with a hard link on every platform ({@link Linking}),
 * and the toolchain registry declines to link at all there ({@code SymlinkProvisioner.canSymlink}).
 * Creating one on Windows also needs {@code SeCreateSymbolicLinkPrivilege}: Developer Mode or an
 * elevated shell, which most Windows contributors do not hold.
 *
 * <p>So a test that needs a symlink is a test of POSIX behaviour, and this aborts it on Windows
 * rather than failing there — the suite is the same colour for every Windows contributor, whether
 * or not the privilege is held, and a skip says so where a swallowed {@code IOException} leaves a
 * green test that asserted nothing.
 */
public final class Symlinks {

    private Symlinks() {}

    /**
     * Create {@code link} pointing at {@code target} and return {@code link}, as {@link
     * Files#createSymbolicLink} does. Aborts the calling test on Windows.
     */
    public static Path create(Path link, Path target) throws IOException {
        if (Os.isWindows()) {
            abort("a symbolic link is POSIX-only in jk; Windows points with a junction");
        }
        return Files.createSymbolicLink(link, target);
    }
}
