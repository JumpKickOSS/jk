// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.engine.plugin.JobWorkers;
import cc.jumpkick.host.HostTemp;
import cc.jumpkick.jdk.JdkFingerprint;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;
import org.jspecify.annotations.Nullable;

/**
 * The fallback for a recording {@link RecordingReader} declines: the JDK's own parser, in a child
 * JVM of the engine's JDK with a heap of its own, never in the engine's. The child writes each wanted
 * event as a line to a file the engine then streams; a child that runs out of its heap, fails or
 * passes {@link #TIMEOUT_SECONDS} leaves the recording unread, with the reason.
 *
 * <p>The child is a jk worker ({@link JobWorkers#start}): its heap is leased from the host's memory
 * like any other worker's, so it waits its turn rather than overbooking the machine.
 */
public final class RecordingDump {

    /** The child's heap: enough for a chunk of 15 MB carrying tens of thousands of stack traces. */
    static final String HEAP = "-Xmx1g";

    /** How long the child may take. */
    static final long TIMEOUT_SECONDS = 600;

    private static final String NULL = "\\0";

    private RecordingDump() {}

    /** As {@link #dump} then {@link #stream}: the JDK's parser's reading of {@code jfr}, given to {@code sink}. */
    public static void read(Path jfr, Map<String, List<String>> wanted, RecordingReader.Sink sink) throws IOException {
        Path out = dump(jfr, wanted);
        try {
            stream(out, wanted, sink);
        } finally {
            Files.deleteIfExists(out);
        }
    }

    /**
     * Run the JDK's parser over {@code jfr} in a child JVM; the file of its lines, which the caller
     * streams ({@link #stream}) and deletes. Throws with the reason when the child could not read it.
     */
    public static Path dump(Path jfr, Map<String, List<String>> wanted) throws IOException {
        Path out = Files.createTempFile(HostTemp.dir(), "jk-recording-", ".tsv");
        Path log = Files.createTempFile(HostTemp.dir(), "jk-recording-", ".log");
        boolean ok = false;
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(JdkFingerprint.java(Path.of(System.getProperty("java.home")))
                    .toString());
            cmd.add(HEAP);
            cmd.add("-XX:+ExitOnOutOfMemoryError");
            cmd.add("-XX:TieredStopAtLevel=1");
            cmd.add("-cp");
            cmd.add(System.getProperty("java.class.path"));
            cmd.add(RecordingDump.class.getName());
            cmd.add(jfr.toAbsolutePath().toString());
            cmd.add(out.toString());
            for (Map.Entry<String, List<String>> e : wanted.entrySet()) {
                cmd.add(e.getKey() + "=" + String.join(",", e.getValue()));
            }
            ProcessBuilder pb =
                    new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile());
            Process child = JobWorkers.start(pb);
            boolean done;
            try {
                done = child.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                child.destroyForcibly();
                throw new IOException("the recording's reader was interrupted");
            }
            if (!done) {
                child.destroyForcibly();
                throw new IOException("the JDK's parser took longer than " + TIMEOUT_SECONDS + " s");
            }
            int exit = child.exitValue();
            if (exit == 3) throw new IOException("the JDK's parser ran out of its " + HEAP.substring(5) + " heap");
            if (exit != 0) throw new IOException("the JDK's parser failed (exit " + exit + ")" + lastLine(log));
            ok = true;
            return out;
        } finally {
            if (!ok) Files.deleteIfExists(out);
            Files.deleteIfExists(log);
        }
    }

    private static String lastLine(Path out) {
        try (BufferedReader in = Files.newBufferedReader(out, StandardCharsets.UTF_8)) {
            String last = null;
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                if (!line.isBlank()) last = line;
            }
            return last == null ? "" : ": " + last.strip();
        } catch (IOException | RuntimeException unreadable) {
            return "";
        }
    }

    /** The child's lines: {@code E}, the event's name, its start and end in epoch nanos, then each field. */
    static void stream(Path out, Map<String, List<String>> wanted, RecordingReader.Sink sink) throws IOException {
        boolean finished = false;
        try (BufferedReader in = Files.newBufferedReader(out, StandardCharsets.UTF_8)) {
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                if (line.equals("END")) {
                    finished = true;
                    continue;
                }
                if (!line.startsWith("E\t")) continue;
                String[] cells = line.split("\t", -1);
                if (cells.length < 4) throw new IOException("a malformed line from the JDK's parser");
                List<String> fields = wanted.get(cells[1]);
                if (fields == null) continue;
                @Nullable String[] values = new String[fields.size()];
                for (int i = 0; i < values.length && 4 + i < cells.length; i++) values[i] = unescape(cells[4 + i]);
                sink.event(cells[1], Long.parseLong(cells[2]), Long.parseLong(cells[3]), values);
            }
        }
        if (!finished) throw new IOException("the JDK's parser stopped before the recording's end");
    }

    static String escape(@Nullable String s) {
        if (s == null) return NULL;
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    static @Nullable String unescape(String s) {
        if (s.equals(NULL)) return null;
        if (s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 == s.length()) {
                sb.append(c);
                continue;
            }
            char n = s.charAt(++i);
            sb.append(
                    switch (n) {
                        case 't' -> '\t';
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        default -> n;
                    });
        }
        return sb.toString();
    }

    /**
     * As {@link RecordingReader} renders a field: strings as they are, characters by code, a thread by
     * its id, the rest in decimal.
     */
    static @Nullable String render(RecordedEvent e, String field) {
        if (!e.hasField(field)) return null;
        Object v = e.getValue(field);
        if (v == null) return null;
        if (v instanceof String s) return s;
        if (v instanceof Character c) return Integer.toString(c);
        if (v instanceof Boolean || v instanceof Number) return v.toString();
        if (v instanceof RecordedThread t) return Long.toString(t.getId());
        return null;
    }

    private static long nanos(Instant t) {
        return t.getEpochSecond() * 1_000_000_000L + t.getNano();
    }

    /** The child: {@code <recording> <lines file> <event>=<field>,<field> ...}. */
    public static void main(String[] args) throws IOException {
        Map<String, List<String>> wanted = new LinkedHashMap<>();
        for (int i = 2; i < args.length; i++) {
            int eq = args[i].indexOf('=');
            wanted.put(
                    args[i].substring(0, eq), List.of(args[i].substring(eq + 1).split(",", -1)));
        }
        try (BufferedWriter w = Files.newBufferedWriter(Path.of(args[1]), StandardCharsets.UTF_8);
                RecordingFile in = new RecordingFile(Path.of(args[0]))) {
            while (in.hasMoreEvents()) {
                RecordedEvent e = in.readEvent();
                String name = e.getEventType().getName();
                List<String> fields = wanted.get(name);
                if (fields == null) continue;
                w.append("E\t").append(name).append('\t');
                w.append(Long.toString(nanos(e.getStartTime()))).append('\t');
                w.append(Long.toString(nanos(e.getEndTime())));
                for (String f : fields) w.append('\t').append(escape(render(e, f)));
                w.append('\n');
            }
            w.append("END\n");
        }
    }
}
