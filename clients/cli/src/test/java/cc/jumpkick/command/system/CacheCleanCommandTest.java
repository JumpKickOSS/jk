// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.run.BuildPlanResult;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class CacheCleanCommandTest {

    private static final BuildPlanResult DONE =
            new BuildPlanResult("cache", true, Duration.ZERO, List.of(), List.of(), List.of(), false, false);

    private static @Nullable String success(ConsoleSpec spec) {
        return spec.onSuccess().apply(DONE);
    }

    @Test
    void the_result_line_counts_what_was_removed_and_names_what_was_cleaned() {
        assertThat(success(CacheCleanCommand.cleanSpec(false, () -> 1, () -> 2048, "cache")))
                .isEqualTo("Finished cleaning cache. 1 file removed, " + CacheCommand.fmtBytes(2048) + " reclaimed.");
        assertThat(success(CacheCleanCommand.cleanSpec(false, () -> 0, () -> 0, "the Maven local repository")))
                .isEqualTo("Finished cleaning the Maven local repository. Nothing to clean up.");
    }

    @Test
    void a_dry_run_says_what_it_would_remove_and_reads_the_counts_late() {
        long[] files = {0};
        ConsoleSpec spec = CacheCleanCommand.cleanSpec(true, () -> files[0], () -> 10, "cache");
        assertThat(success(spec)).isEqualTo("Dry run: nothing to clean up.");

        files[0] = 3;
        assertThat(success(spec))
                .isEqualTo("Dry run: would remove 3 files, " + CacheCommand.fmtBytes(10) + " reclaimable.");
        assertThat(spec.onFailure().apply(DONE)).isEqualTo("Failed to clean cache.");
    }

    @Test
    void the_subcommand_answers_to_clean_and_prune() {
        CacheCleanCommand clean = new CacheCleanCommand();
        assertThat(clean.name()).isEqualTo("clean");
        assertThat(clean.aliases()).containsExactly("prune");
        assertThat(new CacheCommand().subcommands()).anyMatch(c -> c instanceof CacheCleanCommand);
    }
}
