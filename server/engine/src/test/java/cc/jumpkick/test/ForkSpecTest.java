// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import cc.jumpkick.engine.plugin.JobWorkers;
import cc.jumpkick.runtime.base.HostBusy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The launcher starts every JVM from its fork spec: the spec's flags around the module's own JVM
 * arguments and its selection arguments, each of which the spec's stamp token spells.
 */
class ForkSpecTest {

    private static final ForkSpec SPEC = new ForkSpec(
            true,
            false,
            0,
            false,
            List.of(),
            List.of("fast"),
            List.of("slow"),
            List.of("com.acme.FooTest#adds"),
            List.of("*PerfTest"),
            true);

    @Test
    void every_jvm_starts_with_the_specs_flags_around_the_modules_own() {
        JUnitLauncher launcher = new JUnitLauncher().withFork(SPEC).withJvmArgs(List.of("-Dmodule=1"));
        List<String> expected = new ArrayList<>(SPEC.leading());
        expected.add("-Dmodule=1");
        expected.addAll(SPEC.trailing());

        for (JvmRole role : JvmRole.values()) {
            assertThat(launcher.jvmFlags(role, 2, null)).as(role.name()).containsSubsequence(expected);
        }
    }

    @Test
    void the_runner_gets_the_specs_selection_and_the_settled_classes_left_out() {
        List<String> args = new JUnitLauncher()
                .withFork(SPEC)
                .withSettled(List.of("com.acme.DoneTest"))
                .pullWorkerArgs(1, Path.of("classes"));

        assertThat(args).containsAll(SPEC.tagArgs(List.of())).containsAll(SPEC.methodArgs());
        assertThat(args).anyMatch(a -> a.startsWith("--filter=") && a.contains("DoneTest") && a.contains("PerfTest"));
    }

    @Test
    void the_token_spells_what_the_launch_adds() {
        String token = SPEC.token();

        for (String flag : SPEC.leading()) assertThat(token).contains(flag);
        for (String flag : SPEC.trailing()) assertThat(token).contains(flag);
        for (String arg : SPEC.tagArgs(List.of())) assertThat(token).contains(arg);
        SPEC.env().forEach((name, value) -> assertThat(token).contains("env:" + name + "=" + value));
        assertThat(token).contains("include=" + SPEC.classFilter());
        assertThat(token)
                .as("settled classes are the ledger's, under the same stamp")
                .doesNotContain("DoneTest");
    }

    @Test
    void only_a_launch_whose_jvms_may_start_their_own_engines_drives_engines() {
        assertThat(homes(true, false).drivesEngines()).isTrue();
        assertThat(homes(true, true).drivesEngines())
                .as("the fast tier starts no engine")
                .isFalse();
        assertThat(homes(false, false).drivesEngines()).isFalse();
    }

    /**
     * A pooled JVM that starts engines as children leases no slot; one of the engine's own suite,
     * which runs them in-process, keeps its slot; both are priced at their process tree, except in
     * the fast tier, which starts no engine.
     */
    @Test
    void a_pooled_jvm_is_leased_by_where_its_engines_run() {
        assertThat(lease(homes(true, false), false)).isEqualTo(JobWorkers.Lease.DRIVES_ENGINES);
        assertThat(lease(homes(false, false), true)).isEqualTo(JobWorkers.Lease.RUNS_ENGINES);
        assertThat(lease(homes(false, true), true))
                .as("the fast tier starts no engine")
                .isEqualTo(JobWorkers.Lease.SLOT);
        assertThat(lease(homes(false, false), false)).isEqualTo(JobWorkers.Lease.SLOT);
    }

    /**
     * A test JVM that drives an engine leaves room for that engine's work: an auto pool of them runs
     * at half the share, never below one. Every other pool keeps the whole share.
     */
    @Test
    void a_pool_that_drives_engines_runs_at_half_the_share() {
        JUnitLauncher driving = new JUnitLauncher().withFork(homes(true, false));
        assertThat(driving.poolShare(7)).isEqualTo(3);
        assertThat(driving.poolShare(10)).isEqualTo(5);
        assertThat(driving.poolShare(1)).isEqualTo(1);
        assertThat(new JUnitLauncher().withFork(homes(true, true)).poolShare(7))
                .as("the fast tier starts no engine")
                .isEqualTo(7);
        assertThat(new JUnitLauncher().withFork(homes(false, false)).poolShare(7))
                .isEqualTo(7);
    }

    /**
     * An engine-driving pool starts at two idle cores a runner, not at the share: the cores another
     * build is using are not free for its engines. Every other pool starts at the share.
     */
    @Test
    void a_pool_that_drives_engines_starts_at_the_idle_cores() {
        JUnitLauncher driving = new JUnitLauncher().withFork(homes(true, false));
        assertThat(driving.startShare(3, 0.3, 7)).as("an idle host").isEqualTo(3);
        assertThat(driving.startShare(3, 3.2, 7)).as("three cores free").isEqualTo(1);
        assertThat(driving.startShare(12, 8.0, 24)).isEqualTo(8);
        assertThat(driving.startShare(3, 11.0, 7)).as("an oversubscribed host").isEqualTo(1);
        assertThat(driving.startShare(3, -1, 7)).as("no load average").isEqualTo(3);
        assertThat(new JUnitLauncher().withFork(homes(false, false)).startShare(7, 11.0, 7))
                .isEqualTo(7);
    }

    /**
     * An engine-driving pool reads the cores busy outside its engine, not the load average: on a
     * 24-core host at load 27, nearly all of it this build's own suites, it starts wide, where the
     * load average alone started it at one runner. Where that share is unknown it falls back to the
     * load average, and every other pool reads the load average.
     */
    @Test
    void a_pool_that_drives_engines_does_not_count_its_own_engines_load() {
        JUnitLauncher driving = new JUnitLauncher().withFork(homes(true, false));
        try {
            HostBusy.forTests(() -> 0.1);
            assertThat(driving.poolLoad(27.0, 24)).isCloseTo(2.4, within(1e-9));
            assertThat(driving.startShare(12, driving.poolLoad(27.0, 24), 24)).isEqualTo(10);
            assertThat(driving.startShare(12, 27.0, 24))
                    .as("the load average alone")
                    .isEqualTo(1);
            assertThat(new JUnitLauncher().withFork(homes(false, false)).poolLoad(27.0, 24))
                    .isEqualTo(27.0);
            HostBusy.forTests(() -> -1);
            assertThat(driving.poolLoad(27.0, 24)).as("no external share").isEqualTo(27.0);
        } finally {
            HostBusy.forTests(null);
        }
    }

    /** Each start and widening of a pool logs one line naming its width, share and load. */
    @Test
    void a_pool_logs_its_width_share_and_load() {
        assertThat(JUnitLauncher.poolLine(
                        "jk-cli-e2e", "starts at 6 runners, 10 by the idle cores", 12, 27.314, 2.4, 24))
                .isEqualTo("test pool jk-cli-e2e: starts at 6 runners, 10 by the idle cores;"
                        + " share 12, load1 27.31, sized by load 2.40 on 24 processors");
    }

    /** An engine-driving pool widens only while two cores sit idle; every other pool is bounded by its leases. */
    @Test
    void a_pool_that_drives_engines_widens_only_into_idle_cores() {
        JUnitLauncher driving = new JUnitLauncher().withFork(homes(true, false));
        assertThat(driving.coresForAnother(4.5, 7)).isTrue();
        assertThat(driving.coresForAnother(5.5, 7)).isFalse();
        assertThat(driving.coresForAnother(-1, 7)).as("no load average").isTrue();
        assertThat(new JUnitLauncher().withFork(homes(false, false)).coresForAnother(20, 7))
                .isTrue();
    }

    private static JobWorkers.Lease lease(ForkSpec fork, boolean runsEngines) {
        return new JUnitLauncher().withFork(fork).withRunsEngines(runsEngines).poolLease();
    }

    private static ForkSpec homes(boolean workerHomes, boolean walled) {
        return new ForkSpec(true, false, 0, workerHomes, List.of(), List.of(), List.of(), List.of(), List.of(), walled);
    }

    @Test
    void the_settings_it_carries_each_move_the_token() {
        String base = ForkSpec.DEFAULT.token();
        List<ForkSpec> moved = List.of(
                new ForkSpec(false, false, 0, false, List.of(), List.of(), List.of(), List.of(), List.of(), false),
                new ForkSpec(true, true, 0, false, List.of(), List.of(), List.of(), List.of(), List.of(), false),
                new ForkSpec(true, false, 5, false, List.of(), List.of(), List.of(), List.of(), List.of(), false),
                new ForkSpec(true, false, 0, true, List.of(), List.of(), List.of(), List.of(), List.of(), false),
                new ForkSpec(true, false, 0, false, List.of("db"), List.of(), List.of(), List.of(), List.of(), false),
                new ForkSpec(true, false, 0, false, List.of(), List.of(), List.of(), List.of(), List.of("X"), false),
                new ForkSpec(true, false, 0, false, List.of(), List.of(), List.of(), List.of(), List.of(), true));
        for (ForkSpec spec : moved) assertThat(spec.token()).as(spec.toString()).isNotEqualTo(base);
    }
}
