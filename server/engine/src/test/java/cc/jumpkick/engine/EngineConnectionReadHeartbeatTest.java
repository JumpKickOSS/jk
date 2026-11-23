// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JobLimits;
import cc.jumpkick.config.Session;
import cc.jumpkick.engine.api.WireWriter;
import cc.jumpkick.engine.jobs.FakeEnvelopeHost;
import cc.jumpkick.engine.jobs.JobEnvelope;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.verbs.HostedVerb;
import cc.jumpkick.engine.verbs.VerbRegistry;
import cc.jumpkick.engine.verbs.VerbShape;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.protocol.EngineProtocol;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A read served inline on its connection heartbeats while it runs, as a job does, so a client
 * holding the wire to a stream-idle bound hears an engine that is busy and not dead. One real
 * connection over loopback TCP, served by a one-verb registry whose read stays busy until its
 * client has heard a heartbeat.
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class EngineConnectionReadHeartbeatTest {

    private static final String TYPE = "fixture-read-request";
    private static final String ACK = "fixture-read-ack";

    /** A read that answers once its client has heard a heartbeat, or after ten seconds without one. */
    private record BusyRead(CountDownLatch heard) implements HostedVerb {
        @Override
        public String wireType() {
            return TYPE;
        }

        @Override
        public JobKind jobKind() {
            return new JobKind.Plan("explain");
        }

        @Override
        public VerbShape shape() {
            return new VerbShape.SyncRead();
        }

        @Override
        public String threadPrefix() {
            return "jk-fixture-read-";
        }

        @Override
        public JobOutcome run(String requestLine, Session.CancelToken cancel, @Nullable BufferedWriter writer) {
            boolean beatHeard;
            try {
                beatHeard = heard.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                beatHeard = false;
            }
            WireWriter.sendQuiet(writer, "{\"type\":" + Jsonl.quote(ACK) + ",\"heard\":" + beatHeard + "}");
            return JobOutcome.declined();
        }
    }

    @Test
    void a_read_that_runs_past_the_heartbeat_interval_heartbeats_until_it_answers() throws Exception {
        CountDownLatch heard = new CountDownLatch(1);

        List<String> lines = serveOne(new BusyRead(heard), heard, 20L);

        assertThat(lines).as("the wire: %s", lines).last().satisfies(ack -> {
            assertThat(EngineProtocol.typeOf(ack)).isEqualTo(ACK);
            assertThat(ack).as("the read heard its client hear a heartbeat").contains("\"heard\":true");
        });
        assertThat(lines.getFirst())
                .satisfies(first -> assertThat(EngineProtocol.typeOf(first)).isEqualTo(EngineProtocol.HEARTBEAT));
    }

    /**
     * Sends one request for {@code verb} with heartbeats every {@code heartbeatMs} and returns the
     * lines up to and including its ack, counting {@code heard} down at the first heartbeat.
     */
    private static List<String> serveOne(HostedVerb verb, CountDownLatch heard, long heartbeatMs) throws Exception {
        JobLimits limits = new JobLimits(
                heartbeatMs,
                JobLimits.DEFAULT_DEADLINE_MS,
                JobLimits.DEFAULT_DETACHED_DEADLINE_MS,
                JobLimits.DEFAULT_DEADLINE_GRACE_MS,
                JobLimits.DEFAULT_CANCEL_GRACE_MS,
                JobLimits.DEFAULT_QUEUE_WAIT_MS,
                JobLimits.DEFAULT_DRAIN_DEADLINE_MS);
        EngineConnection connection = new EngineConnection(context(verb, limits), EngineConnection.HELLO_IDLE_MS);
        try (ServerSocketChannel listener = ServerSocketChannel.open()) {
            listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            Thread serving = Thread.ofVirtual().name("jk-fixture-connection").start(() -> {
                try {
                    connection.serve(listener.accept());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            List<String> lines = new ArrayList<>();
            try (SocketChannel client = SocketChannel.open(listener.getLocalAddress());
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(Channels.newInputStream(client), StandardCharsets.UTF_8));
                    BufferedWriter out = new BufferedWriter(
                            new OutputStreamWriter(Channels.newOutputStream(client), StandardCharsets.UTF_8))) {
                out.write("{\"type\":" + Jsonl.quote(TYPE) + "}\n");
                out.flush();
                String line;
                while ((line = in.readLine()) != null) {
                    lines.add(line);
                    String type = EngineProtocol.typeOf(line);
                    if (EngineProtocol.HEARTBEAT.equals(type)) heard.countDown();
                    if (ACK.equals(type)) break;
                }
                client.shutdownOutput();
            }
            serving.join();
            return lines;
        }
    }

    /** A connection's context with {@code verb} as its only verb; the arms it never reaches are null. */
    @SuppressWarnings("NullAway")
    private static EngineConnection.Context context(HostedVerb verb, JobLimits limits) {
        return new EngineConnection.Context(
                new VerbRegistry(List.of(verb)),
                new JobEnvelope(new FakeEnvelopeHost(), limits),
                "1.0",
                ProcessHandle.current().pid(),
                System.currentTimeMillis(),
                "fixture",
                null,
                null,
                null,
                null,
                () -> false,
                () -> 0L,
                null,
                () -> EngineState.PRIMARY,
                () -> {},
                (line, writer) -> {},
                () -> {});
    }
}
