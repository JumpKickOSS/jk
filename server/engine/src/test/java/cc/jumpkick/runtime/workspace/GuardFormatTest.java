// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import cc.jumpkick.guard.baseline.Baselines;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The guard's format step: when it checks rather than writes, and what a failed check says. */
class GuardFormatTest {

    @Test
    void the_clients_ci_makes_it_a_check_whatever_shell_started_the_engine() {
        assertThat(GuardFormat.ci(Map.of("CI", "true"))).isTrue();
        assumeFalse(Baselines.ciMode(), "the engine's own CI decides on a CI host");
        assertThat(GuardFormat.ci(Map.of("CI", "false"))).isFalse();
        assertThat(GuardFormat.ci(Map.of())).isFalse();
        assertThat(GuardFormat.ci(null)).isFalse();
    }

    @Test
    void a_failed_check_names_each_file_from_the_root_the_first_with_the_remedy(@TempDir Path root) {
        GuardFormat.Result result = new GuardFormat.Result(
                List.of(), List.of(root.resolve("a/src/main/java/A.java"), root.resolve("b/src/B.java")), 0);
        assertThat(GuardFormat.checkErrors(result, root))
                .containsExactly(
                        "not formatted: a/src/main/java/A.java"
                                + " ([format] enforce: jk guard outside CI, or jk format, formats it)",
                        "not formatted: b/src/B.java");
    }

    @Test
    void a_ratchet_git_cannot_read_formats_nothing(@TempDir Path notARepo) {
        assertThat(GuardFormat.changedSince(notARepo, "origin/main")).isEmpty();
    }
}
