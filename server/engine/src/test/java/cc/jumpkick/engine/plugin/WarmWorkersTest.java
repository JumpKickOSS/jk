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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WarmWorkersTest {

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
        List<String> command = List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dflavor=" + flavor,
                "-cp",
                System.getProperty("java.class.path"),
                ServeFixture.class.getName(),
                "--serve");
        return new WarmWorkers.Launch(
                "test worker",
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
