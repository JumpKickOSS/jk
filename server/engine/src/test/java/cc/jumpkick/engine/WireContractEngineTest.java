// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JobLimits;
import cc.jumpkick.engine.jobs.FakeEnvelopeHost;
import cc.jumpkick.engine.jobs.JobEnvelope;
import cc.jumpkick.engine.verbs.VerbRegistry;
import cc.jumpkick.wire.contract.WireCase;
import cc.jumpkick.wire.contract.WireContract;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Timeout;

/**
 * The engine honours the wire contract: each of {@link WireContract#CASES} is sent over a real
 * connection to {@link EngineConnection}, serving the engine's own verbs, job envelope and vitals,
 * and what it answers must conform to the case, line for line, with nothing after.
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class WireContractEngineTest {

    private static final String VERSION = "9.9.9-contract";

    @TestFactory
    Stream<DynamicTest> the_engine_answers_every_case_as_the_contract_says() {
        return WireContract.CASES.stream()
                .map(c -> DynamicTest.dynamicTest(c.name(), () -> assertThat(c.replyMismatches(serve(c)))
                        .as("%s: the engine's answer to %s", c.name(), c.request())
                        .isEmpty()));
    }

    /** Every line the engine writes for the case's request, read until it closes the connection. */
    private static List<String> serve(WireCase c) throws Exception {
        EngineConnection connection = new EngineConnection(context(), EngineConnection.HELLO_IDLE_MS);
        try (ServerSocketChannel listener = ServerSocketChannel.open()) {
            listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            Thread serving = Thread.ofVirtual().name("jk-contract-connection").start(() -> {
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
                out.write(c.request());
                out.write('\n');
                out.flush();
                for (int i = 0; i < c.replies().size(); i++) {
                    String line = in.readLine();
                    if (line == null) break;
                    lines.add(line);
                }
                // The request is answered; ending the client's side ends the connection loop, and
                // anything the engine wrote after its answer is read here too.
                client.shutdownOutput();
                String extra;
                while ((extra = in.readLine()) != null) lines.add(extra);
            }
            serving.join();
            return lines;
        }
    }

    /**
     * The engine's own connection context with no job running and no HTTP front: the real verb
     * registry is not needed by any case, so it is empty; the vitals are the engine's.
     */
    @SuppressWarnings("NullAway")
    private static EngineConnection.Context context() {
        long pid = ProcessHandle.current().pid();
        long started = System.currentTimeMillis();
        JobEnvelope jobs = new JobEnvelope(new FakeEnvelopeHost(), JobLimits.DEFAULTS);
        EngineVitals vitals = new EngineVitals(
                VERSION,
                pid,
                started,
                VERSION + "-" + started,
                new AtomicInteger(),
                new AtomicInteger(),
                new AtomicInteger(1),
                new AtomicInteger(),
                () -> null,
                () -> 0L,
                () -> -1L,
                () -> -1L,
                () -> "",
                jobs::queued,
                () -> "",
                jobs::jobsJson,
                jobs::admissionCounts);
        return new EngineConnection.Context(
                new VerbRegistry(List.of()),
                jobs,
                VERSION,
                pid,
                started,
                "0a1b2c3d",
                null,
                vitals::snapshot,
                () -> null,
                () -> null,
                () -> false,
                () -> -1L,
                null,
                () -> EngineState.PRIMARY,
                () -> {},
                (line, writer) -> {},
                () -> {});
    }
}
