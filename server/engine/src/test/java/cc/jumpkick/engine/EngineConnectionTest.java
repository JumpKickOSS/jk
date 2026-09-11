// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.BuildRequest;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The four refusal rules of one accepted connection, over a real socket: a newer protocol is refused
 * and closed; garbage and an unknown type are refused and the loop continues; a guarded verb meets
 * the lock's floor before it meets the registry. Every refusal is typed — never silence.
 */
@Tag("integration")
class EngineConnectionTest extends EngineServerHarness {

    @Test
    void a_client_speaking_a_newer_protocol_is_refused_with_version_skew_and_the_connection_closes() throws Exception {
        EnginePaths.Paths p = start();
        try (Client c = new Client(EnginePaths.activeSocket(p))) {
            String newer = ProtoLifecycle.hello("9.9.9-test")
                    .replace("\"proto\":" + EngineProtocol.PROTOCOL, "\"proto\":" + (EngineProtocol.PROTOCOL + 1));
            String reply = c.send(newer);
            assertThat(EngineProtocol.typeOf(reply)).isEqualTo(EngineProtocol.ERROR);
            assertThat(Jsonl.str(reply, "code")).isEqualTo(EngineProtocol.ERR_VERSION_SKEW);
            assertThat(c.readLine()).as("the engine closes after the refusal").isNull();
        }
        try (Client c = new Client(EnginePaths.activeSocket(p))) {
            String ack = c.send(ProtoLifecycle.hello("9.9.9-test"));
            assertThat(EngineProtocol.typeOf(ack)).isEqualTo(EngineProtocol.HELLO_ACK);
            assertThat(Jsonl.str(ack, "version")).isEqualTo("1.0");
            assertThat(Jsonl.longValue(ack, "pid", -1))
                    .isEqualTo(ProcessHandle.current().pid());
            assertThat(Jsonl.longValue(ack, "startedAt", -1)).isPositive();
            assertThat(Jsonl.has(ack, "draining")).isTrue();
            assertThat(Jsonl.has(ack, "buildId")).isTrue();
        }
    }

    @Test
    void an_unparseable_line_is_refused_and_the_connection_keeps_serving() throws Exception {
        EnginePaths.Paths p = start();
        try (Client c = new Client(EnginePaths.activeSocket(p))) {
            String reply = c.send("this is not a request");
            assertThat(EngineProtocol.typeOf(reply)).isEqualTo(EngineProtocol.ERROR);
            assertThat(Jsonl.str(reply, "code")).isEqualTo(EngineProtocol.ERR_PROTOCOL);
            assertThat(EngineProtocol.typeOf(c.send(ProtoLifecycle.ping()))).isEqualTo(EngineProtocol.PONG);
        }
    }

    @Test
    void an_unknown_request_type_is_refused_and_the_connection_keeps_serving() throws Exception {
        EnginePaths.Paths p = start();
        try (Client c = new Client(EnginePaths.activeSocket(p))) {
            String unknown = ProtoLifecycle.ping().replace(EngineProtocol.PING, "frobnicate");
            String reply = c.send(unknown);
            assertThat(EngineProtocol.typeOf(reply)).isEqualTo(EngineProtocol.ERROR);
            assertThat(Jsonl.str(reply, "code")).isEqualTo(EngineProtocol.ERR_PROTOCOL);
            assertThat(Jsonl.str(reply, "message")).contains("frobnicate");
            assertThat(EngineProtocol.typeOf(c.send(ProtoLifecycle.ping()))).isEqualTo(EngineProtocol.PONG);
        }
    }

    @Test
    void a_guarded_verb_meets_the_lock_floor_before_it_meets_the_registry() throws Exception {
        EnginePaths.Paths p = start();
        Path project = shortTempDir();
        Files.writeString(project.resolve("jk.toml"), "group = \"com.example\"\nname = \"app\"\nversion = \"1.0.0\"\n");
        Files.writeString(project.resolve("jk-lock.toml"), """
                version = 1
                generated-by = "jk test"
                resolution-algorithm = "pubgrub-v1"
                jk-min = "9.9.9"
                """);
        try (Client c = new Client(EnginePaths.activeSocket(p))) {
            String reply = c.send(new BuildRequest(
                            project.toString(),
                            shortTempDir().toString(),
                            null,
                            1,
                            null,
                            true,
                            false,
                            1,
                            false,
                            true,
                            false,
                            false,
                            false,
                            false,
                            null,
                            TestSelection.DEFAULT,
                            null,
                            List.of(),
                            false,
                            null,
                            Map.of(),
                            null,
                            null,
                            null)
                    .encode());
            assertThat(EngineProtocol.typeOf(reply))
                    .as("refused before any job-start: the floor runs before the verb lookup")
                    .isEqualTo(EngineProtocol.ERROR);
            assertThat(Jsonl.str(reply, "code")).isEqualTo(EngineProtocol.ERR_VERSION_SKEW);
            assertThat(Jsonl.str(reply, "message")).contains("9.9.9");
            assertThat(c.readLine()).isNull();
        }
    }

    private EnginePaths.Paths start() throws Exception {
        EnginePaths.Paths p = paths(shortTempDir());
        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
        runInBackground(server);
        waitUntil(Duration.ofSeconds(10), () -> Files.exists(EnginePaths.endpoint(p)));
        return p;
    }
}
