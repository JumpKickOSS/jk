// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.jumpkick.run.PipelineResult;
import cc.jumpkick.run.StepStatus;
import cc.jumpkick.util.MiniJson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliSessionTranscriptTest {

    @Test
    void writes_versioned_details_json(@TempDir Path project) throws Exception {
        CliSessionTranscript session =
                CliSessionTranscript.open(project, "build", List.of("build", "--skip-tests"));
        assertNotNull(session, "open should succeed under a writable project dir");

        PipelineResult result = new PipelineResult(
                "build",
                true,
                Duration.ofMillis(42),
                List.of(new PipelineResult.StepReport("compile-main", StepStatus.SUCCESS, Duration.ofMillis(12))),
                List.of(),
                List.of(),
                false);
        session.module("demo:app")
                .absorb(result)
                .wedge("Build successful. Built target/lib/app.jar");

        Optional<Path> written = session.finish(0);
        assertTrue(written.isPresent());
        Path file = written.get();
        assertTrue(Files.isRegularFile(file));
        assertTrue(file.toString().contains(CliSessionTranscript.REL_ROOT.replace('/', java.io.File.separatorChar))
                || file.toString().replace('\\', '/').contains(CliSessionTranscript.REL_ROOT));
        assertEquals(CliSessionTranscript.FILE_NAME, file.getFileName().toString());

        @SuppressWarnings("unchecked")
        Map<String, Object> doc = (Map<String, Object>) MiniJson.parse(Files.readString(file));
        assertEquals(CliSessionTranscript.SCHEMA, ((Number) doc.get("schema")).intValue());
        assertEquals("build", doc.get("command"));
        assertEquals(0, ((Number) doc.get("exit")).intValue());
        assertTrue(doc.containsKey("duration_ms"));
        assertTrue(doc.containsKey("started"));
        assertTrue(doc.containsKey("finished"));
        assertEquals("Build successful. Built target/lib/app.jar", doc.get("wedge"));
        @SuppressWarnings("unchecked")
        List<String> modules = (List<String>) doc.get("modules");
        assertEquals(List.of("demo:app"), modules);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) doc.get("steps");
        assertEquals(1, steps.size());
        assertEquals("compile-main", steps.get(0).get("name"));
        assertEquals("SUCCESS", steps.get(0).get("status"));
    }

    @Test
    void absorb_records_errors(@TempDir Path project) throws Exception {
        CliSessionTranscript session = CliSessionTranscript.open(project, "test");
        assertNotNull(session);
        PipelineResult result = new PipelineResult(
                "test",
                false,
                Duration.ofMillis(5),
                List.of(),
                List.of(),
                List.of(new PipelineResult.Diagnostic("run-tests", "test-failure", "boom")),
                false);
        session.absorb(result);
        Path file = session.finish(4).orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> doc = (Map<String, Object>) MiniJson.parse(Files.readString(file));
        assertEquals(4, ((Number) doc.get("exit")).intValue());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) doc.get("errors");
        assertEquals(1, errors.size());
        assertEquals("boom", errors.get(0).get("message"));
        assertEquals("run-tests", errors.get(0).get("step"));
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
        // Point at a path where the parent is a file → createDirectories fails on open.
        Path blocker = project.resolve("not-a-dir");
        Files.writeString(blocker, "x");
        // open uses projectDir/target/... so this still works; use a non-writable approach:
        // finishing after deleting the parent directory mid-flight.
        CliSessionTranscript session = CliSessionTranscript.open(project, "build");
        assertNotNull(session);
        Path parent = session.file().getParent();
        Files.walk(parent)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
        // Replace parent with a file so write fails.
        Files.writeString(parent, "blocked");
        Optional<Path> written = session.finish(1);
        assertFalse(written.isPresent());
    }
}
