// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.config.SecretRedactor;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.listen.EventRedaction;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.SourceLocation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A workspace run that failed on a run-level verdict records that verdict as an error row, so both
 * reports name it instead of a bare exit code.
 */
class BuildAccumulatorRunErrorsTest {

    private static final String WHY = "--class named 1 class the tag filter excluded: a.BenchTest [bench];"
            + " drop --exclude-tags bench to run it";

    @Test
    void a_run_level_verdict_is_an_error_row_both_reports_name() {
        BuildAccumulator a = new BuildAccumulator("test", "/ws", "g:ws", "cli");
        a.addTask("/ws/fmt", "run-tests", "test", "SUCCESS", 900, 0);
        a.addRunErrors(List.of(WHY));

        BuildRecord r = a.toRecord(2_000, false, 9_500, "9.9", null);

        assertThat(r.success()).isFalse();
        assertThat(r.diagnostics()).singleElement().satisfies(d -> {
            assertThat(d.severity()).isEqualTo("error");
            assertThat(d.code()).isEqualTo("run-error");
            assertThat(d.message()).isEqualTo(WHY);
        });
        assertThat(JkResultsAgent.render(r)).startsWith("FAIL test ws · 9.5s — " + WHY + "\n");
        assertThat(JkResultsMarkdown.render(r)).contains("- " + WHY + "\n").doesNotContain("failed (exit");
    }

    @Test
    void an_error_the_run_already_recorded_is_not_repeated() {
        BuildAccumulator a = new BuildAccumulator("build", "/ws", "g:ws", "cli");
        a.addPreflightFailure("lock", 40, "no version of a:b satisfies 2.0", null);
        a.addRunErrors(List.of("no version of a:b satisfies 2.0", " "));

        BuildRecord r = a.toRecord(2_000, false, 100, "9.9", null);

        assertThat(r.diagnostics()).singleElement().satisfies(d -> assertThat(d.step())
                .isEqualTo("lock"));
    }

    @Test
    void a_located_run_error_carries_its_file_line_and_column_into_the_record() {
        BuildAccumulator a = new BuildAccumulator("build", "/ws", "g:ws", "cli");
        a.addRunError("java must be an integer", new SourceLocation("/ws/jk.toml", 4, 1));
        a.addPreflightFailure("lock", 40, "nothing has a:b", new SourceLocation("/ws/app/jk.toml", 7, 1));

        BuildRecord r = a.toRecord(2_000, false, 100, "9.9", null);

        assertThat(r.diagnostics())
                .extracting(
                        BuildRecord.Diag::code, BuildRecord.Diag::file, BuildRecord.Diag::line, BuildRecord.Diag::col)
                .containsExactlyInAnyOrder(
                        tuple("run-error", "/ws/jk.toml", 4, 1), tuple("lock", "/ws/app/jk.toml", 7, 1));
    }

    @Test
    void a_preflight_failure_with_no_solution_keeps_its_conflict_on_the_error_row() {
        BuildAccumulator a = new BuildAccumulator("build", "/ws", "g:ws", "cli");
        String conflict = "{\"facts\":[{\"kind\":\"no-versions\",\"package\":\"a:b\"}]}";
        a.addPreflightFailure("lock", 40, "no version of a:b satisfies 2.0", null, conflict);

        BuildRecord r = a.toRecord(2_000, false, 100, "9.9", null);

        assertThat(r.diagnostics()).singleElement().satisfies(d -> {
            assertThat(d.step()).isEqualTo("lock");
            assertThat(d.conflict()).isEqualTo(conflict);
        });
    }

    @Test
    void a_preflight_failure_masks_a_secret_in_its_reason_and_conflict(@TempDir Path ws) throws IOException {
        String secret = "lock-preflight-s3cret-token";
        Files.writeString(ws.resolve(".env"), "TOKEN=" + secret + "\n");
        BuildAccumulator a = new BuildAccumulator("build", ws.toString(), "g:ws", "cli");
        String reason = "401 from https://u:" + secret + "@repo.example/a/b";
        a.addPreflightFailure("lock", 40, reason, null, "{\"conclusion\":\"" + reason + "\"}");
        a.addRunErrors(List.of(EventRedaction.redactText(ws.toString(), reason)));

        BuildRecord r = a.toRecord(2_000, false, 100, "9.9", null);

        assertThat(r.diagnostics()).singleElement().satisfies(d -> {
            assertThat(d.message()).doesNotContain(secret).contains(SecretRedactor.MASK);
            assertThat(d.conflict()).doesNotContain(secret).contains(SecretRedactor.MASK);
        });
    }

    @Test
    void a_lock_failure_handed_over_raw_reaches_the_record_masked(@TempDir Path ws) throws IOException {
        String secret = "lock-failure-s3cret-token";
        Files.writeString(ws.resolve(".env"), "TOKEN=" + secret + "\n");
        BuildAccumulator a = new BuildAccumulator("lock", ws.toString(), "g:ws", "cli");
        String reason = "401 from https://u:" + secret + "@repo.example/a/b";
        a.addRunError(reason, new SourceLocation(ws.resolve("jk.toml").toString(), 3, 1));
        a.addRunErrors(List.of(reason, EventRedaction.redactText(ws.toString(), reason)));
        a.stamp(JobOutcome.failed(Exit.FAILURE));

        BuildRecord r = a.toRecord(2_000, false, 100, "9.9", null);

        assertThat(r.diagnostics()).singleElement().satisfies(d -> assertThat(d.message())
                .isEqualTo("401 from https://u:" + SecretRedactor.MASK + "@repo.example/a/b"));
        assertThat(JkResultsMarkdown.render(r)).doesNotContain(secret);
    }

    @Test
    void every_other_text_the_record_takes_whole_is_masked(@TempDir Path ws) throws IOException {
        String secret = "accumulator-s3cret-token";
        Files.writeString(ws.resolve(".env"), "TOKEN=" + secret + "\n");
        BuildAccumulator a = new BuildAccumulator("publish", ws.toString(), "g:ws", "cli");
        a.addEscapedThrow(new IllegalStateException("GET https://u:" + secret + "@repo.example failed"));
        a.addWarning(null, null, "notice", "token " + secret + " expires soon");
        a.markUserCancelled(true, "cancelled by " + secret);
        a.addPublish(new BuildRecord.Publish(
                "https://u:" + secret + "@repo.example",
                1,
                false,
                "d1",
                "FAILED",
                List.of("bad " + secret),
                List.of()));

        BuildRecord r = a.toRecord(2_000, false, 100, "9.9", null);

        assertThat(r.diagnostics())
                .hasSize(3)
                .allSatisfy(d -> assertThat(d.message()).doesNotContain(secret).contains(SecretRedactor.MASK));
        assertThat(a.cancelReason()).doesNotContain(secret);
        assertThat(r.publish()).isNotNull().satisfies(p -> {
            assertThat(p.destination()).doesNotContain(secret);
            assertThat(p.deploymentErrors()).singleElement().asString().doesNotContain(secret);
        });
    }
}
