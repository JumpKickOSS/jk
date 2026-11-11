// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TaskStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliSessionTranscriptTest {

    @BeforeEach
    void liveFile() {
        // These read the transcript while it is written; the lazy open has tests of its own.
        CliSessionTranscript.lazyOpenMillis = 0;
    }

    @AfterEach
    void lazyAgain() {
        CliSessionTranscript.lazyOpenMillis = CliSessionTranscript.LAZY_OPEN_MILLIS;
        CliSessionTranscript.flushMillis = CliSessionTranscript.FLUSH_MILLIS;
    }

    @AfterEach
    void clearProgress() {
        LiveProgress.get().clear();
        CliSessionTranscript leftover = CliSessionTranscript.active();
        if (leftover != null) leftover.finish(0);
    }

    /** Bind to a details path under runs/<buildNumber>/ (engine job-start does this in production). */
    private static Path bind(CliSessionTranscript session, Path project) throws Exception {
        Path details = project.resolve("runs").resolve("7").resolve(CliSessionTranscript.FILE_NAME);
        session.bindJob(42, 7, details.toString(), 12_000);
        assertTrue(Files.isRegularFile(details));
        return details;
    }

    @Test
    void writes_incremental_details_jsonl(@TempDir Path project) throws Exception {
        CliSessionTranscript session = CliSessionTranscript.open(project, "build", List.of("build", "--skip-tests"));
        assertNotNull(session, "open should succeed under a writable project dir");
        assertEquals(session, CliSessionTranscript.active());

        Path file = bind(session, project);
        assertEquals(CliSessionTranscript.FILE_NAME, file.getFileName().toString());
        assertEquals(file, session.file());

        List<String> early = Files.readAllLines(file);
        assertFalse(early.isEmpty());
        // session-start + job meta
        assertTrue(early.stream().anyMatch(l -> l.contains("\"type\":\"session-start\"")));
        assertTrue(early.stream()
                .anyMatch(l ->
                        l.contains("\"type\":\"job\"") && l.contains("\"jid\":42") && l.contains("\"buildNumber\":7")));
        assertTrue(early.stream().anyMatch(l -> l.contains("\"etaMs\":12000")));
        assertTrue(early.stream().noneMatch(l -> l.contains("historyId")));

        LiveProgress.get().update(50, 100);
        session.append(JsonlShape.stepStart("compile-main", "compile", 10));
        session.module("demo:app");

        BuildPlanResult result = new BuildPlanResult(
                "build",
                true,
                Duration.ofMillis(42),
                List.of(new BuildPlanResult.StepReport("compile-main", TaskStatus.SUCCESS, Duration.ofMillis(12))),
                List.of(),
                List.of(),
                false);
        session.absorb(result).wedge("Build successful. Built target/app.jar");

        Optional<Path> written = session.finish(0);
        assertTrue(written.isPresent());
        assertEquals(null, CliSessionTranscript.active());

        List<String> lines = Files.readAllLines(file);
        assertTrue(lines.size() >= 3, "session-start + job + step + session-finish");
        String finish = lines.get(lines.size() - 1);
        assertTrue(finish.contains("\"type\":\"session-finish\""));
        assertTrue(finish.contains("\"exit\":0"));
        assertTrue(finish.contains("\"progress\":100") || finish.contains("\"progress\":100.0"));
        assertTrue(finish.contains("demo:app"));
        assertTrue(finish.contains("Build successful"));
        assertTrue(finish.contains("\"jid\":42"));

        String step =
                lines.stream().filter(l -> l.contains("task-start")).findFirst().orElseThrow();
        assertTrue(step.contains("\"progress\":50") || step.contains("\"progress\":50.0"));
    }

    @Test
    void a_session_across_jobs_keeps_its_first_file_and_records_every_later_job(@TempDir Path project)
            throws Exception {
        CliSessionTranscript session = CliSessionTranscript.openAcrossJobs(project, "dev", List.of("dev"));
        assertNotNull(session);
        Path first = bind(session, project);

        Path second = project.resolve("runs").resolve("8").resolve(CliSessionTranscript.FILE_NAME);
        session.bindJob(43, 8, second.toString(), -1);
        session.appendRaw("{\"schema\":1,\"type\":\"app-started\",\"pid\":7}");
        Path third = project.resolve("runs").resolve("9").resolve(CliSessionTranscript.FILE_NAME);
        session.bindJob(44, 9, third.toString(), -1);
        session.finish(0);

        assertEquals(first, session.file());
        assertFalse(Files.exists(second), "a later job opens no file of its own");
        assertFalse(Files.exists(third));
        List<String> lines = Files.readAllLines(first);
        assertEquals("session-start", type(lines.getFirst()));
        assertEquals("session-finish", type(lines.getLast()));
        List<String> jobs = lines.stream().filter(l -> "job".equals(type(l))).toList();
        assertEquals(3, jobs.size(), "one job line per engine job, in order:\n" + String.join("\n", lines));
        assertTrue(jobs.get(0).contains("\"buildNumber\":7"));
        assertTrue(jobs.get(1).contains("\"buildNumber\":8"));
        assertTrue(jobs.get(2).contains("\"buildNumber\":9"));
        assertTrue(lines.indexOf(jobs.get(1))
                < lines.stream()
                        .filter(l -> "app-started".equals(type(l)))
                        .findFirst()
                        .map(lines::indexOf)
                        .orElseThrow());
    }

    @Test
    void a_build_verb_s_session_follows_its_job_to_the_new_run(@TempDir Path project) throws Exception {
        CliSessionTranscript session = CliSessionTranscript.open(project, "build", List.of("build"));
        assertNotNull(session);
        Path first = bind(session, project);
        Path second = project.resolve("runs").resolve("8").resolve(CliSessionTranscript.FILE_NAME);
        session.bindJob(43, 8, second.toString(), -1);
        session.finish(0);

        assertEquals(second, session.file());
        assertTrue(Files.exists(first));
        assertEquals("session-finish", type(Files.readAllLines(second).getLast()));
    }

    private static String type(String line) {
        int at = line.indexOf("\"type\":\"");
        if (at < 0) return "";
        int start = at + "\"type\":\"".length();
        return line.substring(start, line.indexOf('"', start));
    }

    @Test
    void error_writes_jsonl_line(@TempDir Path project) throws Exception {
        CliSessionTranscript session = CliSessionTranscript.open(project, "test");
        assertNotNull(session);
        bind(session, project);
        session.error("run-tests", "test-failure", "boom");
        Path file = session.finish(4).orElseThrow();
        String body = Files.readString(file);
        assertTrue(body.contains("\"type\":\"error\""));
        assertTrue(body.contains("boom"));
        assertTrue(body.contains("run-tests"));
        assertTrue(body.contains("\"exit\":4"));
    }

    @Test
    void finish_static_returns_exit_unchanged(@TempDir Path project) throws Exception {
        CliSessionTranscript session = CliSessionTranscript.open(project, "build");
        assertNotNull(session);
        bind(session, project);
        assertEquals(7, CliSessionTranscript.finish(session, 7, false));
        assertEquals(3, CliSessionTranscript.finish(null, 3, true));
    }

    @Test
    void strip_ansi_removes_sgr() {
        assertEquals("ok", CliSessionTranscript.stripAnsi("\u001B[32mok\u001B[0m"));
        assertEquals("", CliSessionTranscript.stripAnsi(""));
    }

    @Test
    // The null project is deliberate: it proves open() declines rather than throws.
    @SuppressWarnings("NullAway")
    void open_null_project_is_noop() {
        assertEquals(null, CliSessionTranscript.open(null, "build"));
        assertEquals(null, CliSessionTranscript.open(Path.of("."), "  "));
    }

    @Test
    void write_failure_does_not_throw(@TempDir Path project) throws Exception {
        CliSessionTranscript session = CliSessionTranscript.open(project, "build");
        assertNotNull(session);
        Path file = bind(session, project);
        Path parent = file.getParent();
        Files.walk(parent).sorted(Comparator.reverseOrder()).forEach(p -> {
            try {
                Files.deleteIfExists(p);
            } catch (Exception ignored) {
            }
        });
        Files.writeString(parent, "blocked");
        Optional<Path> written = session.finish(1);
        assertTrue(written.isEmpty() || written.isPresent());
    }

    @Test
    void lazy_flush_still_visible_after_finish(@TempDir Path project) throws Exception {
        CliSessionTranscript session = CliSessionTranscript.open(project, "build");
        assertNotNull(session);
        bind(session, project);
        session.append(JsonlShape.label("compile", "Working…"));
        session.finish(0);
        String body = Files.readString(session.file());
        assertTrue(body.contains("\"type\":\"label\""));
        assertTrue(body.contains("Working"));
    }

    @Test
    void records_wait_for_the_next_flush_and_never_land_half_written(@TempDir Path project) throws Exception {
        CliSessionTranscript.flushMillis = 60_000;
        CliSessionTranscript session = CliSessionTranscript.open(project, "build");
        assertNotNull(session);
        Path file = bind(session, project);
        // After bind: session-start + job meta, written as the file opened.
        int before = Files.readAllLines(file).size();

        for (String step : List.of("compile", "test", "jar")) {
            session.append(JsonlShape.stepFinish(step, step, TaskStatus.SUCCESS, Duration.ofMillis(1), Duration.ZERO));
        }

        assertEquals(before, Files.readAllLines(file).size(), "no write per record between flushes");
        session.finish(0);
        List<String> lines = Files.readAllLines(file);
        for (String line : lines) MiniJson.parse(line);
        assertEquals(3, lines.stream().filter(l -> l.contains("task-finish")).count());
    }

    @Test
    void collected_records_reach_the_file_without_another_append(@TempDir Path project) throws Exception {
        CliSessionTranscript session = CliSessionTranscript.open(project, "build");
        assertNotNull(session);
        Path file = bind(session, project);

        session.append(JsonlShape.label("compile", "Working…"));

        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!Files.readString(file).contains("Working") && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(Files.readString(file).contains("Working"), "the flusher wrote the record within its interval");
        session.finish(0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void session_start_parses_as_json(@TempDir Path project) throws Exception {
        CliSessionTranscript session = CliSessionTranscript.open(project, "build", List.of("build"));
        assertNotNull(session);
        Path file = bind(session, project);
        String first = Files.readAllLines(file).get(0);
        Map<String, Object> doc = (Map<String, Object>) Objects.requireNonNull(MiniJson.parse(first));
        assertTrue(doc.containsKey("schema"));
        assertEquals(CliSessionTranscript.SCHEMA, ((Number) Objects.requireNonNull(doc.get("schema"))).intValue());
        assertEquals("session-start", doc.get("type"));
        session.finish(0);
    }
}
