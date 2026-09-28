// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.BuildPlanResult;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A warning with no file names its module when the build has more than one. */
class JkResultsWarningsTest {

    @Test
    void a_workspace_warning_without_a_file_names_its_module() {
        List<BuildRecord.Module> modules = List.of(
                new BuildRecord.Module("cc.jumpkick:jk-engine", "/ws/server/engine", true, 0, 10, List.of()),
                new BuildRecord.Module("cc.jumpkick:jk-cli", "/ws/clients/cli", true, 0, 10, List.of()));
        BuildRecord.Diag engineWait = new BuildRecord.Diag(
                "warning", "/ws/server/engine", "run-tests", "memory-wait", "waited 12s for memory", "", "");
        BuildRecord.Diag engineAgain = new BuildRecord.Diag(
                "warning", "/ws/server/engine", "run-tests", "memory-wait", "waited 1s for memory", "", "");
        BuildRecord.Diag cliWait = new BuildRecord.Diag(
                "warning", "/ws/clients/cli", "run-tests", "memory-wait", "waited 3s for memory", "", "");
        BuildRecord.Diag engineRetry = new BuildRecord.Diag(
                "warning",
                "/ws/server/engine",
                "run-tests",
                "heap-retry",
                "retried with 256 MiB heap after running out of 128 MiB",
                "",
                "");
        BuildRecord.Diag cliRetry = new BuildRecord.Diag(
                "warning",
                "/ws/clients/cli",
                "compile-java",
                "heap-retry",
                "retried with 1.0 GiB heap after running out of 512 MiB",
                "",
                "");
        BuildRecord.Diag javadoc = BuildAccumulator.diagFromPlan(
                "warning",
                "/ws/server/engine",
                "/ws/server/engine",
                new BuildPlanResult.Diagnostic(
                        "package-javadoc", "javadoc", "/ws/server/engine/src/One.java:6: warning: unknown tag"));
        String md = JkResultsMarkdown.render(
                record(modules, List.of(engineWait, engineAgain, cliWait, engineRetry, cliRetry, javadoc)));

        assertThat(md).contains("- `jk-engine` `run-tests` waited 12s for memory\n");
        assertThat(md).doesNotContain("waited 1s for memory");
        assertThat(md).contains("- `jk-cli` `run-tests` waited 3s for memory\n");
        assertThat(md).contains("- `jk-engine` `run-tests` retried with 256 MiB heap after running out of 128 MiB\n");
        assertThat(md).contains("- `jk-cli` `compile-java` retried with 1.0 GiB heap after running out of 512 MiB\n");
        assertThat(md).contains("`package-javadoc` `server/engine/src/One.java:6`");
        assertThat(md).doesNotContain("`jk-engine` `package-javadoc`");

        String alone = JkResultsMarkdown.render(record(List.of(modules.getFirst()), List.of(engineRetry)));
        assertThat(alone)
                .contains("- `run-tests` retried with 256 MiB heap after running out of 128 MiB\n")
                .doesNotContain("`jk-engine`");
    }

    private static BuildRecord record(List<BuildRecord.Module> modules, List<BuildRecord.Diag> diags) {
        return new BuildRecord(
                "id",
                3,
                BuildRecord.SCHEMA,
                "build",
                "/ws",
                "g:a",
                "pid",
                1_000,
                1_100,
                100,
                true,
                false,
                0,
                "9.9",
                null,
                modules,
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
