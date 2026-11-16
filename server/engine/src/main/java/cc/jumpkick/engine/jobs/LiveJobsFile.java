// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.wire.EnginePaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The jobs an engine holds, kept on disk beside its log, rewritten whenever memory admission admits
 * or releases one: an engine runs with {@code -XX:+ExitOnOutOfMemoryError}, so nothing of it runs
 * when its heap runs out, and this file is what the next engine can say about the one that died. The
 * next engine reads it at start ({@link #previous}) and, when the engine it names left a heap dump,
 * logs "the previous engine exited on OutOfMemoryError with these jobs live: …".
 *
 * <p>Format, one line each: {@code pid <pid>}, {@code heap <used> <max>} in bytes at the last
 * rewrite, then {@code live <jid> <kind> <estimate bytes> <dir>} and {@code queued <jid> <kind> <dir>}.
 */
public final class LiveJobsFile {

    /** One job, as the file names it. */
    public record Job(long jid, String kind, String dir, long estimateBytes) {}

    private final Path file;
    private final long pid;

    public LiveJobsFile(Path file, long pid) {
        this.file = file;
        this.pid = pid;
    }

    /**
     * An engine's file: {@code <engine dir>/<key>.<pid>.jobs}. The pid keeps a draining predecessor
     * and its successor, which share the key for a while, from writing over each other.
     */
    public static Path fileOf(EnginePaths.Paths paths, long pid) {
        return paths.dir().resolve(paths.key() + "." + pid + ".jobs");
    }

    /** The file of the engine of this process. */
    public static LiveJobsFile forThisEngine() {
        long pid = ProcessHandle.current().pid();
        return new LiveJobsFile(fileOf(EnginePaths.current(), pid), pid);
    }

    /**
     * What the files earlier engines under {@code paths} left say ({@link #previous(Path, Path, long)}),
     * one line per engine that exited on OutOfMemoryError. A file of an engine still running (a
     * predecessor draining) is left to it.
     */
    public static List<String> previous(EnginePaths.Paths paths, long thisPid) {
        List<String> out = new ArrayList<>();
        String prefix = paths.key() + ".";
        List<Path> files = new ArrayList<>();
        try {
            PathUtil.forEachChild(paths.dir(), (child, attrs) -> {
                String name = child.getFileName().toString();
                if (name.startsWith(prefix) && name.endsWith(".jobs")) files.add(child);
                return true;
            });
        } catch (IOException none) {
            return out;
        }
        for (Path f : files) {
            String name = f.getFileName().toString();
            long pid;
            try {
                pid = Long.parseLong(name.substring(prefix.length(), name.length() - ".jobs".length()));
            } catch (NumberFormatException other) {
                continue;
            }
            if (pid == thisPid
                    || ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) continue;
            String line = previous(f, EnginePaths.heapDumpDir(paths), thisPid);
            if (line != null) out.add(line);
        }
        return out;
    }

    /** Rewrite the file: the jobs admitted now, those waiting, and the heap. Never throws. */
    public void write(List<Job> live, List<Job> queued, long heapUsedBytes, long heapMaxBytes) {
        StringBuilder sb = new StringBuilder();
        sb.append("pid ").append(pid).append('\n');
        sb.append("heap ")
                .append(heapUsedBytes)
                .append(' ')
                .append(heapMaxBytes)
                .append('\n');
        for (Job j : live) {
            sb.append("live ")
                    .append(j.jid())
                    .append(' ')
                    .append(word(j.kind()))
                    .append(' ')
                    .append(j.estimateBytes())
                    .append(' ')
                    .append(line(j.dir()))
                    .append('\n');
        }
        for (Job j : queued) {
            sb.append("queued ")
                    .append(j.jid())
                    .append(' ')
                    .append(word(j.kind()))
                    .append(' ')
                    .append(line(j.dir()))
                    .append('\n');
        }
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            AtomicWrites.replace(file, sb.toString());
        } catch (IOException | RuntimeException unwritable) {
            Log.debug("live jobs: " + file + " not written", unwritable);
        }
    }

    /**
     * What the file left by an earlier engine says, as one log line, when that engine left a heap
     * dump ({@code java_pid<pid>.hprof} in {@code dumpDir}); null otherwise. The file is removed
     * either way, so one death is told once.
     */
    public static @Nullable String previous(Path file, Path dumpDir, long thisPid) {
        if (!Files.isRegularFile(file)) return null;
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            long pid = -1;
            long used = -1;
            long max = -1;
            List<String> live = new ArrayList<>();
            int queued = 0;
            for (String l : lines) {
                String[] w = l.split(" ", 5);
                switch (w[0]) {
                    case "pid" -> pid = Long.parseLong(w[1]);
                    case "heap" -> {
                        used = Long.parseLong(w[1]);
                        max = Long.parseLong(w[2]);
                    }
                    case "live" -> {
                        if (w.length == 5)
                            live.add(w[2] + " " + w[4] + " (jid " + w[1] + ", " + mib(Long.parseLong(w[3]))
                                    + " estimated)");
                    }
                    case "queued" -> queued++;
                    default -> {}
                }
            }
            if (pid < 0 || pid == thisPid) return null;
            if (!Files.isRegularFile(dumpDir.resolve("java_pid" + pid + ".hprof"))) return null;
            return "jk engine: the previous engine (pid " + pid + ") exited on OutOfMemoryError with "
                    + (live.isEmpty() ? "no job live" : "these jobs live: " + String.join("; ", live))
                    + (queued > 0 ? "; " + queued + " queued" : "")
                    + (max > 0 ? "; heap " + mib(used) + " of " + mib(max) + " at the last admission" : "");
        } catch (IOException | RuntimeException unreadable) {
            Log.debug("live jobs: " + file + " unreadable", unreadable);
            return null;
        } finally {
            try {
                Files.deleteIfExists(file);
            } catch (IOException kept) {
                Log.debug("live jobs: " + file + " not removed", kept);
            }
        }
    }

    private static String mib(long bytes) {
        return String.format(Locale.ROOT, "%d MiB", Math.max(0, bytes) >> 20);
    }

    private static String word(String s) {
        return s == null || s.isBlank() ? "-" : s.replace(' ', '_').replace('\n', '_');
    }

    private static String line(String s) {
        return s == null ? "" : s.replace('\n', ' ').replace('\r', ' ');
    }
}
