// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.jsonl.Jsonl;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The drain-status channel over a fake transport: no socket, no successor, no engine. The report
 * loop is driven on the calling thread wherever possible, so every assertion is about what was
 * sent and in what order.
 */
class DrainReporterTest {

    private static final long PID = 4242L;
    private static final String VERSION = "9.9.9-test";

    /** Records every line; can be told to fail a send, or to go dead after one. */
    private static class FakeLink implements DrainReporter.Link {
        final List<String> sent = Collections.synchronizedList(new ArrayList<>());
        volatile boolean open = true;
        volatile boolean closed;
        int failOnSend = -1;
        int diesAfterSends = -1;

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void send(String line) throws IOException {
            if (sent.size() == failOnSend) throw new IOException("successor went away");
            sent.add(line);
            if (sent.size() == diesAfterSends) open = false;
        }

        @Override
        public void close() {
            closed = true;
            open = false;
        }
    }

    /** Hands out prepared links, then fresh ones, and records every reconnect. */
    private static class FakeConnector implements DrainReporter.Connector {
        final List<FakeLink> handed = Collections.synchronizedList(new ArrayList<>());
        private final List<FakeLink> queued = Collections.synchronizedList(new ArrayList<>());

        FakeConnector(FakeLink... links) {
            Collections.addAll(queued, links);
        }

        @Override
        public DrainReporter.Link connect(Path successor) {
            FakeLink link = queued.isEmpty() ? new FakeLink() : queued.remove(0);
            handed.add(link);
            return link;
        }

        List<String> linesOn(int index) {
            return handed.get(index).sent;
        }
    }

    /** {@code true} for the first {@code n} polls, then {@code false} — a drain that ends. */
    private static BooleanSupplier drainingFor(int n) {
        AtomicInteger polls = new AtomicInteger();
        return () -> polls.getAndIncrement() < n;
    }

    private DrainReporter reporter(
            Path successor, BooleanSupplier stillDraining, int plans, DrainReporter.Connector connector) {
        return new DrainReporter(PID, VERSION, () -> successor, stillDraining, () -> plans, connector, s -> {}, 0L);
    }

    @Test
    void greets_the_successor_once_then_reports_plans_each_tick_and_signs_off(@TempDir Path tmp) throws Exception {
        Path successor = Files.createFile(tmp.resolve("successor.sock"));
        FakeConnector connector = new FakeConnector();

        reporter(successor, drainingFor(2), 3, connector).report();

        assertThat(connector.handed).hasSize(1);
        List<String> sent = connector.linesOn(0);
        assertThat(sent).hasSize(4);
        assertThat(EngineProtocol.typeOf(sent.get(0))).isEqualTo(EngineProtocol.HELLO);
        assertThat(EngineProtocol.typeOf(sent.get(1))).isEqualTo(EngineProtocol.DRAIN_STATUS);
        assertThat(Jsonl.longValue(sent.get(1), "pid", -1)).isEqualTo(PID);
        assertThat(Jsonl.intValue(sent.get(1), "plans", -1)).isEqualTo(3);
        assertThat(Jsonl.str(sent.get(1), "version")).isEqualTo(VERSION);
        assertThat(EngineProtocol.typeOf(sent.get(2))).isEqualTo(EngineProtocol.DRAIN_STATUS);
        assertThat(EngineProtocol.typeOf(sent.get(3)))
                .as("the last thing a lame duck says")
                .isEqualTo(EngineProtocol.DRAIN_DONE);
        assertThat(Jsonl.longValue(sent.get(3), "pid", -1)).isEqualTo(PID);
        assertThat(connector.handed.get(0).closed).isTrue();
    }

    @Test
    void a_voluntary_stop_with_no_successor_listening_never_connects(@TempDir Path tmp) {
        FakeConnector connector = new FakeConnector();

        reporter(tmp.resolve("nobody.sock"), drainingFor(3), 1, connector).report();

        assertThat(connector.handed).isEmpty();
    }

    @Test
    void a_send_that_fails_reopens_the_connection_and_greets_it_again(@TempDir Path tmp) throws Exception {
        Path successor = Files.createFile(tmp.resolve("successor.sock"));
        FakeLink first = new FakeLink();
        first.failOnSend = 2; // hello, one drain-status, then the next tick's drain-status fails
        FakeLink second = new FakeLink();

        FakeConnector connector = new FakeConnector(first, second);
        reporter(successor, drainingFor(3), 1, connector).report();

        assertThat(connector.handed).containsExactly(first, second);
        assertThat(first.closed).isTrue();
        assertThat(EngineProtocol.typeOf(second.sent.get(0)))
                .as("a fresh connection is greeted before it is reported to")
                .isEqualTo(EngineProtocol.HELLO);
        assertThat(EngineProtocol.typeOf(second.sent.get(second.sent.size() - 1)))
                .isEqualTo(EngineProtocol.DRAIN_DONE);
    }

    @Test
    void a_link_that_has_gone_dead_is_replaced_before_the_next_report(@TempDir Path tmp) throws Exception {
        Path successor = Files.createFile(tmp.resolve("successor.sock"));
        FakeLink first = new FakeLink();
        first.diesAfterSends = 2; // hello + one drain-status, then the peer is gone
        FakeLink second = new FakeLink();

        FakeConnector connector = new FakeConnector(first, second);
        reporter(successor, drainingFor(2), 1, connector).report();

        assertThat(connector.handed).containsExactly(first, second);
        assertThat(first.closed).isTrue();
        assertThat(EngineProtocol.typeOf(second.sent.get(0))).isEqualTo(EngineProtocol.HELLO);
    }

    /**
     * The shutdown message and the displacement watchdog can both enter drain. Two reporters would
     * open two connections and double-report the same engine's plan count.
     */
    @Test
    void only_one_reporter_ever_runs_however_often_drain_is_entered(@TempDir Path tmp) throws Exception {
        Path successor = Files.createFile(tmp.resolve("successor.sock"));
        AtomicBoolean draining = new AtomicBoolean(true);
        CountDownLatch connected = new CountDownLatch(1);
        FakeConnector connector = new FakeConnector() {
            @Override
            public DrainReporter.Link connect(Path dest) {
                DrainReporter.Link link = super.connect(dest);
                connected.countDown();
                return link;
            }
        };
        DrainReporter reporter =
                new DrainReporter(PID, VERSION, () -> successor, draining::get, () -> 1, connector, s -> {}, 1L);

        reporter.start();
        reporter.start();
        assertThat(connected.await(5, TimeUnit.SECONDS))
                .as("the reporter did run")
                .isTrue();
        draining.set(false);
        waitUntil(() -> connector.handed.get(0).closed);

        assertThat(connector.handed).hasSize(1);
        assertThat(connector.linesOn(0))
                .filteredOn(l -> EngineProtocol.DRAIN_DONE.equals(EngineProtocol.typeOf(l)))
                .hasSize(1);
    }

    @Test
    void a_predecessor_is_announced_once_while_it_drains_and_once_when_it_finishes() {
        List<String> log = new ArrayList<>();
        DrainReporter r =
                new DrainReporter(PID, VERSION, () -> null, () -> false, () -> 0, new FakeConnector(), log::add, 0L);

        r.predecessorDraining(77, 2);
        r.predecessorDraining(77, 1);
        r.predecessorFinished(77);

        assertThat(log)
                .containsExactly(
                        "jk engine: predecessor pid 77 is draining (2 job(s))",
                        "jk engine: predecessor pid 77 finished draining");
    }

    @Test
    void a_report_carrying_no_pid_is_not_announced() {
        List<String> log = new ArrayList<>();
        DrainReporter r =
                new DrainReporter(PID, VERSION, () -> null, () -> false, () -> 0, new FakeConnector(), log::add, 0L);

        r.predecessorDraining(-1, 3);
        r.predecessorFinished(-1);

        assertThat(log).isEmpty();
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition not met within 5s");
            Thread.sleep(5);
        }
    }
}
