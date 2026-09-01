// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.discovery;

import cc.jumpkick.host.Os;
import cc.jumpkick.util.JkOwnership;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Helpers for the link side of the discover-and-link pattern. Splits out so {@code JdkRegistry} and
 * the Maven/Gradle/Kotlin {@code ToolRegistry} can share the same Windows guard and the same
 * link-vs-real semantics.
 *
 * <p>Per the pipeline: never symlink on Windows — {@link #canSymlink()} short-circuits there. Callers
 * fall back to a regular download path.
 */
public final class SymlinkProvisioner {

    private SymlinkProvisioner() {}

    /** False on Windows (junction handling is too quirky). True elsewhere. */
    public static boolean canSymlink() {
        return !Os.isWindows();
    }

    /**
     * Create a symbolic link {@code target → source}. Caller has already checked {@link
     * #canSymlink()}; this method throws on Windows.
     *
     * <p>{@code target}'s parent is created if missing. A pre-existing entry at {@code target} is
     * removed first, so the call is idempotent — but only on {@link JkOwnership}'s terms: a link or
     * an empty directory goes, a <em>populated</em> directory goes only if jk created it. This used
     * to recurse into whatever was there, which is the shape that destroyed real JDKs from the
     * pointer path in JK-2624; nothing but the caller's choice of {@code target} kept this copy from
     * doing the same (JK-2625).
     */
    public static void link(Path target, Path source) throws IOException {
        if (!canSymlink()) {
            throw new IOException("symlinking is disabled on Windows");
        }
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            JkOwnership.removeIfOwned(target);
        }
        Files.createSymbolicLink(target, source);
    }

    /**
     * True when the path is a symlink whose target no longer resolves to an existing entry. Useful
     * for the broken-link healthcheck before exec.
     */
    public static boolean isBrokenLink(Path path) {
        if (!Files.isSymbolicLink(path)) return false;
        return !Files.exists(path); // follows the link by default
    }

    /** Remove the link itself (not its target). Safe even when the target has gone away. */
    public static void unlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            Files.deleteIfExists(path);
        }
    }

}
