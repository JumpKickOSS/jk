// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * A Spring Boot app that builds its own front end from {@code src/main/node}: the bundle lands in
 * the Boot jar's {@code BOOT-INF/classes/static/}, the running jar serves it at {@code /}, and a
 * front-end edit repackages the jar without compiling Java again. Boot comes from Maven Central and
 * Node.js from nodejs.org.
 */
@Tag("network")
@DisabledOnOs(OS.WINDOWS)
class BootNodeSideBySideNetworkTest {

    @Test
    void the_boot_jar_serves_the_bundle_its_module_builds(@TempDir Path dir) throws Exception {
        Path app = dir.resolve("app");
        write(app.resolve("jk.toml"), """
                group = "com.example"
                name = "app"
                version = "1.0.0"
                java = 25
                node = 24

                [application]
                main = "app.Application"

                [spring-boot]
                version = "4.1.1"

                [dependencies]
                starter-webmvc = "org.springframework.boot:spring-boot-starter-webmvc"
                """);
        write(app.resolve("src/main/java/app/Application.java"), """
                package app;

                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.SpringBootApplication;

                @SpringBootApplication
                public class Application {
                    public static void main(String[] args) {
                        SpringApplication.run(Application.class, args);
                    }
                }
                """);
        Path node = app.resolve("src/main/node");
        write(node.resolve("package.json"), """
                {"name":"ui","version":"1.0.0","scripts":{"build":"node build.js"}}
                """);
        write(node.resolve("package-lock.json"), """
                {"name":"ui","version":"1.0.0","lockfileVersion":3,"requires":true,
                 "packages":{"":{"name":"ui","version":"1.0.0"}}}
                """);
        write(node.resolve("build.js"), bundle("first"));

        assertThat(run("build", "--skip-tests", "-C", app.toString())).isZero();
        Path jar = app.resolve("target/app-1.0.0.jar");
        assertThat(entry(jar, "BOOT-INF/classes/static/index.html")).contains("first");
        assertThat(serve(jar, dir.resolve("run1"))).contains("first");

        Path compiled = app.resolve("target/classes/app/Application.class");
        FileTime before = Files.getLastModifiedTime(compiled);
        write(node.resolve("build.js"), bundle("second"));
        assertThat(run("build", "--skip-tests", "-C", app.toString())).isZero();
        assertThat(entry(jar, "BOOT-INF/classes/static/index.html"))
                .as("the edit is repackaged")
                .contains("second");
        assertThat(Files.getLastModifiedTime(compiled))
                .as("a front-end edit compiles no Java")
                .isEqualTo(before);
    }

    private static String bundle(String text) {
        return """
                const fs = require('fs');
                fs.mkdirSync('dist', { recursive: true });
                fs.writeFileSync('dist/index.html', '<h1>%s</h1>');
                """.formatted(text);
    }

    private static String entry(Path jar, String name) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            ZipEntry e = file.getEntry(name);
            assertThat(e).as("%s in %s; entries: %s", name, jar, names(file)).isNotNull();
            return new String(file.getInputStream(e).readAllBytes());
        }
    }

    private static List<String> names(JarFile file) {
        return Collections.list(file.entries()).stream()
                .map(ZipEntry::getName)
                .filter(n -> n.contains("static"))
                .toList();
    }

    /** {@code java -jar} the Boot jar on a free port and return {@code GET /}'s body. */
    private static String serve(Path jar, Path logs) throws Exception {
        Files.createDirectories(logs);
        int port = freePort();
        Process java = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-jar",
                        jar.toString(),
                        "--server.port=" + port)
                .redirectOutput(logs.resolve("stdout.log").toFile())
                .redirectError(logs.resolve("stderr.log").toFile())
                .redirectInput(new File("/dev/null"))
                .start();
        // HTTP/1.1: some servers drop an h2c upgrade on plain http (next start answers nothing).
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        try {
            long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
            while (java.isAlive() && System.nanoTime() < deadline) {
                try {
                    HttpResponse<String> response = http.send(
                            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/"))
                                    .timeout(Duration.ofSeconds(5))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 200) return response.body();
                } catch (IOException notYet) {
                    // still starting
                }
                Thread.sleep(500);
            }
        } finally {
            java.destroy();
            java.waitFor();
        }
        throw new AssertionError(
                "the Boot jar never served /; stdout:\n" + Files.readString(logs.resolve("stdout.log")));
    }

    private static void write(Path file, String body) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
