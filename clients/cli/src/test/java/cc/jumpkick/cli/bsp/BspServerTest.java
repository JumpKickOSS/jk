// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.bsp;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.engine.EngineTestSupport;
import cc.jumpkick.cli.ide.IdeEngineClient;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BspServerTest {

    @BeforeAll
    static void materializeEngine() {
        EngineTestSupport.ensureEngineMaterialized();
    }

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
    void target_json_shape_includes_canTest_and_canCompile() {
        String json = BspServer.targetJson("file:///p#root", "demo", "file:///p");
        assertThat(json).contains("\"canCompile\":true");
        assertThat(json).contains("\"canTest\":true");
        assertThat(json).contains("\"canRun\":false");
        assertThat(json).contains("file:///p#root");
    }

    @Test
    void initialize_advertises_test_provider(@TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve("jk.toml"),
                """
                [project]
                group = "t"
                name = "t"
                version = "0.0.1"
                jdk = 25
                """);
        IdeEngineClient ide = IdeEngineClient.open(dir, dir.resolve("cache"), null);

        String session = frame(init(1)) + frame(shutdown(2)) + frame(exit());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new BspServer(ide, new ByteArrayInputStream(session.getBytes(StandardCharsets.UTF_8)), out).serve();
        String responses = out.toString(StandardCharsets.UTF_8);
        assertThat(responses).contains("testProvider");
        assertThat(responses).contains("compileProvider");
        assertThat(responses).contains("java");
    }

    @Test
    void buildTargets_includes_canTest_capabilities(@TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve("jk.toml"),
                """
                [project]
                group = "t"
                name = "app"
                version = "0.0.1"
                jdk = 25
                java = 25
                """);
        Path cache = Files.createDirectories(dir.resolve("cache"));
        IdeEngineClient ide = IdeEngineClient.open(dir, cache, null);
        ide.connect();

        String session = frame(init(1))
                + frame(
                        """
                        {"jsonrpc":"2.0","id":2,"method":"workspace/buildTargets","params":{}}
                        """)
                + frame(shutdown(3))
                + frame(exit());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new BspServer(ide, new ByteArrayInputStream(session.getBytes(StandardCharsets.UTF_8)), out).serve();
        String responses = out.toString(StandardCharsets.UTF_8);
        assertThat(responses).contains("\"canTest\":true");
        assertThat(responses).contains("\"canCompile\":true");
        assertThat(responses).contains("targets");
    }

    @Test
    void buildTarget_test_returns_statusCode(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src"));
        Files.writeString(
                dir.resolve("jk.toml"),
                """
                [project]
                group = "t"
                name = "app"
                version = "0.0.1"
                jdk = 25
                java = 25
                """);
        Files.writeString(
                src.resolve("App.java"),
                """
                package t;
                public class App {
                  public static int one() { return 1; }
                }
                """);
        Path cache = Files.createDirectories(dir.resolve("cache"));
        IdeEngineClient ide = IdeEngineClient.open(dir, cache, null);
        ide.connect();

        String session = frame(init(1))
                + frame(
                        """
                        {"jsonrpc":"2.0","id":2,"method":"buildTarget/test","params":{"targets":[{"uri":"file://x#root"}]}}
                        """)
                + frame(shutdown(3))
                + frame(exit());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new BspServer(ide, new ByteArrayInputStream(session.getBytes(StandardCharsets.UTF_8)), out).serve();
        String responses = out.toString(StandardCharsets.UTF_8);
        assertThat(responses).contains("\"statusCode\":");
        assertThat(responses).contains("\"id\":2");
    }

    @Test
    void multi_header_content_length_is_read(@TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve("jk.toml"),
                """
                [project]
                group = "t"
                name = "t"
                version = "0.0.1"
                jdk = 25
                """);
        IdeEngineClient ide = IdeEngineClient.open(dir, dir.resolve("cache"), null);
        String body =
                """
                {"jsonrpc":"2.0","id":1,"method":"build/initialize","params":{}}
                """
                        .trim();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String framed = "Content-Type: application/vscode-jsonrpc; charset=utf-8\r\n"
                + "Content-Length: "
                + bytes.length
                + "\r\n\r\n"
                + body
                + frame(shutdown(2))
                + frame(exit());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new BspServer(ide, new ByteArrayInputStream(framed.getBytes(StandardCharsets.UTF_8)), out).serve();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("testProvider");
    }

    private static String init(int id) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"build/initialize\",\"params\":{}}";
    }

    private static String shutdown(int id) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"build/shutdown\",\"params\":null}";
    }

    private static String exit() {
        return "{\"jsonrpc\":\"2.0\",\"method\":\"build/exit\"}";
    }

    private static String frame(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return "Content-Length: " + bytes.length + "\r\n\r\n" + body;
    }
}
