// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Log;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Peak heap of a worker. The GC log's largest before-occupancy ({@code 128M->40M}) misses a spike
 * between collections, so the process high-water RSS minus {@link #NON_HEAP_RSS} is kept too, and
 * the larger one is the peak.
 */
public final class WorkerGc {

    /**
     * RSS that is not the Java heap (metaspace, code, threads, native), taken off {@code VmHWM}.
     * What remains is treated as heap.
     */
    static final long NON_HEAP_RSS = 96L << 20;

    static final String FLAG_PREFIX = "-Xlog:gc:file=";

    /**
     * A size immediately before {@code ->} in a GC pause line. Region counts ({@code 24->0}) have
     * no unit and do not match. ZGC writes {@code 16M(2%)->}.
     */
    private static final Pattern BEFORE =
            Pattern.compile("(\\d+(?:\\.\\d+)?)([KMGT])(?:\\([^)]*\\))?->", Pattern.CASE_INSENSITIVE);

    private WorkerGc() {}

    /** Largest before-occupancy in {@code text}, or {@code 0} when no collection was logged. */
    public static long peak(@Nullable String text) {
        if (text == null || text.isEmpty()) return 0L;
        Matcher m = BEFORE.matcher(text);
        long max = 0L;
        while (m.find()) {
            long bytes = bytes(m.group(1), m.group(2));
            if (bytes > max) max = bytes;
        }
        return max;
    }

    /** {@link #peak(String)} of {@code log}, or {@code 0} when the file is missing or empty. */
    public static long peak(Path log) {
        if (log == null || !Files.isRegularFile(log)) return 0L;
        try {
            return peak(Files.readString(log));
        } catch (IOException e) {
            Log.debug("worker gc log " + log + ": " + e.getMessage());
            return 0L;
        }
    }

    /** A log file under the state temp dir, created empty. */
    public static Path create() throws IOException {
        Path dir = JkDirs.tmp().resolve("worker-gc");
        Files.createDirectories(dir);
        return Files.createTempFile(dir, "gc-", ".log");
    }

    public static String flag(Path log) {
        return FLAG_PREFIX + log.toAbsolutePath();
    }

    static boolean logged(List<String> command) {
        if (command == null) return false;
        for (String arg : command) {
            if (arg != null && arg.startsWith(FLAG_PREFIX)) return true;
        }
        return false;
    }

    /**
     * Append a GC log to a jk-planned JVM command and remember the {@link HeapScope} to record
     * when the process exits. A no-op for a user pin, a non-JVM, a command that already logs, or
     * a thread with no scope.
     */
    static Watch watch(List<String> command, JvmOptions.HeapChoice choice, LearnedHeaps heaps) {
        int javaAt = javaIndex(command);
        if (command == null
                || command.isEmpty()
                || choice == null
                || choice.userPinned()
                || heaps == null
                || javaAt < 0
                || logged(command)) {
            return Watch.none(command);
        }
        HeapScope.Key key = HeapScope.get();
        if (key == null || key.module().isBlank()) return Watch.none(command);
        try {
            Path log = create();
            List<String> next = new ArrayList<>(command.size() + 1);
            next.addAll(command.subList(0, javaAt + 1));
            next.add(flag(log));
            next.addAll(command.subList(javaAt + 1, command.size()));
            return new Watch(List.copyOf(next), log, key, heaps);
        } catch (IOException e) {
            Log.debug("worker gc log: " + e.getMessage());
            return Watch.none(command);
        }
    }

    /** Index of the {@code java} binary, after a {@code setsid} wrapper when one is present. */
    private static int javaIndex(List<String> command) {
        if (command == null) return -1;
        for (int i = 0; i < command.size(); i++) {
            if (WorkerLeases.jvmExecutable(command.get(i))) return i;
        }
        return -1;
    }

    private static long bytes(String number, String unit) {
        double n;
        try {
            n = Double.parseDouble(number);
        } catch (NumberFormatException e) {
            return 0L;
        }
        long mul =
                switch (Character.toUpperCase(unit.charAt(0))) {
                    case 'K' -> 1024L;
                    case 'M' -> 1024L << 10;
                    case 'G' -> 1024L << 20;
                    case 'T' -> 1024L << 30;
                    default -> 1L;
                };
        return (long) (n * mul);
    }

    /** {@code VmHWM} from a {@code /proc/pid/status} body, in bytes, or {@code 0}. */
    static long vmHwmBytes(@Nullable String status) {
        if (status == null || status.isEmpty()) return 0L;
        for (String line : status.split("\n", -1)) {
            if (!line.startsWith("VmHWM:")) continue;
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 2) return 0L;
            try {
                return Long.parseLong(parts[1]) * 1024L;
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
        return 0L;
    }

    private static long vmHwm(Path status) {
        try {
            return vmHwmBytes(Files.readString(status));
        } catch (IOException e) {
            return 0L;
        }
    }

    /** The command to exec, and the log to fold in when the process has exited. */
    static final class Watch {
        private final List<String> command;
        private final @Nullable Path log;
        private final HeapScope.@Nullable Key key;
        private final @Nullable LearnedHeaps heaps;
        private final AtomicBoolean done = new AtomicBoolean();
        private final AtomicLong rssPeak = new AtomicLong();
        private @Nullable Thread sampler;

        private Watch(
                List<String> command, @Nullable Path log, HeapScope.@Nullable Key key, @Nullable LearnedHeaps heaps) {
            this.command = command;
            this.log = log;
            this.key = key;
            this.heaps = heaps;
        }

        static Watch none(List<String> command) {
            return new Watch(command, null, null, null);
        }

        List<String> command() {
            return command;
        }

        /** Sample {@code process}'s VmHWM until it exits. No-op when this watch records nothing. */
        void observe(Process process) {
            if (process == null || log == null || key == null) return;
            long pid = process.pid();
            sampler = SessionContext.startVirtual("jk-worker-rss", () -> sample(process, pid));
        }

        private void sample(Process process, long pid) {
            Path status = Path.of("/proc", Long.toString(pid), "status");
            while (process.isAlive() && !Thread.currentThread().isInterrupted()) {
                rssPeak.accumulateAndGet(vmHwm(status), Math::max);
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    break;
                }
            }
            rssPeak.accumulateAndGet(vmHwm(status), Math::max);
        }

        /** Record the peak for {@link #key} and delete the log. Safe to call twice. */
        void finish() {
            if (log == null || key == null || !done.compareAndSet(false, true)) return;
            Thread sampling = sampler;
            if (sampling != null) {
                sampling.interrupt();
                try {
                    sampling.join(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            try {
                long fromRss = 0;
                long rss = rssPeak.get();
                if (rss > NON_HEAP_RSS) fromRss = rss - NON_HEAP_RSS;
                long seen = Math.max(peak(log), fromRss);
                if (seen > 0 && heaps != null) heaps.note(key, seen);
            } finally {
                try {
                    Files.deleteIfExists(log);
                } catch (IOException e) {
                    Log.debug("worker gc log delete: " + e.getMessage());
                }
            }
        }
    }

    /** Read a whole log as UTF-8. Exposed for the parser tests via {@link #peak(String)}. */
    static String read(Path log) throws IOException {
        return Files.readString(log, StandardCharsets.UTF_8);
    }
}
