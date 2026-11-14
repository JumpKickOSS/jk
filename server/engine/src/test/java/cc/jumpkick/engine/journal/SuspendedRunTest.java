// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.time.Suspensions;
import cc.jumpkick.runtime.base.RunMeasures;
import cc.jumpkick.runtime.base.StepLedger;
import cc.jumpkick.testing.FakeClock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * A run the engine stopped running in the middle of (the machine slept): it records the gap, the
 * step in flight across it teaches no estimate, and the steps wholly before or after it do.
 */
class SuspendedRunTest {

    private final FakeClock clock = new FakeClock();
    private final AtomicLong boot = new AtomicLong(5_000_000);
    private final Suspensions watch = Suspensions.forTests(clock, boot::get);

    private void run(Duration d) {
        clock.advance(d);
        boot.addAndGet(d.toMillis());
        watch.poll();
    }

    /** compile-java before the gap, run-tests across it, package-jar after it. */
    private BuildRecord suspendedRun() {
        BuildAccumulator a = new BuildAccumulator("build", "/ws", "g:ws", "cli");
        watch.listen((gap, jobs) -> {
            if (jobs.contains(1L)) a.noteSuspended(gap.startedAt(), gap.wallMs());
        });
        watch.jobStarted(1);
        a.noteTaskStart("", "compile-java", "compile", clock.millis());
        run(Duration.ofSeconds(2));
        a.addTask("", "compile-java", "compile", "SUCCESS", 2_000, 0);
        a.noteTaskStart("", "run-tests", "test", clock.millis());
        run(Duration.ofSeconds(1));
        // The lid closes for 4m 34s with the suite in flight: only the wall and boot clocks move.
        clock.set(clock.instant().plus(Duration.ofSeconds(274)));
        boot.addAndGet(274_000);
        run(Duration.ofSeconds(1));
        a.addTask("", "run-tests", "test", "SUCCESS", 276_000, 0);
        a.noteTaskStart("", "package-jar", "package", clock.millis());
        run(Duration.ofSeconds(1));
        a.addTask("", "package-jar", "package", "SUCCESS", 1_000, 0);
        watch.jobEnded(1);
        return a.toRecord(clock.millis(), false, 279_000, "9.9", null);
    }

    private static BuildRecord.Task step(BuildRecord r, String name) {
        return r.steps().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    private static Map<String, BuildRecord.Task> steps(BuildRecord r) {
        Map<String, BuildRecord.Task> out = new LinkedHashMap<>();
        for (BuildRecord.Task t : r.steps()) out.put(t.name(), t);
        return out;
    }

    @Test
    void the_gap_is_on_the_record_and_only_the_step_in_flight_across_it_is_marked() {
        BuildRecord r = suspendedRun();

        assertThat(r.suspendedMillis()).isEqualTo(274_000);
        assertThat(r.suspensions())
                .containsExactly(new BuildRecord.Suspension(clock.millis() - 1_000 - 274_000, 274_000));
        Map<String, BuildRecord.Task> steps = steps(r);
        assertThat(step(r, "compile-java").suspendedMillis())
                .as("wholly before")
                .isZero();
        assertThat(step(r, "run-tests").suspendedMillis()).as("in flight").isEqualTo(274_000);
        assertThat(step(r, "package-jar").suspendedMillis()).as("wholly after").isZero();

        BuildRecord back = Json.read(Json.write(r));
        assertThat(back.suspensions()).isEqualTo(r.suspensions());
        assertThat(steps(back)).isEqualTo(steps);
    }

    @Test
    void the_step_in_flight_teaches_the_ledger_and_the_stores_nothing_and_the_others_teach_as_usual() {
        BuildRecord r = suspendedRun();

        assertThat(RunStepLedger.sample("/ws", step(r, "compile-java"), 0).status())
                .isEqualTo("SUCCESS");
        assertThat(RunStepLedger.sample("/ws", step(r, "package-jar"), 0).status())
                .isEqualTo("SUCCESS");
        assertThat(RunStepLedger.sample("/ws", step(r, "run-tests"), 0).status())
                .as("kept as run, read by no price")
                .isEqualTo(StepLedger.SUSPENDED);

        String metrics = RunMetricsToml.render(r, new RunMeasures());
        assertThat(metrics).contains("task.compile-java.wall-ms = 2000").contains("task.package-jar.wall-ms = 1000");
        assertThat(metrics)
                .doesNotContain("run-tests")
                .doesNotContain("workspace.wall-ms")
                .doesNotContain("invocation.");
    }

    @Test
    void a_run_that_was_never_suspended_teaches_its_whole_wall() {
        BuildAccumulator a = new BuildAccumulator("build", "/ws", "g:ws", "cli");
        a.noteTaskStart("", "run-tests", "test", 0);
        a.addTask("", "run-tests", "test", "SUCCESS", 2_000, 0);
        BuildRecord r = a.toRecord(10_000, false, 2_000, "9.9", null);

        assertThat(r.suspensions()).isEmpty();
        assertThat(RunMetricsToml.render(r, new RunMeasures()))
                .contains("workspace.wall-ms = 2000")
                .contains("task.run-tests.wall-ms = 2000");
    }
}
