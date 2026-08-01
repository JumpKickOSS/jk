// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.ide;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.bsp.BspServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**per-request BSP errors must not tear down the session. */
@Tag("integration")
class BspSessionIsolationTest {

    @Test
    void handler_io_exception_emits_jsonrpc_error_and_session_continues(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                [project]
                group = "t"
                name = "t"
                version = "0.0.1"
                jdk = 25
                """);
        Path cache = Files.createDirectories(dir.resolve("cache"));
        IdeEngineClient failing = new IdeEngineClient(dir, cache, null) {
            @Override
            public cc.jumpkick.engine.protocol.ProjectInfo projectInfo() throws IOException {
                throw new IOException("engine died mid-session");
            }
        };

        String init = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"build/initialize\",\"params\":{}}";
        String targets = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"workspace/buildTargets\",\"params\":{}}";
        String shutdown = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"build/shutdown\",\"params\":null}";
        String leave = "{\"jsonrpc\":\"2.0\",\"method\":\"build/exit\"}";
        String session = frame(init) + frame(targets) + frame(shutdown) + frame(leave);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new BspServer(failing, new ByteArrayInputStream(session.getBytes(StandardCharsets.UTF_8)), out).serve();
        String responses = out.toString(StandardCharsets.UTF_8);
        assertThat(responses).contains("engine died mid-session");
        assertThat(responses).contains("\"error\"");
        assertThat(responses).contains("\"id\":2");
        assertThat(responses).contains("\"id\":3");
    }

    private static String frame(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return "Content-Length: " + bytes.length + "\r\n\r\n" + body;
    }
}
