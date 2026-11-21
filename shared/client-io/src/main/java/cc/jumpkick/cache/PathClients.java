// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.time.Clock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The {@code jk} and {@code jkx} clients on PATH: placing a new pair over the live one without the
 * bin ever lacking a client, parking a replaced pair as {@code .old} where the platform will not
 * replace a running binary, and sweeping what earlier or crashed installs parked or staged.
 */
public final class PathClients {

    private PathClients() {}

    /**
     * Move {@code live} aside to its {@link #parkedName}, or beside it when that name is held; empty
     * when there is nothing at {@code live}.
     */
    public static Optional<Path> displaceToOld(Path live) throws IOException {
        if (live == null || !Files.exists(live)) return Optional.empty();
        Path old = parkedName(live);
        try {
            Files.deleteIfExists(old);
        } catch (IOException ignored) {
            // still mapped; park beside it below
        }
        try {
            Files.move(live, old, StandardCopyOption.REPLACE_EXISTING);
            return Optional.of(old);
        } catch (IOException occupied) {
            // The parked name is still mapped, or a scanner holds the live file it just saw written:
            // park beside it, retrying a Windows sharing denial the way every atomic replace does.
            Path alt = live.resolveSibling(live.getFileName() + ".old-" + Clock.SYSTEM.nanos());
            AtomicWrites.moveInto(live, alt);
            return Optional.of(alt);
        }
    }

    /** The {@code jk} on PATH under {@code binDir}: {@code jk.exe}, else {@code jk.bat}, else {@code jk}. */
    public static Path pathClient(Path binDir) {
        Path exe = binDir.resolve("jk.exe");
        if (Files.isRegularFile(exe)) return exe;
        Path bat = binDir.resolve("jk.bat");
        if (Files.isRegularFile(bat)) return bat;
        return binDir.resolve("jk");
    }

    /** A client an install parked: {@code jk.old}, {@code jk.exe.old-<nanos>}, {@code jkx.bat.old} and so on. */
    private static final Pattern PARKED = Pattern.compile("jkx?(\\.exe|\\.bat|\\.cmd)?\\.old(-\\d+)?");

    /** A client {@link #installBinaries} staged: {@code .jk-new-<nonce>}, {@code .jkx.exe-new-<nonce>} and so on. */
    private static final Pattern STAGED = Pattern.compile("\\.jkx?(\\.exe)?-new-.+");

    /**
     * How old a staged client must be before a sweep takes it. An install renames its staged pair
     * within seconds, so one this old was left by a crash, never by an install still running.
     */
    private static final Duration STAGED_MAX_AGE = Duration.ofHours(1);

    /** Whether {@code p} is a client a crashed install staged and never renamed into place. */
    static boolean isAbandonedStaged(Path p, long modifiedMillis, long nowMillis) {
        return STAGED.matcher(p.getFileName().toString()).matches()
                && nowMillis - modifiedMillis > STAGED_MAX_AGE.toMillis();
    }

    /**
     * Delete the clients earlier installs parked under {@code binDir}, and the ones a crashed install
     * staged more than {@link #STAGED_MAX_AGE} ago. A parked client still running keeps its file on
     * Windows (the delete is refused while it is mapped) and is left for the next sweep; nothing else
     * is touched. Returns how many went.
     */
    public static int sweepParked(Path binDir) {
        int[] removed = {0};
        long now = Clock.SYSTEM.millis();
        try {
            PathUtil.forEachChild(binDir, (p, attrs) -> {
                if (attrs.isRegularFile()
                        && (PARKED.matcher(p.getFileName().toString()).matches()
                                || isAbandonedStaged(p, attrs.lastModifiedTime().toMillis(), now))) {
                    try {
                        Files.delete(p);
                        removed[0]++;
                    } catch (IOException stillRunning) {
                        // Mapped by a process that has not exited yet: the next sweep takes it.
                    }
                }
                return true;
            });
        } catch (IOException e) {
            return removed[0];
        }
        return removed[0];
    }

    /** The name a replaced client is parked under: {@code <live>.old}. */
    public static Path parkedName(Path live) {
        return live.resolveSibling(live.getFileName() + ".old");
    }

    /** {@link #installBinaries(Path, Path, boolean)} for this platform. */
    public static void installBinaries(Path clientSource, Path binDir) throws IOException {
        installBinaries(clientSource, binDir, Os.isWindows());
    }

    /**
     * Replace the PATH client with a copy of {@code clientSource}. Both {@code jk} and {@code jkx}
     * are staged beside their destination and made executable first; only then is the staged pair
     * renamed over the live one. On Windows the live pair is parked as {@code .old} before the
     * rename, and a failure after the park puts it back, so the bin never ends up with no client.
     */
    static void installBinaries(Path clientSource, Path binDir, boolean windows) throws IOException {
        Files.createDirectories(binDir);
        // The clients an earlier install parked go first; the one this install parks stays until the next.
        sweepParked(binDir);
        String jkName = windows ? "jk.exe" : "jk";
        String jkxName = windows ? "jkx.exe" : "jkx";
        Path jk = binDir.resolve(jkName);
        Path jkx = binDir.resolve(jkxName);
        // Names of this install's own: two installs into one bin never write or delete each other's.
        String prefix = "." + jkName + "-new-";
        Path stagedJk = Files.createTempFile(binDir, prefix, "");
        String nonce = stagedJk.getFileName().toString().substring(prefix.length());
        Path stagedJkx = binDir.resolve("." + jkxName + "-new-" + nonce);
        try {
            // A copy, never a link: the source is a CAS blob whose bytes its digest vouches for, and a
            // linked bin/jk would share its inode — the chmod below, or any later rewrite of the PATH
            // client, would then change the verified blob in place.
            Files.copy(clientSource, stagedJk, StandardCopyOption.REPLACE_EXISTING);
            makeExecutable(stagedJk);
            try {
                Files.createLink(stagedJkx, stagedJk);
            } catch (IOException | UnsupportedOperationException noHardlink) {
                Files.copy(stagedJk, stagedJkx, StandardCopyOption.REPLACE_EXISTING);
            }
            makeExecutable(stagedJkx);
        } catch (IOException | RuntimeException staging) {
            discardStaged(stagedJk, staging);
            discardStaged(stagedJkx, staging);
            throw staging;
        }
        Optional<Path> parkedJk = Optional.empty();
        Optional<Path> parkedJkx = Optional.empty();
        try {
            // POSIX renames over a running binary, which keeps its inode, so the name is never
            // missing; Windows refuses to replace a mapped .exe, so the live one is parked first.
            if (windows) {
                parkedJk = displaceToOld(jk);
                parkedJkx = displaceToOld(jkx);
            }
            AtomicWrites.moveInto(stagedJk, jk);
            AtomicWrites.moveInto(stagedJkx, jkx);
        } catch (IOException | RuntimeException publish) {
            restoreParked(parkedJk, jk, publish);
            restoreParked(parkedJkx, jkx, publish);
            discardStaged(stagedJk, publish);
            discardStaged(stagedJkx, publish);
            throw publish;
        }
    }

    /**
     * Put a client {@link #displaceToOld} parked back as {@code live}, over whatever the failed
     * publish left there. A failure to do so is added to {@code cause} as suppressed.
     */
    public static void restoreParked(Optional<Path> parked, Path live, Exception cause) {
        if (parked.isEmpty()) return;
        try {
            Files.move(parked.get(), live, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException failed) {
            cause.addSuppressed(failed);
        }
    }

    /** Delete a staged sibling a failed publish left behind; a failure is suppressed into {@code cause}. */
    public static void discardStaged(Path staged, Exception cause) {
        try {
            Files.deleteIfExists(staged);
        } catch (IOException | RuntimeException failed) {
            cause.addSuppressed(failed);
        }
    }

    static boolean isParkedClientName(String name) {
        if (name == null || name.isBlank()) return false;
        String n = name.toLowerCase(Locale.ROOT);
        if (n.startsWith(".jk-old") || n.startsWith(".jkx-old")) return true;
        if (n.equals("jk.old.exe") || n.equals("jkx.old.exe")) return true;
        return n.startsWith("jk.old")
                || n.startsWith("jk.exe.old")
                || n.startsWith("jk.bat.old")
                || n.startsWith("jk.cmd.old")
                || n.startsWith("jkx.old")
                || n.startsWith("jkx.exe.old")
                || n.startsWith("jkx.bat.old")
                || n.startsWith("jkx.cmd.old");
    }

    private static void makeExecutable(Path p) {
        try {
            var perms = Files.getPosixFilePermissions(p);
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(p, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows / restricted FS
        }
    }
}
