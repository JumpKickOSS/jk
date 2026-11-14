// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.winhost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.changes.Change;
import cc.jumpkick.changes.ChangeActor;
import cc.jumpkick.changes.ChangeJournal;
import cc.jumpkick.config.Request;
import cc.jumpkick.host.time.Clock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MachineSetupTest {

    @TempDir
    Path state;

    private final Forbidden rules =
            new Forbidden("C:\\Users\\ada", "C:\\Users\\ada\\.jk", "C:\\Windows", List.of("C:\\Program Files"), true);

    private static final Clock FIXED = new Clock() {
        @Override
        public long millis() {
            return 1_000L;
        }

        @Override
        public long nanos() {
            return 0L;
        }
    };

    private final List<String> scripts = new ArrayList<>();
    private boolean policyCalled;

    private static SetupChange exclude(String path) {
        return new SetupChange(
                SetupChange.excludeId(path), "Exclude " + path, "why", "effect", SetupChange.Kind.EXCLUDE, path);
    }

    private static final SetupChange POLICY = new SetupChange(
            SetupChange.EXECUTION_POLICY, "policy", "why", "effect", SetupChange.Kind.EXECUTION_POLICY, null);

    private final SetupPlan plan = new SetupPlan(
            List.of(
                    exclude("C:\\Users\\ada\\.jk"),
                    exclude("C:\\Users\\ada\\IdeaProjects"),
                    exclude("D:\\src"),
                    POLICY),
            1_900L,
            "C:\\Users\\ada\\.jk");

    private MachineSetup setup(Elevator elevator) {
        UserCommands user = new UserCommands() {
            @Override
            public boolean executionPolicy() {
                policyCalled = true;
                return true;
            }
        };
        return new MachineSetup(
                elevator,
                user,
                rules,
                SetupMemo.file(state),
                state.resolve("result.txt"),
                FIXED,
                folder -> 900L,
                List.of(environment));
    }

    /** An elevator that "runs" the script by writing {@code lines} to its result file. */
    private Elevator writing(String... lines) {
        return (script, timeout) -> {
            scripts.add(script);
            try {
                Files.write(state.resolve("result.txt"), List.of(lines));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return new Elevator.Outcome.Ran(0);
        };
    }

    /** An environment row of a made-up kind: proposes two environments, installs by reporting steps. */
    private final List<String> installed = new ArrayList<>();

    private final SetupEnvironment environment = new SetupEnvironment() {
        @Override
        public List<SetupChange> rows(WindowsHostCheck check) {
            return List.of(row("alpha"), row("beta"));
        }

        @Override
        public boolean owns(SetupChange row) {
            return row.id().startsWith("fake:");
        }

        @Override
        public MachineSetup.Result install(SetupChange row, Consumer<String> progress) {
            progress.accept("fetched");
            progress.accept("unpacked");
            installed.add(row.id());
            return new MachineSetup.Result(row.id(), true, "installed into " + row.path());
        }
    };

    /** An environment source that proposes {@code rows} and installs nothing. */
    private static SetupEnvironment proposing(SetupChange... rows) {
        return new SetupEnvironment() {
            @Override
            public List<SetupChange> rows(WindowsHostCheck check) {
                return List.of(rows);
            }

            @Override
            public boolean owns(SetupChange row) {
                return false;
            }

            @Override
            public MachineSetup.Result install(SetupChange row, Consumer<String> progress) {
                return new MachineSetup.Result(row.id(), false, "not installed");
            }
        };
    }

    private static SetupChange row(String name) {
        return new SetupChange(
                "fake:" + name, "JumpKick in " + name, "why", "effect", SetupChange.Kind.ENVIRONMENT, "fake:" + name);
    }

    @Test
    void anEnvironmentRowRunsWithoutElevationReportsItsStepsAndEachRowIsRememberedOnItsOwn() throws Exception {
        SetupPlan withEnvironments = new SetupPlan(List.of(POLICY, row("alpha"), row("beta")));

        MachineSetup.Applied applied =
                setup(writing()).apply(withEnvironments, Set.of(SetupChange.EXECUTION_POLICY, "fake:alpha"));

        assertThat(scripts).as("no UAC prompt for per-user rows").isEmpty();
        assertThat(installed).containsExactly("fake:alpha");
        assertThat(applied.lines()).contains("installed into fake:alpha (fetched; unpacked)");
        SetupMemo memo = SetupMemo.read(SetupMemo.file(state));
        assertThat(memo.answeredRows())
                .containsExactlyInAnyOrder(SetupChange.EXECUTION_POLICY, "fake:alpha", "fake:beta");
        WindowsHostCheck check = new WindowsHostCheck(new FakeHost(), memo, Path.of("C:\\Users\\ada\\.jk"), null);
        assertThat(SetupPlan.unanswered(check, List.of(), folder -> 1L, List.of(environment))
                        .isEmpty())
                .as("beta was declined, not asked again")
                .isTrue();

        SetupEnvironment grown = proposing(row("alpha"), row("beta"), row("gamma"));
        assertThat(SetupPlan.unanswered(check, List.of(), folder -> 1L, List.of(grown))
                        .changes())
                .extracting(SetupChange::id)
                .containsExactly("fake:gamma");
        SetupPlan later = SetupPlan.of(check, List.of(), folder -> 1L, List.of(grown));
        assertThat(WindowsSetup.items(later)).extracting(m -> m.get("id")).contains("fake:alpha", "fake:gamma");
    }

    @Test
    void oneElevationForEveryChosenRootThenTheUserChangesAndTheMemo() throws Exception {
        MachineSetup.Applied applied = setup(writing(
                        "verified\tC:\\Users\\ada\\.jk",
                        "missing\tC:\\Users\\ada\\IdeaProjects\tDefender still scans it"))
                .apply(
                        plan,
                        Set.of(
                                SetupChange.excludeId("C:\\Users\\ada\\.jk"),
                                SetupChange.excludeId("C:\\Users\\ada\\IdeaProjects"),
                                SetupChange.EXECUTION_POLICY));
        assertThat(scripts).hasSize(1);
        assertThat(scripts.getFirst())
                .contains("'C:\\Users\\ada\\.jk','C:\\Users\\ada\\IdeaProjects'")
                .doesNotContain("D:\\src");
        assertThat(policyCalled).isTrue();
        assertThat(applied.results())
                .extracting(MachineSetup.Result::change, MachineSetup.Result::ok)
                .containsExactly(
                        tuple(SetupChange.excludeId("C:\\Users\\ada\\.jk"), true),
                        tuple(SetupChange.excludeId("C:\\Users\\ada\\IdeaProjects"), false),
                        tuple(SetupChange.excludeId("C:\\Users\\ada\\.jk"), true),
                        tuple(SetupChange.EXECUTION_POLICY, true));
        assertThat(applied.lines())
                .contains("build files in C:\\Users\\ada\\.jk: 1.9 s -> 900 ms per 1,000 with the exclusion");
        SetupMemo memo = SetupMemo.read(SetupMemo.file(state));
        assertThat(memo.answer()).isEqualTo(SetupMemo.Answer.ACCEPTED);
        assertThat(memo.verified()).containsExactly("C:\\Users\\ada\\.jk");
        assertThat(memo.notExcluded()).containsExactly("C:\\Users\\ada\\IdeaProjects");
        assertThat(memo.declined()).containsExactly("D:\\src");
        assertThat(memo.checkedAt()).isEqualTo(1_000L);
        assertThat(memo.beforeMs()).isEqualTo(1_900L);
        assertThat(memo.afterMs()).isEqualTo(900L);
    }

    @Test
    void anAppliedSetupIsJournaledWithEachResultAndADeclineIsNot() throws Exception {
        Path journal = state.resolve("changes");
        ChangeActor agent = ChangeActor.agent("s1", "Cursor", "speed up builds", "doctor");
        ChangeJournal.into(
                journal,
                () -> Request.actingAs(agent, () -> setup(writing(
                                "verified\tC:\\Users\\ada\\.jk",
                                "missing\tC:\\Users\\ada\\IdeaProjects\tDefender still scans it"))
                        .apply(
                                plan,
                                Set.of(
                                        SetupChange.excludeId("C:\\Users\\ada\\.jk"),
                                        SetupChange.excludeId("C:\\Users\\ada\\IdeaProjects")))));
        ChangeJournal.into(journal, () -> setup((s, t) -> new Elevator.Outcome.Declined())
                .apply(plan, Set.of(SetupChange.excludeId("D:\\src"))));

        List<Change> entries = new ChangeJournal(journal).page(null, 10).entries();
        assertThat(entries).hasSize(1);
        Change entry = entries.getFirst();
        assertThat(entry.action()).isEqualTo(Change.APPLY);
        assertThat(entry.summary()).isEqualTo("applied machine setup: 1 change");
        assertThat(entry.diff()).contains("+ ").contains("- ").contains("IdeaProjects");
        assertThat(entry.actor().details()).containsEntry("client", "Cursor").containsEntry("goal", "speed up builds");
    }

    @Test
    void decliningThePromptChangesNothingAndRecordsTheDecline() throws Exception {
        MachineSetup.Applied applied = setup((s, t) -> new Elevator.Outcome.Declined())
                .apply(plan, Set.of(SetupChange.excludeId("D:\\src"), SetupChange.EXECUTION_POLICY));
        assertThat(applied.declined()).isTrue();
        assertThat(policyCalled).isFalse();
        SetupMemo memo = SetupMemo.read(SetupMemo.file(state));
        assertThat(memo.answer()).isEqualTo(SetupMemo.Answer.DECLINED);
        assertThat(memo.declined()).hasSize(3);
    }

    @Test
    void aPromptThatCannotShowPrintsTheCommandsAndRecordsNoAnswer() throws Exception {
        MachineSetup.Applied applied = setup((s, t) -> new Elevator.Outcome.Failed(5, "no desktop"))
                .apply(plan, Set.of(SetupChange.excludeId("D:\\src")));
        assertThat(applied.lines()).anyMatch(l -> l.contains("Add-MpPreference -ExclusionPath 'D:\\src'"));
        assertThat(SetupMemo.read(SetupMemo.file(state)).answer()).isNull();
    }

    @Test
    void policyRefusalsAreReportedPerRoot() throws Exception {
        MachineSetup.Applied applied = setup(writing("failed\tD:\\src\tOperation blocked by Group Policy"))
                .apply(plan, Set.of(SetupChange.excludeId("D:\\src")));
        assertThat(applied.lines()).containsExactly("failed D:\\src: Operation blocked by Group Policy");
        assertThat(SetupMemo.read(SetupMemo.file(state)).refused())
                .containsEntry("D:\\src", "Operation blocked by Group Policy");
    }

    @Test
    void onlyUserChangesNeedNoElevation() throws Exception {
        setup((s, t) -> {
                    throw new AssertionError("no prompt expected");
                })
                .apply(plan, Set.of(SetupChange.EXECUTION_POLICY));
        assertThat(policyCalled).isTrue();
    }

    @Test
    void decliningTheDecisionRecordsEveryProposedRoot() throws Exception {
        setup((s, t) -> new Elevator.Outcome.Ran(0)).decline(plan, "declined");
        SetupMemo memo = SetupMemo.read(SetupMemo.file(state));
        assertThat(memo.answer()).isEqualTo(SetupMemo.Answer.DECLINED);
        assertThat(memo.declined()).containsExactly("C:\\Users\\ada\\.jk", "C:\\Users\\ada\\IdeaProjects", "D:\\src");
        assertThat(memo.answeredAt()).isEqualTo(1_000L);
    }
}
