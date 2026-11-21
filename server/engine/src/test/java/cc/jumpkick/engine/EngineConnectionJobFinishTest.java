// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JobLimits;
import cc.jumpkick.config.Session;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * A job that fails once admitted ends its connection at {@code job-finish}: the client reads its
 * terminal last, and the connection loop adds nothing after it. One real connection over loopback
 * TCP, served by a one-verb registry.
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class EngineConnectionJobFinishTest {

    private static final String TYPE = "fixture-build-request";

    /** A plan verb whose body throws {@code bodyThrows}, or declines when that is {@code null}. */
    private record FixtureVerb(@Nullable RuntimeException bodyThrows) implements HostedVerb {
        @Override
        public String wireType() {
            return TYPE;
        }

        @Override
        public JobKind jobKind() {
            return new JobKind.Plan("build");
        }

        @Override
        public VerbShape shape() {
            return new VerbShape.AsyncPlan();
        }

        @Override
        public String threadPrefix() {
            return "jk-fixture-";
        }

        @Override
        public JobOutcome run(String requestLine, Session.CancelToken cancel, @Nullable BufferedWriter writer) {
            if (bodyThrows != null) throw bodyThrows;
            return JobOutcome.declined();
        }
    }

    @Test
    void a_job_whose_start_throws_after_admission_ends_the_wire_at_job_finish(@TempDir Path dir) throws Exception {
        FakeEnvelopeHost host =
                FakeEnvelopeHost.failingAfterAdmission(new IllegalStateException("timeline unwritable"));

        List<String> lines = serveOne(host, new FixtureVerb(null), dir);

        assertThat(types(lines)).contains(EngineProtocol.ERR_REQUEST_FAILED);
        assertThat(lines).anyMatch(l -> l.contains("timeline unwritable"));
        assertThat(types(lines)).last().as("the wire: %s", lines).isEqualTo(EngineProtocol.JOB_FINISH);
        assertThat(host.activeBuildPlans()).as("plan slots held").isZero();
    }

    @Test
    void a_job_whose_body_throws_ends_the_wire_at_job_finish(@TempDir Path dir) throws Exception {
        FakeEnvelopeHost host = new FakeEnvelopeHost();

        List<String> lines = serveOne(host, new FixtureVerb(new IllegalStateException("body broke")), dir);

        assertThat(lines).anyMatch(l -> l.contains("body broke"));
        assertThat(types(lines)).last().as("the wire: %s", lines).isEqualTo(EngineProtocol.JOB_FINISH);
        assertThat(host.activeBuildPlans()).as("plan slots held").isZero();
    }

    /**
     * Sends one request for {@code verb} over a fresh connection and returns every line the engine
     * wrote. Once {@code job-finish} arrives the client half-closes, so the connection loop ends either
     * way and whatever it sent after the terminal is read too.
     */
    private static List<String> serveOne(FakeEnvelopeHost host, HostedVerb verb, Path dir) throws Exception {
        EngineConnection connection = new EngineConnection(context(host, verb), EngineConnection.HELLO_IDLE_MS);
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
                out.write("{\"type\":" + Jsonl.quote(TYPE) + ",\"dir\":" + Jsonl.quote(dir.toString()) + "}\n");
                out.flush();
                String line;
                boolean halfClosed = false;
                while ((line = in.readLine()) != null) {
                    lines.add(line);
                    if (!halfClosed && EngineProtocol.JOB_FINISH.equals(EngineProtocol.typeOf(line))) {
                        client.shutdownOutput();
                        halfClosed = true;
                    }
                }
            }
            serving.join();
            return lines;
        }
    }

    /**
     * A connection's context with {@code verb} as its only verb. The status, HTTP and drain arms are
     * never reached, so they are null.
     */
    @SuppressWarnings("NullAway")
    private static EngineConnection.Context context(FakeEnvelopeHost host, HostedVerb verb) {
        return new EngineConnection.Context(
                new VerbRegistry(List.of(verb)),
                new JobEnvelope(host, JobLimits.DEFAULTS),
                "1.0",
                ProcessHandle.current().pid(),
                System.currentTimeMillis(),
                "fixture",
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

    /** Each line's type, or for an error its code: {@code request-failed} is an error with that code. */
    private static List<String> types(List<String> lines) {
        return lines.stream()
                .map(l -> {
                    String type = EngineProtocol.typeOf(l);
                    return EngineProtocol.ERROR.equals(type) ? Jsonl.str(l, "code") : type;
                })
                .toList();
    }
}
