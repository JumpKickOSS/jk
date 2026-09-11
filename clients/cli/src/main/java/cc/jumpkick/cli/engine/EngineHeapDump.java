// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.wire.EnginePaths;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Optional;

/**
 * The trail an engine leaves when its JVM exits on {@code OutOfMemoryError}: a {@code
 * java_pid<pid>.hprof} heap dump in the engine directory beside the log, and the JVM's own
 * "OutOfMemoryError" lines at the log's tail. The next spawn reports the exit once; {@code jk engine
 * status} and {@code jk doctor} name the newest dump while one exists.
 */
public final class EngineHeapDump {

    /** What to do about it, worded the same everywhere it is shown. */
    public static final String REMEDY = "raise `[engine] max-heap-mb` or run `jk cache prune`";

    /** The JVM writes the dump before {@code -XX:+ExitOnOutOfMemoryError} ends the process, so its report is last. */
    private static final int TAIL_BYTES = 16 * 1024;

    private EngineHeapDump() {}

    /** The newest heap dump in this engine's directory, when one has been written. */
    public static Optional<Path> find(EnginePaths.Paths paths) {
        return newestDump(EnginePaths.heapDumpDir(paths));
    }

    /** The most recently modified {@code .hprof} directly under {@code dir}; empty when there is none. */
    static Optional<Path> newestDump(Path dir) {
        Path[] newest = {null};
        FileTime[] when = {null};
        try {
            PathUtil.forEachChild(dir, (file, attrs) -> {
                if (attrs.isRegularFile()
                        && EnginePaths.isHeapDump(file)
                        && (when[0] == null || attrs.lastModifiedTime().compareTo(when[0]) > 0)) {
                    newest[0] = file;
                    when[0] = attrs.lastModifiedTime();
                }
                return true;
            });
        } catch (IOException e) {
            return Optional.empty();
        }
        return Optional.ofNullable(newest[0]);
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
        exitMessage(paths.log(), EnginePaths.heapDumpDir(paths)).ifPresent(m -> CliOutput.err("jk: " + m));
    }

    /**
     * The message for an OutOfMemoryError exit recorded at the tail of {@code log}, or empty when
     * the log ends any other way. The newest dump under {@code dumpDir} is named when there is
     * one; otherwise the rotated log is.
     */
    static Optional<String> exitMessage(Path log, Path dumpDir) {
        if (!exitedOnOutOfMemory(log)) return Optional.empty();
        String where = newestDump(dumpDir)
                .map(dump -> "heap dump at " + dump)
                .orElse("no heap dump was written (see " + log.resolveSibling(log.getFileName() + ".1") + ")");
        return Optional.of("the build engine exited on OutOfMemoryError; " + where + "; " + REMEDY);
    }

    /**
     * Whether the last {@value #TAIL_BYTES} bytes of {@code log} report an {@code OutOfMemoryError}.
     * The match is the exception's qualified name: the log also echoes the engine's own JVM flags,
     * and {@code -XX:+ExitOnOutOfMemoryError} is on every engine's line, exit or no exit.
     */
    static boolean exitedOnOutOfMemory(Path log) {
        if (!Files.isRegularFile(log)) return false;
        try (RandomAccessFile f = new RandomAccessFile(log.toFile(), "r")) {
            long length = f.length();
            int size = (int) Math.min(length, TAIL_BYTES);
            byte[] tail = new byte[size];
            f.seek(length - size);
            f.readFully(tail);
            return new String(tail, StandardCharsets.UTF_8).contains("java.lang.OutOfMemoryError");
        } catch (IOException e) {
            return false;
        }
    }
}
