// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.command.Exit;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A workspace that stopped at one module's failure: the siblings in flight ended {@code
 * CANCELLED}, and the file must read as the failure it is — the failed step named, the stopped
 * siblings {@code SKIPPED}, and the word {@code CANCELLED} nowhere. A run the user cancelled keeps
 * every cancelled row.
 */
class JkResultsStoppedTest {

    private static final BuildRecord.Task COMPILE_TEST_FAIL =
            new BuildRecord.Task("compile-test", "test", "FAIL", 264, 0);
    private static final BuildRecord.Task RUN_TESTS_STOPPED =
            new BuildRecord.Task("run-tests", "test", "CANCELLED", 1_200, 0);
    private static final BuildRecord.Task COMPILE_OK =
            new BuildRecord.Task("compile-java", "compile", "SUCCESS", 90, 0);

    private static final BuildRecord.Diag JAVAC = new BuildRecord.Diag(
            "error",
            "/ws/engine",
            "compile-test",
            "javac",
            "/ws/engine/src/test/java/Foo.java:9: error: cannot find symbol",
            null,
            null,
            "g:engine",
            null,
            null,
            null,
            null);
    private static final BuildRecord.Diag STOPPED_RUN = new BuildRecord.Diag(
            "error",
            "/ws/cli",
            "run-tests",
            "cancelled",
            "test run cancelled",
            null,
            null,
            "g:cli",
            null,
            null,
            null,
            null);

    @Test
    void a_failed_run_names_the_failure_and_renders_the_stopped_siblings_as_skipped() {
        BuildRecord r = record(false, Exit.FAILURE, List.of(JAVAC, STOPPED_RUN));
        String md = JkResultsMarkdown.render(r);

        assertThat(md).startsWith("# jk results — FAIL\n\n**FAIL** · build · `g:ws` · #7 · 16.5s · **exit 1**");
        assertThat(md)
                .contains(
                        "- `g:engine` `compile-test`: /ws/engine/src/test/java/Foo.java:9: error: cannot find symbol");
        assertThat(md).contains("Modules: 3 (**1 failed**, 1 skipped)");
        assertThat(md).contains("Diagnostics: **1 error**");
        assertThat(md).contains("| g:engine | `compile-test` | FAIL |");
        assertThat(md).doesNotContain("`run-tests` | CANCELLED");
        assertThat(md).contains("_1 step stopped by the failure._");
        assertThat(md).contains("| g:cli | SKIPPED |");
        assertThat(md).contains("| g:engine | FAIL |");
        assertThat(md).doesNotContain("### run-tests — g:cli");
        assertThat(md).doesNotContain("CANCELLED");
    }

    @Test
    void a_cancelled_run_keeps_its_cancelled_rows() {
        BuildRecord r = record(true, Exit.INTERRUPTED, List.of(JAVAC, STOPPED_RUN));
        String md = JkResultsMarkdown.render(r);

        assertThat(md).startsWith("# jk results — CANCELLED");
        assertThat(md).contains("**exit 130**");
        assertThat(md).contains("| g:cli | `run-tests` | CANCELLED |");
        assertThat(md).contains("Modules: 3 (**2 failed**)");
        assertThat(md).contains("### run-tests — g:cli");
        assertThat(md).doesNotContain("stopped by the failure");
        assertThat(md).doesNotContain("SKIPPED");
    }

    /** A module with a failed step of its own and a stopped one beside it is a failure, not a skip. */
    @Test
    void a_module_that_failed_and_was_also_stopped_reads_as_failed() {
        BuildRecord.Module both = new BuildRecord.Module(
                "g:both", "/ws/both", false, Exit.FAILURE, 300, List.of(COMPILE_TEST_FAIL, RUN_TESTS_STOPPED));
        assertThat(JkResultsStopped.stoppedModule(record(false, Exit.FAILURE, List.of()), both))
                .isFalse();
    }

    /** engine failed its compile-test; cli's test run was stopped; core finished green. */
    private static BuildRecord record(boolean cancelled, int exit, List<BuildRecord.Diag> diags) {
        BuildRecord.Module engine = new BuildRecord.Module(
                "g:engine", "/ws/engine", false, Exit.FAILURE, 4_000, List.of(COMPILE_OK, COMPILE_TEST_FAIL));
        BuildRecord.Module cli = new BuildRecord.Module(
                "g:cli", "/ws/cli", false, Exit.TESTS_FAILED, 3_000, List.of(COMPILE_OK, RUN_TESTS_STOPPED));
        BuildRecord.Module core = new BuildRecord.Module("g:core", "/ws/core", true, 0, 2_000, List.of(COMPILE_OK));
        return new BuildRecord(
                "id",
                7,
                BuildRecord.SCHEMA,
                "build",
                "/ws",
                "g:ws",
                "pid",
                1_000,
                17_500,
                16_500,
                false,
                cancelled,
                exit,
                "9.9",
                null,
                List.of(engine, cli, core),
                List.of(),
                diags,
                "cli",
                null,
                null,
                null,
                false,
                null,
                0L,
                null,
                List.of());
    }
}
