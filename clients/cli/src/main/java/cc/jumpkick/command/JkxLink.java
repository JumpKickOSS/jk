// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.host.Os;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.JkOwnership;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Materializes {@code jkx} next to {@code jk} in {@code <home>/bin} (hardlink → symlink → shim;
 * Windows: {@code jkx.cmd}). Cheap on the happy path.
 *
 * <p>Whatever is at that name is jk's to replace, by containment: {@code <home>/bin} is jk's own
 * directory. The version of this that wrote to a shared PATH directory could not say that — it
 * read the first kilobyte looking for a generated-by header and refused anything over 4 KiB,
 * because in a directory everything installs into, a name is not a claim.
 */
final class JkxLink {

    /** Attribution line in every shim jk generates. Read by humans, not by this class. */
    private static final String MARKER = JkOwnership.GENERATED_BY;

    enum Status {
        /** Link or shim written (fresh, repaired, or re-pointed). */
        CREATED,
        /** Already present and pointing at this jk. */
        CURRENT,
        /** The jk executable couldn't be resolved to an absolute file; nothing to link to. */
        SKIPPED_NO_EXE
    }

    record Result(Status status, Path path) {}

    private JkxLink() {}

    /** Ensure {@code binDir/jkx} launches {@code jkExe}'s {@code tool run}. Never throws. */
    static Result ensure(Path binDir, @Nullable Path jkExe) {
        try {
            if (jkExe == null || !jkExe.isAbsolute() || !Files.isRegularFile(jkExe)) {
                return new Result(Status.SKIPPED_NO_EXE, null);
            }
            return Os.isWindows() ? ensureCmdShim(binDir, jkExe) : ensurePosix(binDir, jkExe);
        } catch (IOException | RuntimeException e) {
            // Best-effort by contract: a broken jkx must never break `jk activate`.
            return new Result(Status.SKIPPED_NO_EXE, null);
        }
    }

    private static Result ensurePosix(Path binDir, Path jkExe) throws IOException {
        Path jkx = binDir.resolve("jkx");
        if (Files.exists(jkx, LinkOption.NOFOLLOW_LINKS)) {
            // Healthy fast path: hardlink (same inode) or symlink resolving to this jk.
            if (Files.exists(jkx) && Files.isSameFile(jkx, jkExe)) {
                return new Result(Status.CURRENT, jkx);
            }
            Files.delete(jkx); // stale: old shim, moved jk, or broken link we created
        }
        Files.createDirectories(binDir);
        try {
            Files.createLink(jkx, jkExe);
        } catch (IOException | UnsupportedOperationException hardlinkFailed) {
            try {
                Files.createSymbolicLink(jkx, jkExe);
            } catch (IOException | UnsupportedOperationException symlinkFailed) {
                writeShim(jkx, posixShim(jkExe));
                if (!jkx.toFile().setExecutable(true, false)) {
                    throw new IOException("could not mark " + jkx + " executable");
                }
            }
        }
        return new Result(Status.CREATED, jkx);
    }

    private static Result ensureCmdShim(Path binDir, Path jkExe) throws IOException {
        Path jkx = binDir.resolve("jkx.cmd");
        String want = cmdShim(jkExe);
        if (Files.exists(jkx, LinkOption.NOFOLLOW_LINKS)) {
            String have = Files.readString(jkx, StandardCharsets.UTF_8);
            if (want.equals(have)) return new Result(Status.CURRENT, jkx);
        }
        Files.createDirectories(binDir);
        writeShim(jkx, want);
        return new Result(Status.CREATED, jkx);
    }

    private static void writeShim(Path jkx, String content) throws IOException {
        AtomicWrites.replace(jkx, content);
    }

    private static String posixShim(Path jkExe) {
        return "#!/bin/sh\n"
                + "# jkx — `jk tool run` launcher (" + MARKER + "; do not edit)\n"
                + "exec \"" + jkExe + "\" tool run \"$@\"\n";
    }

    private static String cmdShim(Path jkExe) {
        return "@echo off\r\n"
                + "rem jkx — `jk tool run` launcher (" + MARKER + "; do not edit)\r\n"
                + "\"" + jkExe + "\" tool run %*\r\n";
    }
}
