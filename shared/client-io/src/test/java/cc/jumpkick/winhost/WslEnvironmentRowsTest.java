// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The setup decision's WSL rows: which distros get one, and that applying a row runs the one operation. */
class WslEnvironmentRowsTest {

    private final FakeHost host = new FakeHost();
    private final List<String> installed = new ArrayList<>();
    private final WslEnvironmentRows rows = new WslEnvironmentRows((distro, progress) -> {
        installed.add(distro);
        progress.accept("installing in " + distro);
        return new MachineSetup.Result("", true, "installed JumpKick 0.51.1 in " + distro);
    });

    private WindowsHostCheck check(SetupMemo memo) {
        return new WindowsHostCheck(host, memo, Path.of("C:\\Users\\ada\\.jk"), null);
    }

    @Test
    void a_running_wsl_2_distro_without_jumpkick_gets_a_row_and_no_other_does() {
        host.distros.add(new WslDistros.Distro("Ubuntu-26.10", 2, true, true, null));
        host.distros.add(new WslDistros.Distro("Debian", 2, false, true, "jk 0.51.1"));
        host.distros.add(new WslDistros.Distro("Fedora", 2, false, false, null));
        host.distros.add(new WslDistros.Distro("Legacy", 1, false, true, null));
        List<SetupChange> out = rows.rows(check(SetupMemo.NONE));
        assertThat(out).extracting(SetupChange::title).containsExactly("Install JumpKick in WSL Ubuntu-26.10");
        SetupChange row = out.getFirst();
        assertThat(row.kind()).isEqualTo(SetupChange.Kind.ENVIRONMENT);
        assertThat(row.kind().elevated()).isFalse();
        assertThat(row.path()).isEqualTo("wsl:Ubuntu-26.10");
        assertThat(row.id()).isEqualTo(WslEnvironmentRows.id("ubuntu-26.10")).startsWith("wsl-");
    }

    @Test
    void a_row_runs_the_operation_for_its_distro_and_reports_under_its_own_id() {
        host.distros.add(new WslDistros.Distro("Ubuntu-26.10", 2, true, true, null));
        SetupChange row = rows.rows(check(SetupMemo.NONE)).getFirst();
        List<String> steps = new ArrayList<>();
        assertThat(rows.owns(row)).isTrue();
        MachineSetup.Result r = rows.install(row, steps::add);
        assertThat(r).isEqualTo(new MachineSetup.Result(row.id(), true, "installed JumpKick 0.51.1 in Ubuntu-26.10"));
        assertThat(installed).containsExactly("Ubuntu-26.10");
        assertThat(steps).containsExactly("installing in Ubuntu-26.10");
    }

    @Test
    void a_row_for_a_name_wsl_could_not_have_given_is_not_owned() {
        SetupChange forged =
                new SetupChange("wsl-x", "x", "", "", SetupChange.Kind.ENVIRONMENT, "wsl:Ubuntu; rm -rf ~");
        assertThat(rows.owns(forged)).isFalse();
        assertThat(rows.install(forged, s -> {}).ok()).isFalse();
        assertThat(installed).isEmpty();
    }

    @Test
    void the_plan_carries_the_rows_and_an_answered_row_is_not_offered_again() {
        host.distros.add(new WslDistros.Distro("Ubuntu-26.10", 2, true, true, null));
        List<SetupEnvironment> sources = List.of(rows);
        SetupPlan plan = SetupPlan.of(check(SetupMemo.NONE), List.of(), folder -> -1L, sources);
        assertThat(plan.changes()).extracting(SetupChange::path).contains("wsl:Ubuntu-26.10");
        SetupMemo answered = new SetupMemo(
                SetupMemo.Answer.DECLINED,
                1,
                0,
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                0,
                0,
                List.of(WslEnvironmentRows.id("Ubuntu-26.10")));
        SetupPlan again = SetupPlan.unanswered(check(answered), List.of(), folder -> -1L, sources);
        assertThat(again.changes()).extracting(SetupChange::path).doesNotContain("wsl:Ubuntu-26.10");
    }
}
