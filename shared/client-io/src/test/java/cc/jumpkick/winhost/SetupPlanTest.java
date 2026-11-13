// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.winhost.Registry.Hive;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SetupPlanTest {

    private static final Path HOME = Path.of("C:\\Users\\ada\\.jk");
    private static final List<RootDiscovery.Root> ROOTS = List.of(
            new RootDiscovery.Root(HOME, "JumpKick's store, cache and toolchains", null),
            new RootDiscovery.Root(Path.of("C:\\Users\\ada\\IdeaProjects"), "holds projects", null));

    private final FakeHost host = new FakeHost();
    private final List<Path> probed = new ArrayList<>();

    private SetupPlan plan(SetupMemo memo) {
        WindowsHostCheck check = new WindowsHostCheck(host, memo, HOME, null);
        return SetupPlan.of(check, ROOTS, folder -> {
            probed.add(folder);
            return 1_900L;
        });
    }

    @Test
    void eachExclusionIsItsOwnRowWithItsMeasuredEffect() {
        SetupPlan plan = plan(SetupMemo.NONE);
        assertThat(plan.changes()).extracting(SetupChange::kind).containsOnly(SetupChange.Kind.EXCLUDE);
        assertThat(plan.changes().getFirst().effect())
                .isEqualTo("Measured here: writing and reading back costs 1.9 s per 1,000 build files with the scan.");
        assertThat(plan.changes().get(1).effect()).startsWith("Measured in C:\\Users\\ada\\.jk: 1.9 s per 1,000");
        assertThat(probed).containsExactly(HOME);
        assertThat(plan.measuredMs()).isEqualTo(1_900L);
        assertThat(plan.changes().getFirst().item())
                .containsEntry("label", "Exclude C:\\Users\\ada\\.jk from Defender");
    }

    @Test
    void aVerifiedFolderIsLeftOutAndASetUpMachineHasNothingToChange() {
        SetupMemo verified = new SetupMemo(
                SetupMemo.Answer.ACCEPTED,
                1,
                1,
                List.of("C:\\Users\\ada\\.jk", "C:\\Users\\ada\\IdeaProjects"),
                List.of(),
                List.of(),
                Map.of(),
                0,
                0,
                List.of());
        assertThat(plan(verified).isEmpty()).isTrue();
        assertThat(probed).isEmpty();
    }

    @Test
    void defenderOffProposesNoExclusions() {
        host.dword(Hive.LOCAL_MACHINE, WindowsHostCheck.DEFENDER_KEY, "IsServiceRunning", 0);
        assertThat(plan(SetupMemo.NONE).isEmpty()).isTrue();
    }

    @Test
    void longPathsGitAndThePolicyJoinTheSameList() {
        host.dword(Hive.LOCAL_MACHINE, WindowsHostCheck.FS_KEY, "LongPathsEnabled", 0);
        host.git = false;
        host.strings.clear();
        SetupPlan plan = plan(SetupMemo.NONE);
        assertThat(plan.changes())
                .extracting(SetupChange::id)
                .endsWith(SetupChange.LONG_PATHS, SetupChange.GIT_LONG_PATHS, SetupChange.EXECUTION_POLICY);
        assertThat(plan.change(SetupChange.EXECUTION_POLICY).orElseThrow().why())
                .contains("Restricted");
    }

    @Test
    void anOfferShowsOnlyRowsNeverAnsweredWhileTheWholePlanStaysForDoctorFix() {
        host.strings.clear();
        SetupPlan whole = plan(SetupMemo.NONE);
        SetupMemo declined = new SetupMemo(
                SetupMemo.Answer.DECLINED,
                1_762_646_400_000L,
                0,
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                0,
                0,
                List.of(SetupChange.EXECUTION_POLICY));

        assertThat(whole.unanswered(SetupMemo.NONE).changes()).isEqualTo(whole.changes());
        assertThat(whole.unanswered(declined).changes())
                .extracting(SetupChange::id)
                .doesNotContain(SetupChange.EXECUTION_POLICY)
                .contains(SetupChange.excludeId(HOME.toString()));
        assertThat(plan(declined).changes()).extracting(SetupChange::id).contains(SetupChange.EXECUTION_POLICY);
        assertThat(declined.answered()).isEqualTo("declined on 2025-11-09");
    }
}
