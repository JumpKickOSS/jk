// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.run.BuildPlanResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Incremental CLI session transcript as {@code details.jsonl} under the project run dir
 * ({@code ~/.local/state/jk/builds/projects/&lt;key&gt;/runs/&lt;id&gt;/details.jsonl}). Same event shape as
 * {@code --output json}/{@code jsonl} ({@link JsonlShape}, schema 1), appended live so agents can
 * {@code tail -F} mid-run.
 *
 * <p>On open the session buffers until {@link #bindJob} (from engine {@code job-start}) points at the
 * journal run's details path. If the command never receives job-start (failure before admit), a
 * local run dir is created at {@link #finish} so the transcript is still retained.
 *
 * <p>Never throws into the user command path: open/append/finish failures are silent no-ops. Disable
 * with {@code JK_CLI_DETAILS=off} (or {@code 0}).
 */
public final class CliSessionTranscript {

    /**
     * Stay on {@code 1} until jk <strong>1.0</strong> — no pre-release schema churn (see
     * {@code docs/architecture.md} schema freeze). Additive fields only.
     */
    public static final int SCHEMA = 1;

    /** @deprecated details live under project runs; kept for tests that assert the constant. */
    @Deprecated
    public static final String REL_ROOT = "state/builds/projects";

    public static final String FILE_NAME = "details.jsonl";

    private static final String ENV = "JK_CLI_DETAILS";

    /** Active session for dual-write; cleared on finish. */
    private static volatile CliSessionTranscript active;

    private final Path projectDir;
    private final Instant started;
    private final String command;
    private final List<String> argv;
    private final List<String> modules = new ArrayList<>();
    private final Object lock = new Object();
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream(4096);

    private Path file;
    private OutputStream out;
    private long lastFlushMs;
    private String wedgeSummary;
    private boolean closed;
    private boolean bound;

    private long jid = -1;
    private long buildNumber;
    private long etaMs = -1;

    private CliSessionTranscript(Path projectDir, Instant started, String command, List<String> argv) {
        this.projectDir = projectDir;
        this.started = started;
        this.command = command;
        this.argv = List.copyOf(argv);
        this.lastFlushMs = System.currentTimeMillis();
    }

    public static CliSessionTranscript active() {
        return active;
    }

    /**
     * Open a transcript session for {@code projectDir}. Returns {@code null} when disabled or the
     * project path is unusable. Writes a {@code session-start} line into the buffer immediately.
     */
    public static CliSessionTranscript open(Path projectDir, String command, List<String> argv) {
        if (projectDir == null || command == null || command.isBlank()) return null;
        if (disabled()) return null;
        try {
            Instant started = Instant.now();
            List<String> args = argv == null || argv.isEmpty() ? List.of(command) : List.copyOf(argv);
            CliSessionTranscript session = new CliSessionTranscript(projectDir, started, command, args);
            LiveProgress.get().clear();
            active = session;
            session.appendRaw(JsonlShape.withProgress(JsonlShape.sessionStart(command, args), null), true);
            return session;
        } catch (RuntimeException e) {
            return null;
        }
    }

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

    public Path projectDir() {
        return projectDir;
    }

    /**
     * Bind to the engine journal run (from {@code job-start}). Opens {@code detailsPath} (under
     * {@code runs/<buildNumber>/}) and flushes buffered events. Emits a {@code job} metadata line
     * with jid / buildNumber / ETA when known.
     */
    public void bindJob(long jid, long buildNumber, String detailsPath, long etaMs) {
        synchronized (lock) {
            if (closed) return;
            this.jid = jid;
            this.buildNumber = buildNumber;
            this.etaMs = etaMs;
            try {
                if (detailsPath != null && !detailsPath.isBlank()) {
                    openFile(Path.of(detailsPath));
                } else if (buildNumber > 0 && projectDir != null) {
                    // Fallback: resolve runs/<N>/details.jsonl for this project under the live builds root.
                    try {
                        String coord = coordOf(projectDir);
                        Path run = ProjectBuilds.runDir(ProjectBuilds.buildsRoot(), coord, projectDir, buildNumber);
                        openFile(run.resolve(ProjectBuilds.DETAILS));
                    } catch (IOException ignored) {
                    }
                }
                String jobLine = JsonlShape.jobMeta(jid, buildNumber, etaMs, detailsPath);
                if (jobLine != null) {
                    enqueueRecord(jobLine);
                    flushPending();
                }
            } catch (IOException | RuntimeException ignored) {
            }
        }
    }

    /** Note ETA once known (explain / plan phase); appends an {@code eta} event when bound. */
    public void noteEta(long etaMs) {
        if (etaMs < 0) return;
        synchronized (lock) {
            this.etaMs = etaMs;
            appendRaw(JsonlShape.withProgress(JsonlShape.eta(etaMs), null), true);
        }
    }

    private void openFile(Path detailsFile) throws IOException {
        if (detailsFile == null) return;
        Files.createDirectories(detailsFile.getParent());
        if (out != null) {
            try {
                flushPending();
                out.close();
            } catch (IOException ignored) {
            }
            out = null;
        }
        this.file = detailsFile;
        this.out = Files.newOutputStream(
                detailsFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        this.bound = true;
        // Re-write any pending records (session-start etc.) that arrived before bind.
        flushPending();
    }

    /** Ensure a local run dir exists when finish happens without engine bind (tests / pre-admit fail). */
    private void ensureLocalRun() throws IOException {
        if (bound && out != null) return;
        String coord = coordOf(projectDir);
        ProjectBuilds.RunDir run = ProjectBuilds.openRun(coord, projectDir);
        openFile(run.detailsFile());
    }

    private static String coordOf(Path dir) {
        try {
            Path toml = dir.resolve("jk.toml");
            if (!Files.isRegularFile(toml)) return "unknown:unknown";
            String text = Files.readString(toml, StandardCharsets.UTF_8);
            String group = null, name = null;
            for (String line : text.split("\n")) {
                String t = line.trim();
                if (t.startsWith("group") && t.contains("=")) {
                    group = unquote(t.substring(t.indexOf('=') + 1).trim());
                } else if (t.startsWith("name") && t.contains("=") && !t.startsWith("namespace")) {
                    name = unquote(t.substring(t.indexOf('=') + 1).trim());
                }
            }
            if (group != null && name != null) return group + ":" + name;
        } catch (Exception ignored) {
        }
        return "unknown:unknown";
    }

    private static String unquote(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1);
        }
        return s;
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

    public CliSessionTranscript wedge(String summary) {
        if (summary != null && !summary.isBlank()) this.wedgeSummary = summary;
        return this;
    }

    public CliSessionTranscript absorb(BuildPlanResult result) {
        return this;
    }

    public CliSessionTranscript error(String message) {
        return error("", "", message);
    }

    public CliSessionTranscript error(String step, String code, String message) {
        if (message == null || message.isBlank()) return this;
        String s = step == null ? "" : step;
        String c = code == null ? "" : code;
        append(JsonlShape.error(s, c, message), true);
        return this;
    }

    public void append(String line, boolean immediateFlush) {
        if (line == null || line.isBlank()) return;
        appendRaw(JsonlShape.withProgress(line), immediateFlush);
    }

    public void appendRaw(String line, boolean immediateFlush) {
        if (line == null || line.isBlank()) return;
        String record = stripTrailingNewlines(line);
        if (record.isEmpty()) return;
        synchronized (lock) {
            if (closed) return;
            try {
                enqueueRecord(record);
                long now = System.currentTimeMillis();
                if (immediateFlush || now - lastFlushMs >= LiveProgress.DISK_HEARTBEAT_MS) {
                    flushPending();
                }
            } catch (IOException ignored) {
            }
        }
    }

    private void enqueueRecord(String record) throws IOException {
        byte[] bytes = (record + "\n").getBytes(StandardCharsets.UTF_8);
        pending.write(bytes);
    }

    private void flushPending() throws IOException {
        if (pending.size() == 0) return;
        if (out == null) {
            // Keep buffering until bind/finish opens a file.
            lastFlushMs = System.currentTimeMillis();
            return;
        }
        byte[] records = pending.toByteArray();
        pending.reset();
        lastFlushMs = System.currentTimeMillis();
        out.write(records);
        out.flush();
    }

    private static String stripTrailingNewlines(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) end--;
        return s.substring(0, end);
    }

    public static void appendActive(String line) {
        CliSessionTranscript s = active;
        if (s == null || line == null || line.isBlank()) return;
        s.append(line, isImmediateType(line));
    }

    private static final String[] HOT_TYPE_NEEDLES =
            JsonlShape.HOT_TYPES.stream().map(t -> "\"type\":\"" + t + "\"").toArray(String[]::new);

    static boolean isImmediateType(String line) {
        for (String needle : HOT_TYPE_NEEDLES) {
            if (line.contains(needle)) return false;
        }
        return true;
    }

    public Optional<Path> finish(int exitCode) {
        synchronized (lock) {
            if (closed) return file == null ? Optional.empty() : Optional.of(file);
            try {
                if (!bound || out == null) ensureLocalRun();
                Instant finished = Instant.now();
                long durationMs = Duration.between(started, finished).toMillis();
                if (exitCode == 0) LiveProgress.get().setPercent(100.0);
                String finishLine = JsonlShape.withProgress(JsonlShape.sessionFinish(
                        exitCode,
                        durationMs,
                        wedgeSummary == null ? null : stripAnsi(wedgeSummary),
                        List.copyOf(modules)));
                // Enrich finish with jid / buildNumber when known.
                if ((jid > 0 || buildNumber > 0) && !finishLine.contains("\"jid\":")) {
                    finishLine = finishLine.substring(0, finishLine.length() - 1)
                            + (jid > 0 ? ",\"jid\":" + jid : "")
                            + (buildNumber > 0 ? ",\"buildNumber\":" + buildNumber : "")
                            + "}";
                }
                if (out != null) {
                    enqueueRecord(stripTrailingNewlines(finishLine));
                    flushPending();
                }
                return file == null ? Optional.empty() : Optional.of(file);
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

    public static int finish(CliSessionTranscript session, int exit, boolean verbose) {
        if (session == null) return exit;
        session.finish(exit).ifPresent(p -> {
            if (verbose) {
                announceWritten(p);
                Path results = session.projectDir() == null
                        ? null
                        : session.projectDir().resolve("target").resolve("jk-results.md");
                if (results != null && Files.isRegularFile(results)) {
                    System.err.println("Results: " + results);
                }
            }
        });
        return exit;
    }

    public static void announceWritten(Path file) {
        if (file == null) return;
        System.err.println("Details: " + file + "  (session JSONL; disable: JK_CLI_DETAILS=off)");
    }

    public void announceIf(boolean verbose) {
        if (verbose && file != null) announceWritten(file);
    }

    static String stripAnsi(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.replaceAll("\\u001B\\[[0-9;]*m", "");
    }

    static String quote(String s) {
        return Jsonl.quote(s);
    }
}
