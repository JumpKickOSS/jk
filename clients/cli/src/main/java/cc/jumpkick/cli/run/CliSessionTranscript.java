// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.plugin.protocol.Jsonl;
import cc.jumpkick.run.PipelineResult;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.MiniJson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Best-effort CLI session transcript under {@code <project>/target/.jk-cli/<ts>/details.json}
 * (JK-1079). Keeps the human terminal terse while preserving command metadata, wedge summary,
 * selected modules, pipeline steps, and engine errors for support / local debug.
 *
 * <p>Never throws into the user command path: open/finish failures are silent no-ops. Disable with
 * {@code JK_CLI_DETAILS=off} (or {@code 0}).
 */
public final class CliSessionTranscript {

    /**
     * Stay on {@code 1} until jk <strong>1.0</strong> — no pre-release schema churn (see
     * {@code docs/architecture.md} schema freeze). Additive fields only.
     */
    public static final int SCHEMA = 1;

    public static final String REL_ROOT = "target/.jk-cli";
    public static final String FILE_NAME = "details.json";
    private static final String ENV = "JK_CLI_DETAILS";

    private static final DateTimeFormatter DIR_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmmss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final Path file;
    private final Instant started;
    private final String command;
    private final List<String> argv;
    private final List<String> modules = new ArrayList<>();
    private final List<Map<String, Object>> errors = new ArrayList<>();
    private final List<Map<String, Object>> steps = new ArrayList<>();
    private String wedgeSummary;
    private long pipelineDurationMs = -1;

    private CliSessionTranscript(Path file, Instant started, String command, List<String> argv) {
        this.file = file;
        this.started = started;
        this.command = command;
        this.argv = List.copyOf(argv);
    }

    /**
     * Open a transcript session under {@code projectDir}. Returns {@code null} when disabled, the
     * project path is unusable, or directory creation fails.
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
            return new CliSessionTranscript(file, started, command, args);
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

    /** Fold a finished pipeline into steps + errors + duration. */
    public CliSessionTranscript absorb(PipelineResult result) {
        if (result == null) return this;
        pipelineDurationMs = result.duration().toMillis();
        for (PipelineResult.StepReport s : result.steps()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", s.name());
            row.put("status", s.status() == null ? "" : s.status().name());
            row.put("duration_ms", s.duration() == null ? 0L : s.duration().toMillis());
            steps.add(row);
        }
        for (PipelineResult.Diagnostic d : result.errors()) {
            error(d.step(), d.code(), d.message());
        }
        return this;
    }

    public CliSessionTranscript error(String message) {
        return error("", "", message);
    }

    public CliSessionTranscript error(String step, String code, String message) {
        if (message == null || message.isBlank()) return this;
        Map<String, Object> row = new LinkedHashMap<>();
        if (step != null && !step.isBlank()) row.put("step", step);
        if (code != null && !code.isBlank()) row.put("code", code);
        row.put("message", message);
        errors.add(row);
        return this;
    }

    /**
     * Write {@code details.json}. Never throws. Returns the path written, or empty on failure.
     */
    public Optional<Path> finish(int exitCode) {
        try {
            Instant finished = Instant.now();
            long durationMs = Duration.between(started, finished).toMillis();
            if (pipelineDurationMs >= 0) {
                // Prefer measured pipeline duration when present; still report wall clock as well.
            }
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("schema", SCHEMA);
            doc.put("command", command);
            doc.put("argv", argv);
            doc.put("exit", exitCode);
            doc.put("duration_ms", durationMs);
            if (pipelineDurationMs >= 0) doc.put("pipeline_duration_ms", pipelineDurationMs);
            doc.put("started", started.toString());
            doc.put("finished", finished.toString());
            if (wedgeSummary != null) doc.put("wedge", stripAnsi(wedgeSummary));
            if (!modules.isEmpty()) doc.put("modules", List.copyOf(modules));
            if (!errors.isEmpty()) doc.put("errors", List.copyOf(errors));
            if (!steps.isEmpty()) doc.put("steps", List.copyOf(steps));
            AtomicWrites.replace(file, MiniJson.writePretty(doc));
            return Optional.of(file);
        } catch (RuntimeException | IOException e) {
            return Optional.empty();
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

    /** One-line discoverability on stderr (verbose only by default callers). */
    public static void announceWritten(Path file) {
        if (file == null) return;
        System.err.println("Details: " + file + "  (session transcript; disable: JK_CLI_DETAILS=off)");
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
