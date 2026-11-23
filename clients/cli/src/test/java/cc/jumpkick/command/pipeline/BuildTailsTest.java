// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.testing.ForcedAnsi;
import cc.jumpkick.model.InstallDestination;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/** Wedge wording for built vs checked vs fully up to date, and the shared module completion line. */
@ExtendWith(ForcedAnsi.class)
class BuildTailsTest {

    private static WorkspaceResult ok(List<ModuleOutcome> modules) {
        return new WorkspaceResult(true, 0, modules, List.of());
    }

    /** The settle line without its {@code took …}: what every shape is pinned to. */
    private static String line(String tail) {
        String plain = TestAnsi.strip(tail);
        int took = plain.lastIndexOf(" took ");
        return took < 0 ? plain : plain.substring(0, took);
    }

    @Test
    void empty_plan_is_up_to_date() {
        assertThat(line(BuildTails.successTail(ok(List.of()), 0, System.nanoTime())))
                .isEqualTo("Build successful. Build up to date");
    }

    @Test
    void all_checked_modules() {
        var modules = List.of(
                new ModuleOutcome("a:b", Path.of("/a"), true, 0, 10, false),
                new ModuleOutcome("a:c", Path.of("/c"), true, 0, 12, false));
        assertThat(line(BuildTails.successTail(ok(modules), 2, System.nanoTime())))
                .isEqualTo("Build successful. Build up to date (2 modules checked)");
    }

    @Test
    void all_built_modules() {
        var modules = List.of(new ModuleOutcome("a:b", Path.of("/a"), true, 0, 100, true));
        assertThat(line(BuildTails.successTail(ok(modules), 1, System.nanoTime())))
                .isEqualTo("Build successful. Built 1 module");
    }

    @Test
    void a_project_whose_steps_all_hit_is_up_to_date_not_built(@TempDir Path dir) {
        var checked = List.of(new ModuleOutcome("a:b", dir, true, 0, 10, false));
        assertThat(line(BuildTails.projectTail(ok(checked), dir))).isEqualTo("Build successful. Build up to date");
        var built = List.of(new ModuleOutcome("a:b", dir, true, 0, 10, true));
        assertThat(line(BuildTails.projectTail(ok(built), dir))).isEqualTo("Build successful. Built project");
    }

    @Test
    void mix_built_and_checked() {
        var modules = List.of(
                new ModuleOutcome("a:b", Path.of("/a"), true, 0, 100, true),
                new ModuleOutcome("a:c", Path.of("/c"), true, 0, 10, false),
                new ModuleOutcome("a:d", Path.of("/d"), true, 0, 10, false));
        assertThat(line(BuildTails.successTail(ok(modules), 3, System.nanoTime())))
                .isEqualTo("Build successful. Built 1 module, 2 up to date");
    }

    @Test
    void every_up_to_date_settle_line_says_build_up_to_date() {
        assertThat(BuildTails.upToDate(null)).isEqualTo(BuildTails.UP_TO_DATE).isEqualTo("Build up to date");
        assertThat(BuildTails.upToDate("served from cache")).isEqualTo("Build up to date (served from cache)");
        assertThat(BuildTails.settle("Install successful", BuildTails.UP_TO_DATE))
                .isEqualTo("Install successful. Build up to date");
    }

    @Test
    void install_settle_lines_take_the_same_shape(@TempDir Path m2) {
        var roots = Map.of(InstallDestination.M2, m2, InstallDestination.JK, m2);
        long now = System.nanoTime();
        assertThat(line(InstallCommand.installTail(0, List.of(), roots, now)))
                .isEqualTo("Install successful. Build up to date");
        var same = new ModuleOutcome.Installed(
                "g:a:1.0-SNAPSHOT", "aa", "bb", InstallDestination.M2, ModuleOutcome.Installed.Change.UNCHANGED);
        assertThat(line(InstallCommand.installTail(1, List.of(same), roots, now)))
                .startsWith("Install successful. Build up to date (g:a:1.0-SNAPSHOT already installed) → ");
        var fresh = new ModuleOutcome.Installed(
                "g:a:1.0-SNAPSHOT", "aa", "bb", InstallDestination.M2, ModuleOutcome.Installed.Change.NEW);
        assertThat(line(InstallCommand.installTail(1, List.of(fresh), roots, now)))
                .startsWith("Install successful. Installed g:a:1.0-SNAPSHOT → ");
        assertThat(line(InstallCommand.installTail(3, List.of(fresh, fresh, fresh), roots, now)))
                .startsWith("Install successful. Installed 3 modules → ");
    }

    @Test
    void completion_line_zero_pads_the_numerator_to_the_denominator_width() {
        String t = TestAnsi.strip(BuildTails.completionLine(true, 1, 16, "g:a", 16));
        assertThat(t).contains("[01 of 16]");
        assertThat(t).contains("g:a");
    }

    @Test
    void a_module_whose_suite_replayed_says_no_test_ran_and_what_its_time_went_on() {
        var replayed = new ModuleOutcome("g:e2e", Path.of("/e2e"), true, 0, 15_400, true)
                .withWaited(14_900)
                .withTests(new ModuleOutcome.Tests(412, 0, true));
        String t = TestAnsi.strip(BuildTails.completionLine(replayed, 62, 65));
        assertThat(t).contains("g:e2e no tests run (412 replayed)").contains("took 15.4s, 14.9s waiting");
    }

    @Test
    void a_module_whose_suite_ran_names_no_replay_and_a_short_wait_is_left_off() {
        var ran = new ModuleOutcome("g:a", Path.of("/a"), true, 0, 2_000, true)
                .withWaited(400)
                .withTests(new ModuleOutcome.Tests(12, 0, false));
        String t = TestAnsi.strip(BuildTails.completionLine(ran, 1, 3));
        assertThat(t).contains("g:a took 2.0s").doesNotContain("replayed").doesNotContain("waiting");
    }

    @Test
    void failed_completion_line_says_failed() {
        String t = TestAnsi.strip(BuildTails.completionLine(false, 2, 3, "g:a", 5));
        assertThat(t).contains("[2 of 3]");
        assertThat(t).contains("— failed");
    }

    @Test
    void a_module_another_failure_stopped_says_stopped_not_failed() {
        var stopped = new ModuleOutcome("g:a", Path.of("/a"), false, 1, 5, true, false).withStopped(true);
        String t = TestAnsi.strip(BuildTails.completionLine(stopped, 2, 3));
        assertThat(t).contains("[2 of 3]").contains("g:a").contains("— stopped");
        assertThat(t).doesNotContain("failed");
        assertThat(TestAnsi.strip(BuildTails.stoppedLine("g:a")))
                .contains("stopped")
                .contains("g:a");
    }
}
