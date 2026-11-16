// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.host.HostTemp;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One suite run's heap-retry state: where its JVMs mark a heap exhaustion of their own, which only
 * the JVM itself can do ({@link #onOutOfMemory}), and whether the run climbed the heap ladder. A
 * launcher starts one per run ({@link #start}) and closes it when the run ends ({@link #close}).
 */
final class SuiteHeapRun {

    /** The marks directory of the run, or null when there is no place for one. */
    private @Nullable Path marks;

    /** A marks directory this run made outside its temp root, removed when the run ends. */
    private @Nullable Path owned;

    private boolean climbed;

    /**
     * Start a run: a fresh marks directory under its temp root {@code tmpRoot}, or, without one, a
     * directory of its own.
     */
    void start(@Nullable Path tmpRoot) {
        close();
        climbed = false;
        try {
            if (tmpRoot != null) {
                marks = Files.createDirectories(tmpRoot.resolve(MARKERS));
                forget(marks);
            } else {
                owned = Files.createTempDirectory(HostTemp.dir(), "jk-oom-");
                marks = owned;
            }
        } catch (IOException | RuntimeException unwritable) {
            Log.debug("suite retry: no place for heap marks", unwritable);
            marks = null;
        }
    }

    /** The flag that has the run's suite JVMs mark a heap exhaustion; null when the run has no marks. */
    @Nullable
    String flag() {
        Path dir = marks;
        return dir == null ? null : onOutOfMemory(dir, Os.isWindows());
    }

    /** Whether a JVM of the run marked its own heap exhaustion since the last {@link #forget()}. */
    boolean ranOut() {
        return ranOut(marks);
    }

    /** Forget the marks, so the next JVM's verdict is its own. */
    void forget() {
        forget(marks);
    }

    /** The run re-ran classes on another heap. */
    void climbed() {
        climbed = true;
    }

    /** Whether the run passed at the heap it planned, never running out: its learned heap may come down. */
    boolean green(boolean failed) {
        return !failed && !climbed && !ranOut();
    }

    /** The run is over: a marks directory it made outside its temp root goes. */
    void close() {
        Path dir = owned;
        if (dir == null) return;
        forget(dir);
        try {
            Files.deleteIfExists(dir);
        } catch (IOException kept) {
            Log.debug("suite retry: " + dir + " not removed", kept);
        }
        owned = null;
        if (dir.equals(marks)) marks = null;
    }

    /** The directory name, under a run's temp root, the suite JVMs mark a heap exhaustion in. */
    static final String MARKERS = ".jk-oom";

    /**
     * The flag that has a suite JVM mark its own heap exhaustion: HotSpot runs {@code
     * -XX:OnOutOfMemoryError} when the VM itself first throws an {@link OutOfMemoryError}, never for
     * one a test constructs or prints, and replaces {@code %p} with its pid. Null when {@code dir}
     * cannot be quoted into a shell command (it holds a quote character), so the suite is then never
     * retried on a larger heap.
     */
    static @Nullable String onOutOfMemory(Path dir, boolean windows) {
        String marker = dir.resolve("oom-%p").toString();
        if (marker.indexOf('\'') >= 0 || marker.indexOf('"') >= 0 || marker.indexOf(';') >= 0) return null;
        // HotSpot runs the command with /bin/sh -c, or cmd /c on Windows.
        return "-XX:OnOutOfMemoryError=" + (windows ? "type nul > \"" + marker + "\"" : "touch '" + marker + "'");
    }

    /** Whether a suite JVM marked a heap exhaustion of its own in {@code dir}. */
    static boolean ranOut(@Nullable Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return false;
        boolean[] seen = {false};
        try {
            PathUtil.forEachChild(dir, (child, attrs) -> {
                if (child.getFileName().toString().startsWith("oom-")) seen[0] = true;
                return !seen[0];
            });
        } catch (IOException unreadable) {
            return false;
        }
        return seen[0];
    }

    /** Forget the marks in {@code dir}, so the next JVM's verdict is its own. */
    static void forget(@Nullable Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return;
        List<Path> marks = new ArrayList<>();
        try {
            PathUtil.forEachChild(dir, (child, attrs) -> {
                if (child.getFileName().toString().startsWith("oom-")) marks.add(child);
                return true;
            });
            for (Path mark : marks) Files.deleteIfExists(mark);
        } catch (IOException kept) {
            Log.debug("suite retry: heap marks not cleared in " + dir, kept);
        }
    }
}
