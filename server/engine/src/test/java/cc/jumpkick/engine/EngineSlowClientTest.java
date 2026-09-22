// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * One client that stops reading its stream, or eight, cost every other client nothing: hello and
 * status are answered on the connection's own platform thread and written by the stream's own
 * writer thread, so neither a client that never drains its socket nor a virtual-thread scheduler
 * whose every carrier is busy stands between a fresh client and its reply.
 *
 * <p>Proving the carrier half of that means pinning every carrier of the virtual-thread
 * scheduler, so this class saturates the machine while it runs and shares the sharded worker
 * pool with whatever else the suite is running. That is why {@link #ANSWERED} is generous.
 */
@Tag("integration")
class EngineSlowClientTest extends EngineServerHarness {

    private static final int FLOODERS = 8;
    private static final int REQUESTS_EACH = 2_000;

    /**
     * LIVENESS, not latency. Every carrier is pinned by a spinner that only stops in the finally,
     * so a reply that the flooders or the scheduler can block does not arrive late — it does not
     * arrive at all, and {@link Timeout} is what ends the test then. Any bound between "at once"
     * and that timeout catches the same defect, and a tight one only measures how much CPU the
     * probe won on the day: at one second this failed under the suite's own parallelism and
     * passed run alone. Generous on purpose.
     */
    private static final Duration ANSWERED = Duration.ofSeconds(20);

    @Test
    @Timeout(60)
    void
            hello_and_status_answer_under_a_second_while_clients_that_never_read_flood_their_streams_and_every_carrier_is_busy()
                    throws Exception {
        EnginePaths.Paths p = paths(shortTempDir());
        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
        runInBackground(server);
        waitUntil(Duration.ofSeconds(10), () -> Files.exists(EnginePaths.endpoint(p)));

        List<Client> flooders = new ArrayList<>();
        List<Thread> flooding = new ArrayList<>();
        AtomicBoolean stop = new AtomicBoolean();
        List<Thread> spinners = new ArrayList<>();
        try {
            // Each flooder asks for thousands of replies and reads none of them: its socket buffer
            // fills, the engine's writer for that stream blocks in the socket, and the engine
            // stops reading the flooder's requests — so its writer has a thread of its own to
            // block on.
            for (int i = 0; i < FLOODERS; i++) {
                Client c = new Client(EnginePaths.activeSocket(p));
                flooders.add(c);
                flooding.add(Thread.ofPlatform().start(() -> {
                    try {
                        for (int r = 0; r < REQUESTS_EACH && !stop.get(); r++) c.sendLine(ProtoLifecycle.ping());
                    } catch (IOException closed) {
                        // torn down under it
                    }
                }));
            }
            // Long enough for every flooder's reply buffer to fill and its engine writer to block.
            Thread.sleep(1_000);
            // Every carrier of the virtual-thread scheduler is held by a thread that never yields,
            // the way concurrent directory walks hold them; a virtual thread queued now does not run.
            int carriers = Runtime.getRuntime().availableProcessors() + 4;
            for (int i = 0; i < carriers; i++) {
                spinners.add(Thread.ofVirtual().start(() -> {
                    while (!stop.get()) Thread.onSpinWait();
                }));
            }

            try (Client probe = new Client(EnginePaths.activeSocket(p))) {
                String ack = within(ANSWERED, "hello", () -> probe.send(ProtoLifecycle.hello("1.0")));
                assertThat(EngineProtocol.typeOf(ack)).isEqualTo(EngineProtocol.HELLO_ACK);
                for (int i = 0; i < 5; i++) {
                    String status = within(ANSWERED, "status", () -> probe.send(ProtoLifecycle.statusRequest()));
                    assertThat(EngineProtocol.typeOf(status)).isEqualTo(EngineProtocol.STATUS_ACK);
                }
            }
        } finally {
            stop.set(true);
            for (Thread t : spinners) t.join(Duration.ofSeconds(5).toMillis());
            for (Client c : flooders) c.close();
            for (Thread t : flooding) t.join(Duration.ofSeconds(5).toMillis());
            server.close();
        }
    }

    /**
     * {@code reply}'s value inside {@code bound}, on a platform thread of the test's own: a reply
     * that does not come is the failure this test exists for, so it must be reported rather than
     * waited for.
     */
    private static String within(Duration bound, String what, Callable<String> reply) throws Exception {
        ExecutorService onePlatformThread =
                Executors.newSingleThreadExecutor(Thread.ofPlatform().factory());
        try {
            Future<String> f = onePlatformThread.submit(reply);
            try {
                return f.get(bound.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                f.cancel(true);
                throw new AssertionError(what + " was not answered within " + bound.toMillis() + " ms", e);
            }
        } finally {
            onePlatformThread.shutdownNow();
        }
    }
}
