// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.publish.testkit.GpgTestFixture;
import cc.jumpkick.testing.SysProps;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
@SysProps.TempRoots("jk.m2.local")
class PublishCommandTest {

    private HttpServer server;
    private URI base;
    private final Map<String, byte[]> received = new HashMap<>();
    /** When set, the metadata GET answers this status — the transient-failure shape. */
    private volatile int metadataGetStatus = 0;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            switch (exchange.getRequestMethod()) {
                case "PUT" -> {
                    received.put(path, exchange.getRequestBody().readAllBytes());
                    exchange.sendResponseHeaders(201, -1);
                }
                // Serve stored bytes so metadata GET can merge the version list.
                case "GET", "HEAD" -> {
                    if (metadataGetStatus != 0 && path.endsWith("maven-metadata.xml")) {
                        exchange.sendResponseHeaders(metadataGetStatus, -1);
                        exchange.close();
                        return;
                    }
                    byte[] body = received.get(path);
                    if (body == null) {
                        exchange.sendResponseHeaders(404, -1);
                    } else {
                        exchange.sendResponseHeaders(200, body.length);
                        if (!"HEAD".equals(exchange.getRequestMethod())) {
                            exchange.getResponseBody().write(body);
                        }
                    }
                }
                default -> exchange.sendResponseHeaders(405, -1);
            }
            exchange.close();
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/repo/");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void publishes_jar_pom_sources_and_checksums(@TempDir Path tempDir) throws Exception {
        // sources = true → PUBLISH mode: the sources jar is assembled at publish time
        // (sourcesMode defaults to DISABLED, so it must be opted into here).
        Files.writeString(tempDir.resolve("jk.toml"), """
                group    = "com.example"
                name     = "widget"
                version  = "1.0.0"
                jdk      = 25
                sources  = true
                """);
        writeJar(tempDir.resolve("target/lib/widget-1.0.0.jar"));
        writeSource(
                tempDir.resolve("src/main/java/com/example/Widget.java"),
                "package com.example; public class Widget {}");

        int exit = run("publish", "-C", tempDir.toString(), "--repo-url", base.toString());
        assertThat(exit).isEqualTo(0);

        String prefix = "/repo/com/example/widget/1.0.0/widget-1.0.0";
        // Main jar, POM, sources jar — each with four checksum files.
        assertThat(received)
                .containsKeys(
                        prefix + ".jar",
                        prefix + ".jar.sha256",
                        prefix + ".pom",
                        prefix + ".pom.sha256",
                        prefix + "-sources.jar",
                        prefix + "-sources.jar.sha256");
        String pom = new String(received.get(prefix + ".pom"), StandardCharsets.UTF_8);
        assertThat(pom).contains("<artifactId>widget</artifactId>");
        assertThat(pom).contains("<version>1.0.0</version>");
    }

    @Test
    void snapshot_version_refused_by_default(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("jk.toml"), """
                group    = "com.example"
                name     = "widget"
                version  = "1.0.0-SNAPSHOT"
                jdk      = 25
                """);
        writeJar(tempDir.resolve("target/lib/widget-1.0.0-SNAPSHOT.jar"));

        int exit = run("publish", "-C", tempDir.toString(), "--repo-url", base.toString());
        assertThat(exit).isEqualTo(65); // EX_DATAERR
        assertThat(received).isEmpty();
    }

    @Test
    void snapshot_version_allowed_with_flag(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("jk.toml"), """
                group    = "com.example"
                name     = "widget"
                version  = "1.0.0-SNAPSHOT"
                jdk      = 25
                """);
        writeJar(tempDir.resolve("target/lib/widget-1.0.0-SNAPSHOT.jar"));

        int exit = run("publish", "-C", tempDir.toString(), "--repo-url", base.toString(), "--allow-snapshot");
        assertThat(exit).isEqualTo(0);
    }

    @Test
    void sources_disabled_by_default_no_sources_jar(@TempDir Path tempDir) throws Exception {
        writeJkBuild(tempDir);
        writeJar(tempDir.resolve("target/lib/widget-1.0.0.jar"));

        int exit = run("publish", "-C", tempDir.toString(), "--repo-url", base.toString());
        assertThat(exit).isEqualTo(0);
        assertThat(received.keySet()).noneMatch(k -> k.contains("-sources.jar"));
    }

    @Test
    void missing_jar_returns_no_input(@TempDir Path tempDir) throws Exception {
        writeJkBuild(tempDir);
        int exit = run("publish", "-C", tempDir.toString(), "--repo-url", base.toString());
        assertThat(exit).isEqualTo(66);
    }

    @Test
    void signed_publish_uploads_asc_files(@TempDir Path tempDir) throws Exception {
        // Generate a throwaway secret key via the supply-chain test fixture.
        var key = GpgTestFixture.generate(tempDir, "pass");

        writeJkBuild(tempDir);
        writeJar(tempDir.resolve("target/lib/widget-1.0.0.jar"));

        int exit = run(
                "publish",
                "-C",
                tempDir.toString(),
                "--repo-url",
                base.toString(),
                "--sign",
                "--key-file",
                key.secretKeyFile().toString(),
                "--key-passphrase",
                "pass");
        assertThat(exit).isEqualTo(0);

        String stem = "/repo/com/example/widget/1.0.0/widget-1.0.0";
        assertThat(received).containsKeys(stem + ".jar.asc", stem + ".pom.asc");
        assertThat(new String(received.get(stem + ".jar.asc"), StandardCharsets.UTF_8))
                .startsWith("-----BEGIN PGP SIGNATURE-----");
    }

    @Test
    void sign_without_key_file_errors(@TempDir Path tempDir) throws Exception {
        writeJkBuild(tempDir);
        writeJar(tempDir.resolve("target/lib/widget-1.0.0.jar"));
        int exit = run("publish", "-C", tempDir.toString(), "--repo-url", base.toString(), "--sign");
        // CommandLine propagates the runtime error as a non-zero exit.
        assertThat(exit).isNotZero();
    }

    @Test
    void dry_run_makes_no_http_requests(@TempDir Path tempDir) throws Exception {
        writeJkBuild(tempDir);
        writeJar(tempDir.resolve("target/lib/widget-1.0.0.jar"));

        int exit = run("publish", "-C", tempDir.toString(), "--repo-url", base.toString(), "--dry-run");
        assertThat(exit).isEqualTo(0);
        assertThat(received).isEmpty();
    }

    @Test
    void slsa_emits_intoto_provenance_for_the_main_jar(@TempDir Path tempDir) throws Exception {
        writeJkBuild(tempDir);
        writeJar(tempDir.resolve("target/lib/widget-1.0.0.jar"));

        int exit = run("publish", "-C", tempDir.toString(), "--repo-url", base.toString(), "--slsa");
        assertThat(exit).isEqualTo(0);

        String stem = "/repo/com/example/widget/1.0.0/widget-1.0.0";
        assertThat(received).containsKey(stem + ".intoto.json");
        String provenance = new String(received.get(stem + ".intoto.json"), StandardCharsets.UTF_8);
        assertThat(provenance).contains("\"_type\":\"https://in-toto.io/Statement/v1\"");
        assertThat(provenance).contains("\"predicateType\":\"https://slsa.dev/provenance/v1\"");
        assertThat(provenance).contains("\"name\":\"widget-1.0.0.jar\"");
    }

    @Test
    void sbom_emits_cyclonedx_and_spdx_sidecars(@TempDir Path tempDir) throws Exception {
        writeJkBuild(tempDir);
        writeJar(tempDir.resolve("target/lib/widget-1.0.0.jar"));

        int exit = run("publish", "-C", tempDir.toString(), "--repo-url", base.toString(), "--sbom");
        assertThat(exit).isEqualTo(0);

        String stem = "/repo/com/example/widget/1.0.0/widget-1.0.0";
        assertThat(received)
                .containsKeys(
                        stem + "-cyclonedx.json",
                        stem + "-cyclonedx.json.sha256",
                        stem + "-spdx.json",
                        stem + "-spdx.json.sha256");
        assertThat(new String(received.get(stem + "-cyclonedx.json"), StandardCharsets.UTF_8))
                .contains("\"bomFormat\":\"CycloneDX\"")
                .contains("pkg:maven/com.example/widget@1.0.0");
        assertThat(new String(received.get(stem + "-spdx.json"), StandardCharsets.UTF_8))
                .contains("\"spdxVersion\":\"SPDX-2.3\"")
                .contains("\"documentDescribes\":[\"SPDXRef-Package-Root\"]");
    }

    @Test
    void sigstore_dry_run_does_not_call_fulcio(@TempDir Path tempDir) throws Exception {
        // --dry-run must not attempt to initialise the keyless signer, which
        // would otherwise need network + OIDC. Same goes for --sign without a
        // key file — dry-run is the path users hit while exploring the command.
        writeJkBuild(tempDir);
        writeJar(tempDir.resolve("target/lib/widget-1.0.0.jar"));

        int exit = run("publish", "-C", tempDir.toString(), "--repo-url", base.toString(), "--sigstore", "--dry-run");
        assertThat(exit).isEqualTo(0);
        assertThat(received).isEmpty();
    }

    // --- helpers -----------------------------------------------------------

    private static void writeJkBuild(Path projectDir) throws IOException {
        Files.writeString(projectDir.resolve("jk.toml"), """
                group    = "com.example"
                name     = "widget"
                version  = "1.0.0"
                jdk      = 25
                """);
    }

    @Test
    void refuses_to_publish_a_composite_path_dependency(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("jk.toml"), """
                group    = "com.example"
                name     = "widget"
                version  = "1.0.0"
                jdk      = 25

                [dependencies]
                lib = { path = "../lib" }
                """);
        writeJar(tempDir.resolve("target/lib/widget-1.0.0.jar"));

        int exit = run("publish", "-C", tempDir.toString(), "--repo-url", base.toString());

        assertThat(exit).isNotEqualTo(0);
        assertThat(received).isEmpty(); // rejected before any upload
    }

    private static void writeJar(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        // The publisher just streams bytes — content doesn't need to be a real jar.
        Files.write(path, "pretend-jar".getBytes(StandardCharsets.UTF_8));
    }

    private static void writeSource(Path path, String text) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
    }
    /**
     * The version list must accumulate across publishes. Before a failed metadata read was
     * swallowed and replaced with a single-version document, so publishing 0.2.0 erased 0.1.0 — and
     * this suite could not see it, because its server answered 405 to every GET and so every read
     * "failed". This is the end-to-end assertion that would have caught it.
     */
    @Test
    void publishing_a_second_version_keeps_the_first_in_the_metadata(@TempDir Path tempDir) throws Exception {
        String manifest = """
                group    = "com.example"
                name     = "widget"
                version  = "%s"
                jdk      = 25
                """;

        Files.writeString(tempDir.resolve("jk.toml"), manifest.formatted("0.1.0"));
        writeJar(tempDir.resolve("target/lib/widget-0.1.0.jar"));
        assertThat(run("publish", "-C", tempDir.toString(), "--repo-url", base.toString()))
                .isEqualTo(0);

        Files.writeString(tempDir.resolve("jk.toml"), manifest.formatted("0.2.0"));
        writeJar(tempDir.resolve("target/lib/widget-0.2.0.jar"));
        assertThat(run("publish", "-C", tempDir.toString(), "--repo-url", base.toString()))
                .isEqualTo(0);

        String metadata =
                new String(received.get("/repo/com/example/widget/maven-metadata.xml"), StandardCharsets.UTF_8);
        assertThat(metadata).contains("<version>0.1.0</version>").contains("<version>0.2.0</version>");
    }

    /**
     * A transient failure reading the existing metadata must not replace the version list with a
     * single-version document. This is the end-to-end guard for: the artifacts are already
     * uploaded at that point, so silently truncating is unrecoverable on a real repository.
     *
     * <p>A 403 rather than a 503 on purpose — `Http` never retries it, and a write-only deploy
     * credential is the likeliest way a real publish hits this, which would truncate on *every* run.
     */
    @Test
    void a_failed_metadata_read_does_not_truncate_the_version_list(@TempDir Path tempDir) throws Exception {
        String manifest = """
                group    = "com.example"
                name     = "widget"
                version  = "%s"
                jdk      = 25
                """;

        Files.writeString(tempDir.resolve("jk.toml"), manifest.formatted("0.1.0"));
        writeJar(tempDir.resolve("target/lib/widget-0.1.0.jar"));
        assertThat(run("publish", "-C", tempDir.toString(), "--repo-url", base.toString()))
                .isEqualTo(0);
        byte[] afterFirst = received.get("/repo/com/example/widget/maven-metadata.xml");
        assertThat(new String(afterFirst, StandardCharsets.UTF_8)).contains("<version>0.1.0</version>");

        metadataGetStatus = 403;
        Files.writeString(tempDir.resolve("jk.toml"), manifest.formatted("0.2.0"));
        writeJar(tempDir.resolve("target/lib/widget-0.2.0.jar"));

        assertThat(run("publish", "-C", tempDir.toString(), "--repo-url", base.toString()))
                .as("publish must fail loudly rather than truncate the version list")
                .isNotEqualTo(0);

        assertThat(new String(received.get("/repo/com/example/widget/maven-metadata.xml"), StandardCharsets.UTF_8))
                .as("the stored version list must still hold 0.1.0")
                .contains("<version>0.1.0</version>");
    }
}
