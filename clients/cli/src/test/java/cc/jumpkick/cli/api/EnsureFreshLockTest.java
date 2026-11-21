// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.api;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.BuildPlanResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Failure policy: freshen failure with an existing readable lock is soft (warn, exit 0) so
 * read-only commands keep answering from the stale lock; no lock at all or a genuinely
 * unsatisfiable manifest stays a hard failure.
 */
class EnsureFreshLockTest {

    @Test
    void soft_failure_with_existing_lock_proceeds(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), "name = \"demo\"\n");
        Files.writeString(tmp.resolve("jk-lock.toml"), "version = 1\n");

        int code = EnsureFreshLock.failSoftOrHard(tmp, "Explain", "connect timed out", 6, null);

        assertThat(code).isZero();
    }

    @Test
    void unsatisfiable_resolution_hard_fails_even_with_a_lock(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), "name = \"demo\"\n");
        Files.writeString(tmp.resolve("jk-lock.toml"), "version = 1\n");

        int code = EnsureFreshLock.failSoftOrHard(tmp, "Explain", "‼ Cannot resolve dependencies:\n  │ …", 6, null);

        assertThat(code).isEqualTo(6);
    }

    @Test
    void missing_lock_hard_fails(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), "name = \"demo\"\n");

        int code = EnsureFreshLock.failSoftOrHard(tmp, "Tree", "connect timed out", 6, null);

        assertThat(code).isEqualTo(6);
    }

    @Test
    void zero_exit_code_maps_to_config_on_hard_failure(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), "name = \"demo\"\n");

        int code = EnsureFreshLock.failSoftOrHard(tmp, "Tree", "boom", 0, null);

        assertThat(code).isEqualTo(Exit.CONFIG);
    }

    /**
     * Under {@code -O json} a freshen that failed on a resolve with no solution prints it as an
     * {@code error} row carrying the conflict as an object; a failure without one prints no row.
     */
    @Test
    void a_conflict_is_an_error_row_on_stdout() {
        String conflict = "{\"conclusion\":\"the project's requirements cannot be resolved\","
                + "\"packages\":[\"com.foo:leaf\"],\"facts\":[],\"declared\":[\"com.foo:leaf 9.9\"]}";
        BuildPlanResult.Diagnostic resolve = new BuildPlanResult.Diagnostic(
                        "resolve-deps", "verbatim", "‼ Cannot resolve dependencies:\n  │ …\n")
                .withConflict(conflict);
        BuildPlanResult.Diagnostic network = new BuildPlanResult.Diagnostic("resolve-deps", "resolve", "timed out");

        CliOutput.beginCommand(false, true);
        String out;
        try {
            out = Capture.stdout(() -> EnsureFreshLock.printConflicts(List.of(network, resolve)));
        } finally {
            CliOutput.beginCommand(false, false);
        }

        assertThat(out.lines()).singleElement().satisfies(line -> {
            Map<?, ?> row = (Map<?, ?>) Objects.requireNonNull(MiniJson.parse(line));
            assertThat(row.get("type")).isEqualTo("error");
            assertThat(row.get("task")).isEqualTo("resolve-deps");
            assertThat(row.get("message")).isEqualTo("‼ Cannot resolve dependencies:\n  │ …");
            assertThat(row.get("conflict")).isEqualTo(MiniJson.parse(conflict));
        });
    }
}
