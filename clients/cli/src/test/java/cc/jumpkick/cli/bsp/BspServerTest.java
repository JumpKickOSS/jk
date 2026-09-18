// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.bsp;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.engine.EngineTestSupport;
import cc.jumpkick.cli.ide.IdeEngineClient;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.wire.protocol.RequestEnvironment;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class BspServerTest {

    @BeforeAll
    static void materializeEngine() {
        EngineTestSupport.ensureEngineMaterialized();
    }

    @Test
    void extractTargetUris_finds_fragments() {
        String json = """
                {"params":{"targets":[{"uri":"file:///tmp/ws#api"},{"uri":"file:///tmp/ws#worker"}]}}
                """;
        assertThat(BspServer.extractTargetUris(json)).contains("file:///tmp/ws#api", "file:///tmp/ws#worker");
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
    void target_json_canRun_when_main_present() {
        String json = BspServer.targetJson("file:///p#app", "app", "file:///p/app", true);
        assertThat(json).contains("\"canRun\":true");
        assertThat(json)
                .as("a module with an [application] main is an application")
                .contains("\"tags\":[\"application\"]");
        assertThat(BspServer.targetJson("file:///p#lib", "lib", "file:///p/lib", false))
                .contains("\"tags\":[\"library\"]");
    }

    @Test
    void initialize_advertises_run_provider(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "t"
                name = "t"
                version = "0.0.1"
                java = 25
                """);
        IdeEngineClient ide = IdeEngineClient.open(dir, dir.resolve("cache"), null);

        String session = frame(init(1)) + frame(shutdown(2)) + frame(exit());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new BspServer(ide, new ByteArrayInputStream(session.getBytes(StandardCharsets.UTF_8)), out).serve();
        String responses = out.toString(StandardCharsets.UTF_8);
        assertThat(responses).contains("runProvider");
        assertThat(responses).contains("testProvider");
    }

    @Test
    void initialize_advertises_test_provider(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "t"
                name = "t"
                version = "0.0.1"
                java = 25
                """);
        IdeEngineClient ide = IdeEngineClient.open(dir, dir.resolve("cache"), null);

        String session = frame(init(1)) + frame(shutdown(2)) + frame(exit());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new BspServer(ide, new ByteArrayInputStream(session.getBytes(StandardCharsets.UTF_8)), out).serve();
        String responses = out.toString(StandardCharsets.UTF_8);
        assertThat(responses).contains("testProvider");
        assertThat(responses).contains("compileProvider");
        assertThat(responses).contains("runProvider");
        assertThat(responses).contains("java");
    }

    @Test
    void buildTargets_includes_canTest_capabilities(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "t"
                name = "app"
                version = "0.0.1"
                jdk = 25
                java = 25
                """);
        Path cache = Files.createDirectories(dir.resolve("cache"));
        IdeEngineClient ide = IdeEngineClient.open(dir, cache, null);
        ide.connect();

        String session = frame(init(1)) + frame("""
                        {"jsonrpc":"2.0","id":2,"method":"workspace/buildTargets","params":{}}
                        """) + frame(shutdown(3)) + frame(exit());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new BspServer(ide, new ByteArrayInputStream(session.getBytes(StandardCharsets.UTF_8)), out).serve();
        String responses = out.toString(StandardCharsets.UTF_8);
        assertThat(responses).contains("\"canTest\":true");
        assertThat(responses).contains("\"canCompile\":true");
        assertThat(responses).contains("targets");
    }

    /**
     * A mixed Java/Scala module is a Scala build target to Metals: its target carries the {@code
     * scala} data kind with the compiler version and its {@code languageIds} name both languages,
     * {@code buildTarget/scalacOptions} answers with the Zinc arguments, the classpath and the
     * class directory, and the server advertises {@code scala} among its languages.
     */
    @Test
    void a_mixed_java_scala_module_is_a_scala_target_with_scalac_options(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "t"
                name = "mixed"
                version = "0.0.1"
                java = 25
                scala = "3.8.4"
                """);
        Files.createDirectories(dir.resolve("src/main/java/t"));
        Files.writeString(dir.resolve("src/main/java/t/Greeter.java"), """
                package t;
                public class Greeter { public static String hi() { return "hi"; } }
                """);
        Files.createDirectories(dir.resolve("src/main/scala/t"));
        Files.writeString(dir.resolve("src/main/scala/t/Main.scala"), """
                package t
                object Main:
                  def main(args: Array[String]): Unit = println(Greeter.hi())
                """);
        IdeEngineClient ide = IdeEngineClient.open(dir, Files.createDirectories(dir.resolve("cache")), null);
        ide.connect();

        String session = frame(init(1)) + frame("""
                        {"jsonrpc":"2.0","id":2,"method":"workspace/buildTargets","params":{}}
                        """) + frame("""
                        {"jsonrpc":"2.0","id":3,"method":"buildTarget/scalacOptions","params":{"targets":[]}}
                        """) + frame("""
                        {"jsonrpc":"2.0","id":4,"method":"buildTarget/javacOptions","params":{"targets":[]}}
                        """) + frame(shutdown(5)) + frame(exit());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new BspServer(ide, new ByteArrayInputStream(session.getBytes(StandardCharsets.UTF_8)), out).serve();
        String responses = out.toString(StandardCharsets.UTF_8);

        assertThat(responses).contains("\"languageIds\":[\"java\",\"scala\"]");
        assertThat(responses).contains("\"dataKind\":\"scala\"");
        assertThat(responses).contains("\"scalaVersion\":\"3.8.4\"").contains("\"scalaBinaryVersion\":\"3\"");
        assertThat(responses).contains("\"scalaOrganization\":\"org.scala-lang\"");
        String classes = dir.toRealPath().resolve("target/classes/main").toUri().toString();
        assertThat(responses)
                .contains("\"options\":[\"-java-output-version\",\"25\"]")
                .contains("\"classDirectory\":\"" + classes + "\"");
        assertThat(responses).contains("\"options\":[\"--release\",\"25\"]");
        // The initialize result names scala among the languages this workspace compiles.
        assertThat(responses.substring(0, responses.indexOf("\"id\":2"))).contains("scala");
    }

    @Test
    void buildTarget_test_returns_statusCode(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src"));
        Files.writeString(dir.resolve("jk.toml"), """
                group = "t"
                name = "app"
                version = "0.0.1"
                jdk = 25
                java = 25
                """);
        Files.writeString(src.resolve("App.java"), """
                package t;
                public class App {
                  public static int one() { return 1; }
                }
                """);
        Path cache = Files.createDirectories(dir.resolve("cache"));
        IdeEngineClient ide = IdeEngineClient.open(dir, cache, null);
        ide.connect();

        String session = frame(init(1)) + frame("""
                        {"jsonrpc":"2.0","id":2,"method":"buildTarget/test","params":{"targets":[{"uri":"file://x#root"}]}}
                        """) + frame(shutdown(3)) + frame(exit());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new BspServer(ide, new ByteArrayInputStream(session.getBytes(StandardCharsets.UTF_8)), out).serve();
        String responses = out.toString(StandardCharsets.UTF_8);
        assertThat(responses).contains("\"statusCode\":");
        assertThat(responses).contains("\"id\":2");
    }

    @Test
    void initialize_declares_this_process_as_the_ide_session(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "t"
                name = "t"
                version = "0.0.1"
                jdk = 25
                """);
        IdeEngineClient ide = IdeEngineClient.open(dir, dir.resolve("cache"), null);
        String init = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"build/initialize\","
                + "\"params\":{\"displayName\":\"IntelliJ-BSP\",\"version\":\"2025.2\"}}";
        try {
            new BspServer(
                            ide,
                            new ByteArrayInputStream((frame(init) + frame(shutdown(2)) + frame(exit()))
                                    .getBytes(StandardCharsets.UTF_8)),
                            new ByteArrayOutputStream())
                    .serve();
            // Every build this process sends from here on journals as the IDE's own.
            assertThat(RequestEnvironment.trigger()).isEqualTo("bsp");
            assertThat(RequestEnvironment.session()).matches("IntelliJ-BSP [0-9a-f]{4}");
            // The same four hex digits for the life of the process: two windows are two sessions.
            assertThat(BspServer.sessionLabel(MiniJson.parse(init))).isEqualTo(RequestEnvironment.session());
            assertThat(BspServer.sessionLabel(null)).matches("bsp-client [0-9a-f]{4}");
        } finally {
            System.clearProperty("jk.build.trigger");
            System.clearProperty("jk.build.session");
        }
    }

    @Test
    void multi_header_content_length_is_read(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "t"
                name = "t"
                version = "0.0.1"
                jdk = 25
                """);
        IdeEngineClient ide = IdeEngineClient.open(dir, dir.resolve("cache"), null);
        String body = """
                {"jsonrpc":"2.0","id":1,"method":"build/initialize","params":{}}
                """.trim();
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
