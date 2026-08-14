// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Chrome Trace Event writer for one engine request (single module or workspace). The engine owns
 * this so every client (CLI, web, IDE) gets the same per-run timeline without re-implementing
 * capture; write moved off the CLI).
 *
 * <p>Default path: {@code <project>/target/jk-chrome-profile.json} (canonical build output dir).
 * Disable with request {@code noTimeline=true} or env {@code JK_CHROME_PROFILE=off}. Override path
 * with {@code JK_CHROME_PROFILE=<file>}.
 *
 * <p>Spans use the plan's measured {@link java.time.Duration} (same numbers as {@link
 * BuildMetrics}), placed with {@link System#nanoTime} at step finish so concurrent modules
 * align on one clock.
 */
public final class ChromeTimeline {

    static final String ENV = "JK_CHROME_PROFILE";
    /** Under {@link cc.jumpkick.layout.BuildLayout}'s module output root ({@code target/}). */
    static final String DEFAULT_REL = "target/jk-chrome-profile.json";

    private final Path file;
    /**
     * Appends dominate (one per step) and reads happen only at {@link #flush}, so a synchronized
     * ArrayList beats {@code CopyOnWriteArrayList}, whose per-add full-array copy makes recording
     * O(n²) — a 50-module × 10-step build costs ~125k element copies (JK-1484).
     */
    private final List<Event> events = Collections.synchronizedList(new ArrayList<>());

    private final Map<String, Integer> tids = new ConcurrentHashMap<>();
    private final AtomicInteger nextTid = new AtomicInteger(1);

    private ChromeTimeline(Path file) {
        this.file = file;
    }

    /**
     * Open a session for {@code projectDir}, or {@code null} when disabled / path unusable. Never
     * throws.
     *
     * @param projectDir entry/workspace root (timeline file under its {@code target/})
     * @param noTimeline request flag (CLI {@code --no-timeline})
     */
    public static ChromeTimeline open(Path projectDir, boolean noTimeline) {
        if (noTimeline || projectDir == null) return null;
        String env = System.getenv(ENV);
        if (env != null && (env.isBlank() || "off".equalsIgnoreCase(env) || "0".equals(env))) {
            return null;
        }
        try {
            Path file = (env != null && !env.isBlank())
                    ? Path.of(env).toAbsolutePath().normalize()
                    : projectDir.resolve(DEFAULT_REL).toAbsolutePath().normalize();
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            return new ChromeTimeline(file);
        } catch (RuntimeException | IOException e) {
            return null;
        }
    }

    /** Convenience when timeline is enabled. */
    public static ChromeTimeline open(Path projectDir) {
        return open(projectDir, false);
    }

    public Path file() {
        return file;
    }

    /**
     * Record a complete span ({@code ph:X}) using the plan duration and finish-time nano clock.
     *
     * @param module track label (coord or module path)
     * @param step step name
     * @param status SUCCESS / SKIPPED / FAIL / …
     * @param durationMillis plan wall time for this step (same as metrics)
     */
    public void complete(String module, String step, String status, long durationMillis) {
        long end = System.nanoTime();
        long durNanos = Math.max(0L, durationMillis) * 1_000_000L;
        completeNanos(module, step, status, end - durNanos, end);
    }

    /** Absolute nano times (wall); origin is min start across events at flush. */
    void completeNanos(String module, String step, String status, long startNanos, long endNanos) {
        long start = startNanos;
        long end = Math.max(startNanos, endNanos);
        int tid =
                tids.computeIfAbsent(module == null || module.isBlank() ? "_" : module, k -> nextTid.getAndIncrement());
        events.add(new Event(module, step, status, start, end, tid));
    }

    /** Best-effort write; never throws. */
    public Optional<Path> flush() {
        try {
            // A synchronizedList's copy constructor iterates without the lock — hold it here or a
            // step completing mid-flush throws ConcurrentModificationException.
            List<Event> snapshot;
            synchronized (events) {
                snapshot = new ArrayList<>(events);
            }
            long origin = snapshot.stream().mapToLong(e -> e.startNanos).min().orElse(0L);
            StringBuilder sb = new StringBuilder(256 + snapshot.size() * 96);
            sb.append("[\n");
            for (int i = 0; i < snapshot.size(); i++) {
                Event ev = snapshot.get(i);
                long tsUs = Math.max(0L, (ev.startNanos - origin) / 1000L);
                long durUs = Math.max(0L, (ev.endNanos - ev.startNanos) / 1000L);
                if (i > 0) sb.append(",\n");
                sb.append("  {\"name\":")
                        .append(json(ev.step))
                        .append(",\"cat\":")
                        .append(json(ev.module))
                        .append(",\"ph\":\"X\",\"ts\":")
                        .append(tsUs)
                        .append(",\"dur\":")
                        .append(durUs)
                        .append(",\"pid\":1,\"tid\":")
                        .append(ev.tid)
                        .append(",\"args\":{\"status\":")
                        .append(json(ev.status))
                        .append("}}");
            }
            sb.append("\n]\n");
            AtomicWrites.replace(file, sb.toString());
            return Optional.of(file);
        } catch (RuntimeException | IOException e) {
            return Optional.empty();
        }
    }

    private static String json(String s) {
        if (s == null) return "\"\"";
        StringBuilder b = new StringBuilder(s.length() + 2);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '"' -> b.append("\\\"");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        b.append('"');
        return b.toString();
    }

    private record Event(String module, String step, String status, long startNanos, long endNanos, int tid) {}
}
