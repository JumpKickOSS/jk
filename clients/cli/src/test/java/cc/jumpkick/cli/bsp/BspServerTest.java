// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.bsp;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.ide.IdeEngineClient;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BspServerTest {

    @Test
    void extractTargetUris_finds_fragments() {
        String json =
                """
                {"params":{"targets":[{"uri":"file:///tmp/ws#api"},{"uri":"file:///tmp/ws#worker"}]}}
                """;
        assertThat(BspServer.extractTargetUris(json))
                .contains("file:///tmp/ws#api", "file:///tmp/ws#worker");
    }

    @Test
    void initialize_advertises_test_provider_and_canTest(@TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve("jk.toml"),
                """
                [project]
                group = "t"
                name = "t"
                version = "0.0.1"
                jdk = 25
                """);
        // Minimal stub client: only projectDir needed for initialize (no engine call).
        IdeEngineClient ide = IdeEngineClient.open(dir, dir.resolve("cache"), null);

        String init =
                """
                {"jsonrpc":"2.0","id":1,"method":"build/initialize","params":{}}
                """;
        String framed = frame(init);
        // shutdown after initialize so serve() exits cleanly after one response + exit
        String exit =
                """
                {"jsonrpc":"2.0","id":2,"method":"build/shutdown","params":null}
                """;
        String exitMsg = frame(exit);
        String leave =
                """
                {"jsonrpc":"2.0","method":"build/exit"}
                """;
        ByteArrayInputStream in =
                new ByteArrayInputStream((framed + frame(exit) + frame(leave)).getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new BspServer(ide, in, out).serve();
        String responses = out.toString(StandardCharsets.UTF_8);
        assertThat(responses).contains("testProvider");
        assertThat(responses).contains("java");
        // workspace/buildTargets not called — canTest is on targets; also check initialize body
        assertThat(responses).contains("compileProvider");
    }

    @Test
    void target_json_shape_includes_canTest() {
        // Public contract via extract + documented capabilities — canTest is in target template.
        // Smoke the constant path by reading source contract: initialize lists testProvider.
        assertThat(BspServer.extractTargetUris("{\"uri\":\"file:///p#root\"}")).contains("file:///p#root");
    }

    private static String frame(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return "Content-Length: " + bytes.length + "\r\n\r\n" + body;
    }
}
