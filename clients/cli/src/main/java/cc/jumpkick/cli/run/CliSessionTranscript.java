// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.plugin.protocol.Jsonl;
import cc.jumpkick.run.PipelineResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Incremental CLI session transcript under {@code <project>/target/.jk-cli/<ts>/details.jsonl}
 * (JK-1116). Same event shape as {@code --output json}/{@code jsonl} ({@link JsonlShape}, schema
 * 1), appended live so agents/CI can {@code tail -F} mid-run. Flush cadence: JK-1118.
 *
 * <p>Disk materialize is <strong>line-bounded</strong>: only complete newline-terminated records
 * leave the pending buffer on flush. Partial lines never hit the file mid-write — incomplete
 * records stay buffered until a later flush turn (or {@link #finish}).
 *
 * <p>Never throws into the user command path: open/append/finish failures are silent no-ops. Disable
 * with {@code JK_CLI_DETAILS=off} (or {@code 0}).
 *
 * <p>The active session (if any) is held in a volatile for dual-write from {@link JsonlListener},
 * workspace {@code emitJsonl}, and {@link SessionMirrorListener}.
 */
public final class CliSessionTranscript {

    /**
     * Stay on {@code 1} until jk <strong>1.0</strong> — no pre-release schema churn (see
     * {@code docs/architecture.md} schema freeze). Additive fields only.
     */
    public static final int SCHEMA = 1;

    public static final String REL_ROOT = "target/.jk-cli";
    /** Canonical live session log (replaces end-only {@code details.json}). */
    public static final String FILE_NAME = "details.jsonl";
    private static final String ENV = "JK_CLI_DETAILS";

    private static final DateTimeFormatter DIR_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmmss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** Active session for dual-write; cleared on finish. */
    private static volatile CliSessionTranscript active;

    private final Path file;
    private final Instant started;
    private final String command;
    private final List<String> argv;
    private final List<String> modules = new ArrayList<>();
    private final Object lock = new Object();
    /** Unbuffered (or lightly buffered) file stream — we own record framing. */
    private OutputStream out;
    /**
     * Complete records only ({@code …\n} each). Flushed as a unit so readers never observe a partial
     * JSON line on disk.
     */
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream(4096);
    private long lastFlushMs;
    private boolean dirty;
    private String wedgeSummary;
    private boolean closed;

    private CliSessionTranscript(Path file, Instant started, String command, List<String> argv, OutputStream out) {
        this.file = file;
        this.started = started;
        this.command = command;
        this.argv = List.copyOf(argv);
        this.out = out;
        this.lastFlushMs = System.currentTimeMillis();
    }

    /** Currently open session, or {@code null}. */
    public static CliSessionTranscript active() {
        return active;
    }

    /**
     * Open a transcript session under {@code projectDir}. Returns {@code null} when disabled, the
     * project path is unusable, or directory creation fails. Writes a {@code session-start} line
     * immediately (flush) so a crash still leaves a partial file.
     */
    public static CliSessionTranscript open(Path projectDir, String command, List<String> argv) {
        if (projectDir == null || command == null || command.isBlank()) return null;
        if (disabled()) return null;
        try {
            Instant started = Instant.now();
            Path dir = projectDir.toAbsolutePath().normalize().resolve(REL_ROOT).resolve(DIR_TS.format(started));
            Files.createDirectories(dir);
            Path file = dir.resolve(FILE_NAME);
            List<String> args = argv == null || argv.isEmpty() ? List.of(command) : List.copyOf(argv);
            // Raw stream: no BufferedWriter auto-flush mid-line when the internal buffer fills.
            OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            CliSessionTranscript session = new CliSessionTranscript(file, started, command, args, out);
            LiveProgress.get().clear();
            active = session;
            // session-start is metadata — no progress yet (null rider).
            session.appendRaw(JsonlShape.withProgress(JsonlShape.sessionStart(command, args), null), true);
            return session;
        } catch (RuntimeException | IOException e) {
            return null;
        }
    }

    /** Same as {@link #open(Path, String, List)} with argv = {@code [command]}. */
    public static CliSessionTranscript open(Path projectDir, String command) {
        return open(projectDir, command, List.of(command));
    }

    static boolean disabled() {
        String env = System.getenv(ENV);
        return env != null && (env.isBlank() || "off".equalsIgnoreCase(env) || "0".equals(env));
    }

    public Path file() {
        return file;
    }

    public CliSessionTranscript modules(Iterable<String> coords) {
        if (coords == null) return this;
        for (String c : coords) {
            if (c != null && !c.isBlank()) modules.add(c);
        }
        return this;
    }

    public CliSessionTranscript module(String coord) {
        if (coord != null && !coord.isBlank()) modules.add(coord);
        return this;
    }

    /** Settled wedge / summary line (plain text preferred; ANSI is fine — not re-rendered). */
    public CliSessionTranscript wedge(String summary) {
        if (summary != null && !summary.isBlank()) this.wedgeSummary = summary;
        return this;
    }

    /**
     * Acknowledge a finished pipeline. Step/error events should already be in the JSONL stream via
     * {@link SessionMirrorListener} or {@link JsonlListener} — this does not re-emit them.
     */
    public CliSessionTranscript absorb(PipelineResult result) {
        // Reserved for future summary fields; pipeline events already dual-written.
        return this;
    }

    public CliSessionTranscript error(String message) {
        return error("", "", message);
    }

    /**
     * Record a command-level error as a JSONL {@code error} event (immediate flush). Prefer pipeline
     * listeners for step diagnostics so events are not duplicated.
     */
    public CliSessionTranscript error(String step, String code, String message) {
        if (message == null || message.isBlank()) return this;
        String s = step == null ? "" : step;
        String c = code == null ? "" : code;
        append(JsonlShape.error(s, c, message), true);
        return this;
    }

    /**
     * Append one {@link JsonlShape} line (progress rider applied). {@code immediateFlush} is true for
     * M1–M3 semantic boundaries; false for hot ticks (flush ≤ {@link LiveProgress#DISK_HEARTBEAT_MS}).
     */
    public void append(String line, boolean immediateFlush) {
        if (line == null || line.isBlank()) return;
        appendRaw(JsonlShape.withProgress(line), immediateFlush);
    }

    /**
     * Append a fully-formed JSON object as one record. Strips any trailing CR/LF from {@code line},
     * then enqueues exactly one {@code line + '\n'} into the pending buffer. Disk flush only writes
     * complete pending records — never a partial line.
     */
    public void appendRaw(String line, boolean immediateFlush) {
        if (line == null || line.isBlank()) return;
        // Normalize: callers may pass a line that already ends with \n.
        String record = line;
        while (!record.isEmpty() && (record.charAt(record.length() - 1) == '\n' || record.charAt(record.length() - 1) == '\r')) {
            record = record.substring(0, record.length() - 1);
        }
        if (record.isEmpty()) return;
        byte[] bytes = (record + "\n").getBytes(StandardCharsets.UTF_8);
        synchronized (lock) {
            if (closed || out == null) return;
            try {
                pending.write(bytes);
                dirty = true;
                long now = System.currentTimeMillis();
                if (immediateFlush || now - lastFlushMs >= LiveProgress.DISK_HEARTBEAT_MS) {
                    flushPending();
                }
            } catch (IOException ignored) {
                // Best-effort; leave pending for a later attempt or finish.
            }
        }
    }

    /**
     * Write every complete pending record to the file, then flush the OS stream. Empty pending is a
     * no-op. Never writes a non-newline-terminated fragment.
     */
    private void flushPending() throws IOException {
        if (out == null || pending.size() == 0) return;
        out.write(pending.toByteArray());
        out.flush();
        pending.reset();
        lastFlushMs = System.currentTimeMillis();
        dirty = false;
    }

    /**
     * Dual-write helper for stdout JSONL / workspace emit paths. No-op when no active session.
     * Flush policy inferred from the event {@code type} field when present.
     */
    public static void appendActive(String line) {
        CliSessionTranscript s = active;
        if (s == null || line == null || line.isBlank()) return;
        s.append(line, isImmediateType(line));
    }

    /** Semantic events flush immediately (M1–M3); hot ticks use the 2s heartbeat (M4/M5). */
    static boolean isImmediateType(String line) {
        // Cheap substring checks — avoid full JSON parse on the hot path.
        if (line.contains("\"type\":\"progress\"")
                || line.contains("\"type\":\"tick-update\"")
                || line.contains("\"type\":\"workspace-progress\"")
                || line.contains("\"type\":\"label\"")
                || line.contains("\"type\":\"output\"")) {
            return false;
        }
        return true;
    }

    /**
     * Write {@code session-finish}, flush all pending complete records, and close. Never throws.
     * Returns the path written, or empty on failure.
     */
    public Optional<Path> finish(int exitCode) {
        synchronized (lock) {
            if (closed) return Optional.of(file);
            try {
                Instant finished = Instant.now();
                long durationMs = Duration.between(started, finished).toMillis();
                if (exitCode == 0) LiveProgress.get().setPercent(100.0);
                String finishLine = JsonlShape.withProgress(JsonlShape.sessionFinish(
                        exitCode, durationMs, wedgeSummary == null ? null : stripAnsi(wedgeSummary), List.copyOf(modules)));
                // Enqueue finish as a complete record, then drain pending.
                if (out != null) {
                    String record = finishLine;
                    while (!record.isEmpty()
                            && (record.charAt(record.length() - 1) == '\n'
                                    || record.charAt(record.length() - 1) == '\r')) {
                        record = record.substring(0, record.length() - 1);
                    }
                    pending.write((record + "\n").getBytes(StandardCharsets.UTF_8));
                    dirty = true;
                    flushPending();
                }
                return Optional.of(file);
            } catch (RuntimeException | IOException e) {
                return Optional.empty();
            } finally {
                closed = true;
                if (active == this) active = null;
                if (out != null) {
                    try {
                        out.close();
                    } catch (IOException ignored) {
                    }
                    out = null;
                }
            }
        }
    }

    /**
     * Finish {@code session} (if non-null) and optionally print a one-line path on verbose. Always
     * returns {@code exit} so callers can {@code return finish(session, code, verbose)}.
     */
    public static int finish(CliSessionTranscript session, int exit, boolean verbose) {
        if (session == null) return exit;
        session.finish(exit).ifPresent(p -> {
            if (verbose) announceWritten(p);
        });
        return exit;
    }

    /**
     * One-line discoverability on stderr. Prefer announcing at open when verbose so mid-run
     * consumers know the path; finish may re-print.
     */
    public static void announceWritten(Path file) {
        if (file == null) return;
        System.err.println("Details: " + file + "  (session JSONL; disable: JK_CLI_DETAILS=off)");
    }

    /** Announce path when a session opens (verbose callers). */
    public void announceIf(boolean verbose) {
        if (verbose) announceWritten(file);
    }

    /** Drop common SGR sequences so wedge text is greppable in JSON. */
    static String stripAnsi(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.replaceAll("\\u001B\\[[0-9;]*m", "");
    }

    /** Package-visible JSON string escape for tests — delegates to shared codec. */
    static String quote(String s) {
        return Jsonl.quote(s);
    }
}
