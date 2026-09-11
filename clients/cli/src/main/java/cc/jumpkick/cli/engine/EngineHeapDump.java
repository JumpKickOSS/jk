// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.wire.EnginePaths;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The trail an engine leaves when its JVM exits on {@code OutOfMemoryError}: the {@code <key>.hprof}
 * heap dump beside the log, and the JVM's own "OutOfMemoryError" lines at the log's tail. The next
 * spawn reports the exit once; {@code jk engine status} and {@code jk doctor} name the dump while
 * it exists.
 */
public final class EngineHeapDump {

    /** What to do about it, worded the same everywhere it is shown. */
    public static final String REMEDY = "raise `[engine] max-heap-mb` or run `jk cache prune`";

    /** The JVM writes the dump before {@code -XX:+ExitOnOutOfMemoryError} ends the process, so its report is last. */
    private static final int TAIL_BYTES = 16 * 1024;

    private EngineHeapDump() {}

    /** The heap dump for this engine identity, when one has been written. */
    public static Optional<Path> find(EnginePaths.Paths paths) {
        Path dump = EnginePaths.heapDump(paths);
        return Files.isRegularFile(dump) ? Optional.of(dump) : Optional.empty();
    }

    /** One line naming the dump and the remedy, for a status row or a doctor finding. */
    public static String finding(Path dump) {
        return "an earlier engine exited on OutOfMemoryError; heap dump at " + dump + "; " + REMEDY;
    }

    /**
     * Tell the user, once, that the engine whose log is about to be rotated died of an
     * OutOfMemoryError. Silent when the log's tail carries no such exit.
     */
    static void reportExit(EnginePaths.Paths paths) {
        exitMessage(paths.log(), EnginePaths.heapDump(paths)).ifPresent(m -> CliOutput.err("jk: " + m));
    }

    /**
     * The message for an OutOfMemoryError exit recorded at the tail of {@code log}, or empty when
     * the log ends any other way. The dump is named when it exists; otherwise the rotated log is.
     */
    static Optional<String> exitMessage(Path log, Path dump) {
        if (!exitedOnOutOfMemory(log)) return Optional.empty();
        String where = Files.isRegularFile(dump)
                ? "heap dump at " + dump
                : "no heap dump was written (see " + log.resolveSibling(log.getFileName() + ".1") + ")";
        return Optional.of("the build engine exited on OutOfMemoryError; " + where + "; " + REMEDY);
    }

    /** Whether the last {@value #TAIL_BYTES} bytes of {@code log} report an {@code OutOfMemoryError}. */
    static boolean exitedOnOutOfMemory(Path log) {
        if (!Files.isRegularFile(log)) return false;
        try (RandomAccessFile f = new RandomAccessFile(log.toFile(), "r")) {
            long length = f.length();
            int size = (int) Math.min(length, TAIL_BYTES);
            byte[] tail = new byte[size];
            f.seek(length - size);
            f.readFully(tail);
            return new String(tail, StandardCharsets.UTF_8).contains("OutOfMemoryError");
        } catch (IOException e) {
            return false;
        }
    }
}
