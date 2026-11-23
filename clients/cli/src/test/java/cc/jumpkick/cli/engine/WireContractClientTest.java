// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.testing.ShortTempDirs;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.EngineTransport;
import cc.jumpkick.wire.contract.WireCase;
import cc.jumpkick.wire.contract.WireContract;
import cc.jumpkick.wire.contract.WireContractStub;
import cc.jumpkick.wire.protocol.CancelAckFrame;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The CLI keeps the wire contract: for each of {@link WireContract#CASES} the client code a command
 * runs talks to a {@link WireContractStub} serving the case, and what it wrote must conform to the
 * case's request and what it made of the answer must be what the answer says. A case with no drive
 * here fails, so a case added to the contract is run on this side too.
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class WireContractClientTest {

    private static final String CLIENT_VERSION = "9.9.9";

    @RegisterExtension
    final ShortTempDirs tempDirs = new ShortTempDirs("jkc-");

    private @Nullable String forcedTransport;

    @BeforeEach
    void unixTransport() {
        forcedTransport = System.getProperty(EngineTransport.TRANSPORT_PROPERTY);
        System.setProperty(EngineTransport.TRANSPORT_PROPERTY, "unix");
    }

    @AfterEach
    void restore() {
        if (forcedTransport == null) System.clearProperty(EngineTransport.TRANSPORT_PROPERTY);
        else System.setProperty(EngineTransport.TRANSPORT_PROPERTY, forcedTransport);
    }

    @TestFactory
    Stream<DynamicTest> the_cli_reads_every_case_as_the_contract_says() {
        return WireContract.CASES.stream().map(c -> DynamicTest.dynamicTest(c.name(), () -> drive(c)));
    }

    private void drive(WireCase c) throws Exception {
        EnginePaths.Paths paths = EnginePaths.resolve(tempDirs.create());
        Path socket = EnginePaths.activeSocket(paths);
        // The handshake rides along where a command opens with one; the case under test comes first.
        try (WireContractStub engine = new WireContractStub(socket, List.of(c, WireContract.HELLO))) {
            try {
                read(c, paths, socket);
            } catch (AssertionError e) {
                // A request the stub could not match leaves the client with no answer; say why.
                if (engine.mismatches().isEmpty()) throw e;
                throw new AssertionError(c.name() + ": the CLI broke the contract: " + engine.mismatches(), e);
            }
            assertThat(engine.served()).as("cases the stub served").contains(c.name());
            assertThat(engine.mismatches())
                    .as("what the CLI wrote, against the contract")
                    .isEmpty();
        }
    }

    /** Runs the client code that sends the case's request and checks what it made of the answer. */
    private static void read(WireCase c, EnginePaths.Paths paths, Path socket) throws Exception {
        switch (c.name()) {
            case "hello" -> {
                assertThat(EngineProbe.handshake(socket, CLIENT_VERSION))
                        .contains(new EngineProbe.Handshake("9.9.9-contract", 4242, 1767225600000L, false, "0a1b2c3d"));
            }
            case "ping" -> assertThat(EngineProbe.ping(socket)).isTrue();
            case "status" -> {
                EngineProbe.Status s = EngineProbe.status(socket).orElseThrow();
                assertThat(s.version()).isEqualTo("9.9.9-contract");
                assertThat(s.pid()).isEqualTo(4242);
                assertThat(s.startedAtMillis()).isEqualTo(1767225600000L);
                assertThat(s.activeRequests()).isEqualTo(1);
                assertThat(s.activeBuildPlans()).isZero();
                assertThat(s.queuedBuildPlans()).isZero();
                assertThat(s.idleDropped()).isEqualTo(3);
                assertThat(s.heapUsedBytes()).isEqualTo(104857600L);
                assertThat(s.heapCommittedBytes()).isEqualTo(209715200L);
                assertThat(s.heapMaxBytes()).isEqualTo(1073741824L);
                assertThat(s.rssBytes()).isEqualTo(314572800L);
                assertThat(s.cores()).isEqualTo(8);
                assertThat(s.totalMemoryBytes()).isEqualTo(17179869184L);
                assertThat(s.availableMemoryBytes()).isEqualTo(8589934592L);
                assertThat(s.systemCpuLoad()).isEqualTo(0.25);
                assertThat(s.systemLoadAverage()).isEqualTo(1.5);
                assertThat(s.engineEpoch()).isEqualTo("9.9.9-contract-1767225600000");
                assertThat(s.logBytes()).isEqualTo(4096);
                assertThat(s.logRolledAt()).isEqualTo(-1);
                assertThat(s.draining()).isFalse();
                assertThat(s.drainDeadlineMillis()).isEqualTo(-1);
                assertThat(s.httpUrl()).isNull();
                assertThat(s.httpError()).isNull();
                assertThat(s.mcpUrl()).isNull();
                assertThat(s.jobs())
                        .containsExactly(new EngineProbe.Job(
                                7, "build", "/contract/app", true, 1767225601000L, 2, 1767225602000L, -1, false));
                assertThat(s.engineId()).isEqualTo("engine-0001");
                assertThat(s.engineLabel()).isEqualTo("Linux on contract-host");
                assertThat(s.engineCommit()).isEqualTo("0a1b2c3d");
            }
            case "cancel-unknown-jid" -> {
                CancelAckFrame ack = cancelAck(EngineCancel.cancel(paths, Jsonl.longValue(c.request(), "jid", -1)));
                assertThat(ack).isEqualTo(new CancelAckFrame(99, false, "unknown or already finished jid"));
            }
            case "cancel-idle-dir" -> {
                CancelAckFrame ack = cancelAck(EngineCancel.cancelForDir(paths, Jsonl.requiredStr(c.request(), "dir")));
                assertThat(ack).isEqualTo(new CancelAckFrame(0, false, "no running jobs for dir"));
            }
            case "suspend-unknown-jid" ->
                assertThat(EngineJobControl.send(socket, List.of(Jsonl.longValue(c.request(), "jid", -1)), true))
                        .as("jobs the engine suspended")
                        .isZero();
            default -> fail("no client drive for the wire contract case " + c.name());
        }
    }

    private static CancelAckFrame cancelAck(Optional<String> line) {
        assertThat(line).as("the cancel-ack").isPresent();
        return CancelAckFrame.decode(line.orElseThrow());
    }
}
