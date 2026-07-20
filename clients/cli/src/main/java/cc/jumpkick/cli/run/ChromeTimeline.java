// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared Chrome Trace Event writer for one invocation (single module or whole workspace). Thread-safe
 * event append; {@link #flush()} writes a loadable JSON array for Perfetto / {@code chrome://tracing}.
 *
 * <p>Disable with {@code JK_CHROME_PROFILE=off}. Override path with {@code JK_CHROME_PROFILE=<file>}.
 * Default path: {@code <project>/out/jk-chrome-profile.json}.
 */
public final class ChromeTimeline {

    private static final String ENV = "JK_CHROME_PROFILE";
    private static final String DEFAULT_REL = "out/jk-chrome-profile.json";

    private final Path file;
    private final long originNanos;
    private final List<Event> events = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> tids = new ConcurrentHashMap<>();
    private final AtomicInteger nextTid = new AtomicInteger(1);

    private ChromeTimeline(Path file) {
        this.file = file;
        this.originNanos = System.nanoTime();
    }

    /**
     * Open a session for {@code projectDir}, or {@code null} when disabled / path unusable.
     * Never throws.
     */
    public static ChromeTimeline open(Path projectDir) {
        if (projectDir == null) return null;
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

    public Path file() {
        return file;
    }

    /** Record a complete span ({@code ph:X}) for one step. */
    public void complete(String module, String step, String status, long startNanos, long endNanos) {
        long s = Math.max(0, startNanos - originNanos);
        long e = Math.max(s, endNanos - originNanos);
        int tid = tids.computeIfAbsent(module == null || module.isBlank() ? "_" : module, k -> nextTid.getAndIncrement());
        events.add(new Event(module, step, status, s / 1000L, Math.max(0, (e - s) / 1000L), tid));
    }

    /** Best-effort write; never throws. Returns the path written, or empty. */
    public java.util.Optional<Path> flush() {
        try {
            List<Event> snapshot = new ArrayList<>(events);
            StringBuilder sb = new StringBuilder(256 + snapshot.size() * 96);
            sb.append("[\n");
            for (int i = 0; i < snapshot.size(); i++) {
                Event ev = snapshot.get(i);
                if (i > 0) sb.append(",\n");
                sb.append("  {\"name\":")
                        .append(json(ev.step))
                        .append(",\"cat\":")
                        .append(json(ev.module))
                        .append(",\"ph\":\"X\",\"ts\":")
                        .append(ev.tsUs)
                        .append(",\"dur\":")
                        .append(ev.durUs)
                        .append(",\"pid\":1,\"tid\":")
                        .append(ev.tid)
                        .append(",\"args\":{\"status\":")
                        .append(json(ev.status))
                        .append("}}");
            }
            sb.append("\n]\n");
            AtomicWrites.replace(file, sb.toString());
            return java.util.Optional.of(file);
        } catch (RuntimeException | IOException e) {
            return java.util.Optional.empty();
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

    private record Event(String module, String step, String status, long tsUs, long durUs, int tid) {}
}
