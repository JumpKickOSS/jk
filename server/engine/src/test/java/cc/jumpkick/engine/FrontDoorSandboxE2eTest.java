// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.engine.http.HttpEngineServer;
import cc.jumpkick.host.Guest;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.wire.EnginePaths;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * A real engine in a test sandbox, on a host that looks like a WSL guest with interop, takes no part
 * in the front door: it does not pair with Windows (which asks interop for cmd.exe, wslpath and
 * powershell.exe, and runs the 8910 role with its checks for the rest of the process), and serves no
 * engine list. Every suite that runs a real engine in a sandbox on a WSL host used to start all of
 * that per engine, inside the test worker. {@code JK_FRONT_DOOR=true} opts a sandbox engine back in.
 */
class FrontDoorSandboxE2eTest extends EngineServerHarness {

    private static final Guest WSL = new Guest(Guest.Kind.WSL, "Ubuntu-26.10", true, "distro=Ubuntu-26.10");

    private List<HttpEngineServer> runEngine(Function<String, @Nullable String> env) throws Exception {
        List<HttpEngineServer> paired = new CopyOnWriteArrayList<>();
        EnginePaths.Paths p = paths(shortTempDir());
        EngineServer server = new EngineServer(
                p, JkEngineConfig.DEFAULTS, httpOnEphemeralPort(shortTempDir()), JkVersion.VERSION, null);
        server.httpFront().seams(() -> WSL, env, paired::add);
        Thread serverThread = runInBackground(server);
        try {
            waitUntil(Duration.ofSeconds(10), () -> Files.exists(EnginePaths.endpoint(p)) && hasContent(p.http()));
            HttpEngineServer http =
                    Objects.requireNonNull(server.httpFront().server(), "the engine serves its dashboard");
            assertThat(Objects.requireNonNull(http.identity()).guest()).isEqualTo(WSL);
            assertThat(http.frontDoor()).as("no engine list or proxy").isNull();
            return paired;
        } finally {
            server.close();
            serverThread.join(10_000);
        }
    }

    @Test
    void a_sandbox_engine_on_a_wsl_host_never_pairs_with_windows() throws Exception {
        // Nothing in the environment says so: the engine's home, the test JVM's sandbox, is enough.
        assertThat(runEngine(Map.<String, String>of()::get))
                .as("pairing, the Windows engine's start and the 8910 role")
                .isEmpty();
    }

    @Test
    void a_sandbox_engine_that_opts_in_pairs() throws Exception {
        assertThat(runEngine(Map.of("JK_FRONT_DOOR", "true")::get)).hasSize(1);
    }
}
