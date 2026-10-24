// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jsonl.Jsonl;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WarmWorkersTest {

    @BeforeEach
    void normalHostPressure() {
        HostPressure.forTests(HostPressure.Level.NORMAL);
    }

    @AfterEach
    void hostPressureFromTheHost() {
        HostPressure.forTests(null);
    }

    @TempDir
    Path dir;

    @BeforeEach
    void linger() {
        WarmLinger.forTests(60_000);
    }

    @AfterEach
    void stop() {
        WarmWorkers.shutdown();
        WarmLinger.forTests(-1);
        WarmLinger.ciForTests(null);
    }

    /** A run's pid line, chatter and protocol lines. */
    private static final class Seen implements WarmWorkers.Listener {
        long pid = -1;
        final List<String> output = new ArrayList<>();

        @Override
        public void protocol(String json) {
            if ("pid".equals(Jsonl.str(json, "t"))) pid = Jsonl.longValue(json, "pid", -1);
        }

        @Override
        public void output(String line) {
            output.add(line);
        }
    }

    private WarmWorkers.Launch launch(String flavor) {
        return launch(flavor, null);
    }

    private WarmWorkers.Launch launch(String flavor, @Nullable String xmx) {
        return launch("test worker", flavor, xmx);
    }

    private WarmWorkers.Launch launch(String name, String flavor, @Nullable String xmx) {
        List<String> command = new ArrayList<>(
                List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Dflavor=" + flavor));
        if (xmx != null) command.add("-Xmx" + xmx);
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), ServeFixture.class.getName(), "--serve"));
        return new WarmWorkers.Launch(
                name,
                command,
                WorkerEnv.strict(),
                ServeFixture.PREFIX,
                new HeapScope.Key(
                        dir, "warm", HeapScope.PLUGIN, Runtime.version().feature()));
    }

    private Seen run(WarmWorkers.Launch launch, String what, WarmWorkers.Outcome[] outcome) throws Exception {
        Path spec = Files.createTempFile(dir, "spec", ".txt");
        Files.writeString(spec, what + "\n");
        Seen seen = new Seen();
        outcome[0] = WarmWorkers.run(launch, spec, seen, () -> false);
        return seen;
    }

    @Test
    void runs_of_one_launch_share_a_worker_and_another_launch_gets_its_own() throws Exception {
        WarmWorkers.Outcome[] out = new WarmWorkers.Outcome[1];
        Seen first = run(launch("a"), "ok", out);
        assertThat(out[0]).isEqualTo(new WarmWorkers.Outcome(2, false));
        assertThat(first.output).contains("chatter for ok");
        Seen second = run(launch("a"), "okay", out);
        assertThat(out[0].exit()).isEqualTo(4);
        assertThat(second.pid).isEqualTo(first.pid);
        Seen other = run(launch("b"), "ok", out);
        assertThat(other.pid).isNotEqualTo(first.pid);
        assertThat(WarmWorkers.idleCount()).isEqualTo(2);
    }

    @Test
    void a_run_that_maps_a_startup_cache_shares_the_worker_that_started_without_one() throws Exception {
        WarmWorkers.Outcome[] out = new WarmWorkers.Outcome[1];
        long cold = run(launch("a"), "ok", out).pid;

        WarmWorkers.Launch mapped = launch("a");
        List<String> command = new ArrayList<>(mapped.command());
        command.addAll(1, List.of("-XX:AOTCache=" + dir.resolve("w.aot"), "-Xlog:aot=off"));
        mapped = new WarmWorkers.Launch(mapped.name(), command, mapped.env(), mapped.prefix(), mapped.heapKey());

        assertThat(run(mapped, "ok", out).pid).isEqualTo(cold);
        assertThat(WarmWorkers.idleCount()).isEqualTo(1);
    }

    @Test
    void an_idle_worker_with_enough_heap_serves_a_run_that_asks_for_less() throws Exception {
        WarmWorkers.Outcome[] out = new WarmWorkers.Outcome[1];
        long big = run(launch("a", "256m"), "ok", out).pid;

        assertThat(run(launch("a", "64m"), "ok", out).pid).as("256m covers 64m").isEqualTo(big);
        long bigger = run(launch("a", "512m"), "ok", out).pid;
        assertThat(bigger).as("256m does not cover 512m").isNotEqualTo(big);
        assertThat(run(launch("a"), "ok", out).pid)
                .as("a command with no heap takes only a worker with none")
                .isNotIn(big, bigger);
    }

    @Test
    void an_idle_worker_a_bigger_idle_sibling_covers_exits() throws Exception {
        WarmWorkers.Outcome[] out = new WarmWorkers.Outcome[1];
        run(launch("a", "64m"), "ok", out);
        run(launch("a", "256m"), "ok", out);

        assertThat(WarmWorkers.idleCount())
                .as("the 64m worker exits once the 256m one is idle")
                .isEqualTo(1);
        long pid = run(launch("a", "64m"), "ok", out).pid;
        assertThat(run(launch("a", "128m"), "ok", out).pid).isEqualTo(pid);
    }

    @Test
    void a_worker_that_dies_mid_run_reports_its_exit_and_output_and_is_not_reused() throws Exception {
        WarmWorkers.Outcome[] out = new WarmWorkers.Outcome[1];
        Seen first = run(launch("a"), "ok", out);
        Seen dead = run(launch("a"), "oom", out);
        assertThat(out[0]).isEqualTo(new WarmWorkers.Outcome(3, true));
        assertThat(WorkerFate.heapExhausted(out[0].exit(), String.join("\n", dead.output)))
                .isTrue();
        Seen next = run(launch("a"), "ok", out);
        assertThat(next.pid).isNotEqualTo(first.pid);
    }

    @Test
    void a_retiring_run_ends_its_worker() throws Exception {
        WarmWorkers.Outcome[] out = new WarmWorkers.Outcome[1];
        Seen retired = run(launch("a"), "retire", out);
        assertThat(out[0]).isEqualTo(new WarmWorkers.Outcome(6, false));
        assertThat(WarmWorkers.idleCount()).isZero();
        assertThat(ProcessHandle.of(retired.pid).map(ProcessHandle::isAlive).orElse(false))
                .isFalse();
    }

    @Test
    void host_memory_pressure_ends_idle_workers_and_leaves_a_busy_one() throws Exception {
        WarmWorkers.Outcome[] out = new WarmWorkers.Outcome[1];
        long idle = run(launch("a"), "ok", out).pid;
        Path spec = Files.createTempFile(dir, "spec", ".txt");
        Files.writeString(spec, "hang\n");
        Seen busy = new Seen();
        AtomicBoolean cancel = new AtomicBoolean();
        Thread running = Thread.ofVirtual().start(() -> {
            try {
                WarmWorkers.run(launch("b"), spec, busy, cancel::get);
            } catch (Exception e) {
                // the run ends with the test
            }
        });
        try {
            for (int i = 0; i < 200 && busy.pid < 0; i++) Thread.sleep(25);
            assertThat(busy.pid).isPositive();

            HostPressure.forTests(HostPressure.Level.WARN);
            assertThat(WarmWorkers.shedIdle(HostPressure.WARN_IDLE))
                    .as("under warn a worker idle for less than the grace stays")
                    .isZero();
            HostPressure.forTests(HostPressure.Level.CRITICAL);
            HostPressure.check();

            assertThat(WarmWorkers.idleCount()).isZero();
            assertThat(ProcessHandle.of(idle).map(ProcessHandle::isAlive).orElse(false))
                    .as("the idle worker exits")
                    .isFalse();
            assertThat(ProcessHandle.of(busy.pid).map(ProcessHandle::isAlive).orElse(false))
                    .as("the busy one is left to its run")
                    .isTrue();
        } finally {
            HostPressure.forTests(null);
            cancel.set(true);
            running.join();
        }
    }

    @Test
    void on_ci_the_latest_kotlinc_worker_outlives_the_linger_and_the_others_do_not() throws Exception {
        WarmLinger.ciForTests(true);
        WarmLinger.forTests(100);
        WarmWorkers.Outcome[] out = new WarmWorkers.Outcome[1];
        long older = run(launch(WarmLinger.KOTLIN_COMPILER, "a", null), "ok", out).pid;
        // Past the linger while the first is still the latest: its reaper has already kept it.
        Thread.sleep(300);
        long latest = run(launch(WarmLinger.KOTLIN_COMPILER, "b", null), "ok", out).pid;
        long other = run(launch("formatter", "c", null), "ok", out).pid;

        for (int i = 0; i < 200 && (alive(older) || alive(other)); i++) Thread.sleep(25);

        assertThat(alive(older)).as("an older kotlinc worker idles out").isFalse();
        assertThat(alive(other)).as("another tool keeps the linger").isFalse();
        assertThat(alive(latest)).as("the latest kotlinc worker stays").isTrue();
        assertThat(run(launch(WarmLinger.KOTLIN_COMPILER, "b", null), "ok", out).pid)
                .isEqualTo(latest);
    }

    @Test
    void on_ci_a_linger_of_zero_still_keeps_the_latest_kotlinc_worker() throws Exception {
        WarmLinger.ciForTests(true);
        WarmLinger.forTests(0);
        WarmWorkers.Outcome[] out = new WarmWorkers.Outcome[1];
        long kept = run(launch(WarmLinger.KOTLIN_COMPILER, "a", null), "ok", out).pid;
        long gone = run(launch("formatter", "c", null), "ok", out).pid;

        assertThat(run(launch(WarmLinger.KOTLIN_COMPILER, "a", null), "ok", out).pid)
                .isEqualTo(kept);
        for (int i = 0; i < 200 && alive(gone); i++) Thread.sleep(25);
        assertThat(alive(gone)).isFalse();
    }

    private static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    @Test
    void a_cancelled_run_stops_its_worker() throws Exception {
        Path spec = Files.createTempFile(dir, "spec", ".txt");
        Files.writeString(spec, "hang\n");
        Seen seen = new Seen();
        AtomicBoolean cancel = new AtomicBoolean();
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
                // the test is ending
            }
            cancel.set(true);
        });
        WarmWorkers.Outcome out = WarmWorkers.run(launch("a"), spec, seen, cancel::get);
        assertThat(out.died()).isTrue();
        assertThat(seen.pid).isPositive();
        assertThat(ProcessHandle.of(seen.pid).map(ProcessHandle::isAlive).orElse(false))
                .isFalse();
        assertThat(WarmWorkers.idleCount()).isZero();
    }

    @Test
    void with_no_linger_a_worker_exits_after_its_run() throws Exception {
        WarmLinger.forTests(0);
        WarmWorkers.Outcome[] out = new WarmWorkers.Outcome[1];
        Seen seen = run(launch("a"), "ok", out);
        assertThat(out[0].died()).isFalse();
        assertThat(WarmWorkers.idleCount()).isZero();
        assertThat(ProcessHandle.of(seen.pid).map(ProcessHandle::isAlive).orElse(false))
                .isFalse();
    }

    @Test
    void an_idle_worker_exits_once_the_linger_passes() throws Exception {
        WarmLinger.forTests(300);
        WarmWorkers.Outcome[] out = new WarmWorkers.Outcome[1];
        Seen seen = run(launch("a"), "ok", out);
        assertThat(WarmWorkers.idleCount()).isEqualTo(1);
        ProcessHandle handle = ProcessHandle.of(seen.pid).orElseThrow();
        handle.onExit().get(10, TimeUnit.SECONDS);
        assertThat(WarmWorkers.idleCount()).isZero();
    }

    @Test
    void the_spec_is_sealed_with_the_network_policy_before_the_worker_reads_it() throws Exception {
        Path spec = Files.createTempFile(dir, "spec", ".txt");
        Files.writeString(spec, "ok\n");
        WarmWorkers.run(launch("a"), spec, new Seen(), () -> false);
        assertThat(Files.readAllLines(spec)).hasSizeGreaterThan(1);
    }
}
