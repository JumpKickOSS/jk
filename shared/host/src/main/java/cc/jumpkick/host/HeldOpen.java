// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Replacing a file another process has open. Windows refuses to rename onto an open file, so a
 * replace there can fail for as long as a resident helper keeps the old file open; Linux and macOS
 * allow the rename, and there a failed write is passed on untouched.
 */
public final class HeldOpen {

    private HeldOpen() {}

    /** Who may still have the file open after a {@link Release}, and what frees it; the two halves of the error. */
    public record Holder(String who, String remedy) {}

    /** Close what this process's own helpers hold open, returning once they have; says who may still hold it. */
    @FunctionalInterface
    public interface Release {
        Holder release();
    }

    /** A write that replaces one file. */
    @FunctionalInterface
    public interface Write<T> {
        T write() throws IOException;
    }

    /** The target stayed open after the release; the message names it, its holder and the fix. */
    public static final class Held extends IOException {
        Held(String message, IOException cause) {
            super(message, cause);
        }
    }

    /**
     * Run {@code write}, which replaces {@code target}. When Windows refuses because the target is
     * open, run {@code release} and write once more; a second refusal throws {@link Held}.
     */
    public static <T> T replace(Path target, Write<T> write, Release release) throws IOException {
        return replace(target, write, release, e -> Os.isWindows() && refusedFor(e, target));
    }

    static <T> T replace(Path target, Write<T> write, Release release, Predicate<IOException> held) throws IOException {
        try {
            return write.write();
        } catch (IOException e) {
            if (!held.test(e)) throw e;
            Holder holder = release.release();
            try {
                return write.write();
            } catch (IOException again) {
                if (!held.test(again)) throw again;
                throw new Held(message(target, holder), again);
            }
        }
    }

    static String message(Path target, Holder holder) {
        return "could not replace " + target + ": it is open in " + holder.who() + "; " + holder.remedy();
    }

    /**
     * Whether {@code e} is the Windows denial {@link AtomicWrites#moveInto} gives up on, for a move
     * onto {@code target}: the move names the staged file first and the target second.
     */
    static boolean refusedFor(IOException e, Path target) {
        if (!(e instanceof FileSystemException f) || !AtomicWrites.isTransientWindowsLock(e)) return false;
        return names(f.getOtherFile(), target) || names(f.getFile(), target);
    }

    private static boolean names(@Nullable String file, Path target) {
        if (file == null) return false;
        try {
            return Path.of(file)
                    .toAbsolutePath()
                    .normalize()
                    .equals(target.toAbsolutePath().normalize());
        } catch (InvalidPathException e) {
            return false;
        }
    }
}
