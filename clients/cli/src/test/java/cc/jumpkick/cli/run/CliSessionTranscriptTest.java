// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.jumpkick.run.PipelineResult;
import cc.jumpkick.run.StepStatus;
import cc.jumpkick.util.MiniJson;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliSessionTranscriptTest {

    @AfterEach
    void clearProgress() {
        LiveProgress.get().clear();
        // Ensure no active session leaks across tests.
        CliSessionTranscript leftover = CliSessionTranscript.active();
        if (leftover != null) leftover.finish(0);
    }

    @Test
    void writes_incremental_details_jsonl(@TempDir Path project) throws Exception {
        CliSessionTranscript session = CliSessionTranscript.open(project, "build", List.of("build", "--skip-tests"));
        assertNotNull(session, "open should succeed under a writable project dir");
        assertEquals(session, CliSessionTranscript.active());

        // Mid-run: session-start is already on disk (flush at open).
        Path file = session.file();
        assertTrue(Files.isRegularFile(file));
        assertEquals(CliSessionTranscript.FILE_NAME, file.getFileName().toString());
        assertTrue(file.toString().contains(CliSessionTranscript.REL_ROOT.replace('/', File.separatorChar))
                || file.toString().replace('\\', '/').contains(CliSessionTranscript.REL_ROOT));

        List<String> early = Files.readAllLines(file);
        assertFalse(early.isEmpty());
        assertTrue(early.get(0).contains("\"type\":\"session-start\""));
        assertTrue(early.get(0).contains("\"command\":\"build\""));

        LiveProgress.get().update(50, 100);
        session.append(JsonlShape.stepStart("compile-main", "compile", 10), true);
        session.module("demo:app");

        PipelineResult result = new PipelineResult(
                "build",
                true,
                Duration.ofMillis(42),
                List.of(new PipelineResult.StepReport("compile-main", StepStatus.SUCCESS, Duration.ofMillis(12))),
                List.of(),
                List.of(),
                false);
        session.absorb(result).wedge("Build successful. Built target/lib/app.jar");

        Optional<Path> written = session.finish(0);
        assertTrue(written.isPresent());
        assertEquals(null, CliSessionTranscript.active());

        List<String> lines = Files.readAllLines(file);
        assertTrue(lines.size() >= 3, "session-start + step + session-finish");
        String finish = lines.get(lines.size() - 1);
        assertTrue(finish.contains("\"type\":\"session-finish\""));
        assertTrue(finish.contains("\"exit\":0"));
        assertTrue(finish.contains("\"progress\":100") || finish.contains("\"progress\":100.0"));
        assertTrue(finish.contains("demo:app"));
        assertTrue(finish.contains("Build successful"));

        // Mid-run line should have carried progress rider.
        String step =
                lines.stream().filter(l -> l.contains("step-start")).findFirst().orElseThrow();
        assertTrue(step.contains("\"progress\":50") || step.contains("\"progress\":50.0"));
    }

    @Test
    void error_writes_jsonl_line(@TempDir Path project) throws Exception {
        CliSessionTranscript session = CliSessionTranscript.open(project, "test");
        assertNotNull(session);
        session.error("run-tests", "test-failure", "boom");
        Path file = session.finish(4).orElseThrow();
        String body = Files.readString(file);
        assertTrue(body.contains("\"type\":\"error\""));
        assertTrue(body.contains("boom"));
        assertTrue(body.contains("run-tests"));
        assertTrue(body.contains("\"exit\":4"));
    }

    @Test
    void finish_static_returns_exit_unchanged(@TempDir Path project) {
        CliSessionTranscript session = CliSessionTranscript.open(project, "build");
        assertNotNull(session);
        assertEquals(7, CliSessionTranscript.finish(session, 7, false));
        assertEquals(3, CliSessionTranscript.finish(null, 3, true));
    }

    @Test
    void strip_ansi_removes_sgr() {
        assertEquals("ok", CliSessionTranscript.stripAnsi("\u001B[32mok\u001B[0m"));
        assertEquals("", CliSessionTranscript.stripAnsi(""));
    }

    @Test
    void open_null_project_is_noop() {
        assertEquals(null, CliSessionTranscript.open(null, "build"));
        assertEquals(null, CliSessionTranscript.open(Path.of("."), "  "));
    }

    @Test
    void write_failure_does_not_throw(@TempDir Path project) throws Exception {
        CliSessionTranscript session = CliSessionTranscript.open(project, "build");
        assertNotNull(session);
        Path parent = session.file().getParent();
        // Close writer by finishing after deleting the directory mid-flight.
        Files.walk(parent).sorted(Comparator.reverseOrder()).forEach(p -> {
            try {
                Files.deleteIfExists(p);
            } catch (Exception ignored) {
            }
        });
        Files.writeString(parent, "blocked");
        Optional<Path> written = session.finish(1);
        // finish may return empty or the path; must not throw
        assertTrue(written.isEmpty() || written.isPresent());
    }

    @Test
    void lazy_flush_still_visible_after_finish(@TempDir Path project) throws Exception {
        CliSessionTranscript session = CliSessionTranscript.open(project, "build");
        assertNotNull(session);
        // Hot-tick path: lazy flush (false) — line is buffered until finish/heartbeat.
        session.append(JsonlShape.label("compile", "Working…"), false);
        session.finish(0);
        String body = Files.readString(session.file());
        assertTrue(body.contains("\"type\":\"label\""));
        assertTrue(body.contains("Working"));
    }

    @Test
    void flush_is_line_bounded_lazy_lines_stay_off_disk(@TempDir Path project) throws Exception {
        CliSessionTranscript session = CliSessionTranscript.open(project, "build");
        assertNotNull(session);
        // session-start was flushed immediately — one complete line on disk.
        List<String> onDisk = Files.readAllLines(session.file());
        assertEquals(1, onDisk.size());
        assertTrue(onDisk.get(0).contains("session-start"));
        // Parse every on-disk line as JSON — no partial records.
        for (String line : onDisk) {
            MiniJson.parse(line);
        }

        // Lazy hot tick: must not appear on disk until flush/finish.
        session.append(JsonlShape.label("compile", "buffered-only"), false);
        List<String> still = Files.readAllLines(session.file());
        assertEquals(1, still.size(), "lazy line must not partial-flush mid-record");
        assertFalse(Files.readString(session.file()).contains("buffered-only"));

        // Immediate semantic event drains pending complete records (label + step-finish).
        session.append(JsonlShape.stepFinish("compile", "compile", StepStatus.SUCCESS, Duration.ofMillis(1)), true);
        String after = Files.readString(session.file());
        assertTrue(after.contains("buffered-only"));
        assertTrue(after.contains("step-finish"));
        for (String line : Files.readAllLines(session.file())) {
            MiniJson.parse(line); // every flushed line is a complete JSON object
        }
        session.finish(0);
    }

    @Test
    void is_immediate_type_classifies_hot_ticks() {
        assertFalse(
                CliSessionTranscript.isImmediateType("{\"schema\":1,\"ts\":1,\"type\":\"progress\",\"step\":\"x\"}"));
        assertFalse(CliSessionTranscript.isImmediateType(
                "{\"schema\":1,\"ts\":1,\"type\":\"tick-update\",\"step\":\"x\"}"));
        // Engine aggregate ticks arrive at up to 12.5Hz — heartbeat cadence, not per-line flush.
        assertFalse(CliSessionTranscript.isImmediateType(
                "{\"schema\":1,\"ts\":1,\"type\":\"workspace-progress\",\"numerator\":5,\"denominator\":10}"));
        assertTrue(CliSessionTranscript.isImmediateType(
                "{\"schema\":1,\"ts\":1,\"type\":\"step-finish\",\"step\":\"x\"}"));
        assertTrue(CliSessionTranscript.isImmediateType(
                "{\"schema\":1,\"ts\":1,\"type\":\"error\",\"message\":\"nope\"}"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void session_start_parses_as_json(@TempDir Path project) throws Exception {
        CliSessionTranscript session = CliSessionTranscript.open(project, "build", List.of("build"));
        assertNotNull(session);
        String first = Files.readAllLines(session.file()).get(0);
        Map<String, Object> doc = (Map<String, Object>) MiniJson.parse(first);
        assertEquals(CliSessionTranscript.SCHEMA, ((Number) doc.get("schema")).intValue());
        assertEquals("session-start", doc.get("type"));
        session.finish(0);
    }
}
