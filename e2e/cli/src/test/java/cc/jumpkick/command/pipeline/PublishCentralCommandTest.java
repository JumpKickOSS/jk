// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.publish.testkit.GpgTestFixture;
import cc.jumpkick.repo.RepoCredentialStore;
import cc.jumpkick.testing.SysProps;
import cc.jumpkick.util.MarkdownReports;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk publish} to {@code central} against a stub Portal on loopback: the upload hands out a
 * deployment id, the status endpoint walks the Portal's states, the publish call releases the
 * deployment, and one variant fails validation with two errors. The token comes from the {@code
 * central} entry of the credential store, bound to the stub's origin the way {@code jk repo login
 * central -U …} binds it.
 */
@Tag("integration")
@SysProps.TempRoots("jk.m2.local")
class PublishCentralCommandTest {

    private static final String STEM = "com/example/widget/1.0.0/widget-1.0.0";

    private HttpServer server;
    private URI base;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile byte[] uploaded = new byte[0];
    private final AtomicInteger polls = new AtomicInteger();
    private volatile List<String> walk = List.of("PENDING", "VALIDATING", "VALIDATED");
    private final AtomicInteger releasedPolls = new AtomicInteger();
    private volatile boolean released;
    private volatile String errorsJson = "{}";
    private final RepoCredentialStore store = new RepoCredentialStore();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/publisher/upload", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " "
                    + exchange.getRequestHeaders().getFirst("Authorization"));
            uploaded = exchange.getRequestBody().readAllBytes();
            byte[] id = "dep-1234".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, id.length);
            exchange.getResponseBody().write(id);
            exchange.close();
        });
        server.createContext("/api/v1/publisher/deployment/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            released = true;
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.createContext("/api/v1/publisher/status", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            String state = released
                    ? List.of("PUBLISHING", "PUBLISHED").get(Math.min(releasedPolls.getAndIncrement(), 1))
                    : walk.get(Math.min(polls.getAndIncrement(), walk.size() - 1));
            String body = "{\"deploymentId\":\"dep-1234\",\"deploymentState\":\"" + state + "\""
                    + ("FAILED".equals(state) ? ",\"errors\":" + errorsJson : "") + "}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        store.write("central", new RepoCredential.Bearer("portal-token"), List.of(base));
        System.setProperty("jk.env.JK_CENTRAL_PORTAL_URL", base.toString());
    }

    @AfterEach
    void stop() {
        server.stop(0);
        store.clear("central");
        System.clearProperty("jk.env.JK_CENTRAL_PORTAL_URL");
    }

    @Test
    void a_publish_uploads_the_signed_bundle_and_releases_the_deployment(@TempDir Path dir) throws Exception {
        writeLibrary(dir, "");

        String out = Capture.stdout(() ->
                assertThat(run("publish", "--no-ansi", "-C", dir.toString())).isEqualTo(0));

        assertThat(requests.get(0))
                .isEqualTo(
                        "POST /api/v1/publisher/upload?name=widget-1.0.0&publishingType=USER_MANAGED Bearer portal-token");
        assertThat(requests.subList(1, requests.size()))
                .containsExactly(
                        "POST /api/v1/publisher/status?id=dep-1234",
                        "POST /api/v1/publisher/status?id=dep-1234",
                        "POST /api/v1/publisher/status?id=dep-1234",
                        "POST /api/v1/publisher/deployment/dep-1234",
                        "POST /api/v1/publisher/status?id=dep-1234",
                        "POST /api/v1/publisher/status?id=dep-1234");
        List<String> entries = zipEntries(multipartFile(uploaded));
        assertThat(entries)
                .containsExactly(
                        STEM + ".jar",
                        STEM + ".jar.asc",
                        STEM + ".jar.md5",
                        STEM + ".jar.sha1",
                        STEM + ".pom",
                        STEM + ".pom.asc",
                        STEM + ".pom.md5",
                        STEM + ".pom.sha1",
                        STEM + "-sources.jar",
                        STEM + "-sources.jar.asc",
                        STEM + "-sources.jar.md5",
                        STEM + "-sources.jar.sha1",
                        STEM + "-javadoc.jar",
                        STEM + "-javadoc.jar.asc",
                        STEM + "-javadoc.jar.md5",
                        STEM + "-javadoc.jar.sha1");
        assertThat(out)
                .contains("Published com.example:widget:1.0.0 to the Central Portal (16 files)")
                .contains("  Central bundle: 16 files, ")
                .contains("deployment dep-1234")
                .contains("PUBLISHED");

        String results = MarkdownReports.strip(Files.readString(dir.resolve("target/jk-results.md")));
        assertThat(results)
                .contains("## Publish")
                .contains("| library | Central Portal | 16 | published |")
                .contains("- Central bundle: 16 files, ")
                .contains("- deployment: `dep-1234` · **PUBLISHED**");
    }

    @Test
    void hold_stops_at_validated_and_leaves_the_release_to_the_portal(@TempDir Path dir) throws Exception {
        writeLibrary(dir, "hold = true");

        String out = Capture.stdout(() ->
                assertThat(run("publish", "--no-ansi", "-C", dir.toString())).isEqualTo(0));

        assertThat(requests).noneMatch(r -> r.contains("/deployment/"));
        assertThat(polls.get()).isEqualTo(3);
        assertThat(out).contains("deployment dep-1234").contains("VALIDATED; release it from the Portal");
        assertThat(MarkdownReports.strip(Files.readString(dir.resolve("target/jk-results.md"))))
                .contains("- deployment: `dep-1234` · **VALIDATED**");
    }

    @Test
    void a_rejected_deployment_fails_the_run_and_writes_every_validation_error(@TempDir Path dir) throws Exception {
        walk = List.of("PENDING", "FAILED");
        errorsJson = "{\"common\":[\"Missing signature for file: widget-1.0.0.pom\"],"
                + "\"pkg:maven/com.example/widget@1.0.0\":[\"Javadocs must be provided but not found in entries\"]}";
        writeLibrary(dir, "");

        Capture.Streams streams = Capture.both(() ->
                assertThat(run("publish", "--no-ansi", "-C", dir.toString())).isEqualTo(1));

        assertThat(streams.err())
                .contains("dep-1234")
                .contains("Missing signature for file: widget-1.0.0.pom")
                .contains("Javadocs must be provided but not found in entries");
        String results = MarkdownReports.strip(Files.readString(dir.resolve("target/jk-results.md")));
        assertThat(results)
                .startsWith("# jk results — FAIL")
                .contains("- deployment: `dep-1234` · **FAILED**")
                .contains("- validation errors:\n  - Missing signature for file: widget-1.0.0.pom\n"
                        + "  - Javadocs must be provided but not found in entries\n");
    }

    @Test
    void a_dry_run_writes_the_bundle_under_target_and_lists_its_entries(@TempDir Path dir) throws Exception {
        writeLibrary(dir, "");

        String out = Capture.stdout(() -> assertThat(run("publish", "--no-ansi", "-C", dir.toString(), "--dry-run"))
                .isEqualTo(0));

        assertThat(requests).isEmpty();
        Path zip = dir.resolve("target/publish/central-bundle.zip");
        assertThat(zip).exists();
        assertThat(zipEntries(Files.readAllBytes(zip)))
                .hasSize(16)
                .contains(STEM + ".pom.asc", STEM + "-javadoc.jar.sha1");
        assertThat(out)
                .contains("(dry-run)")
                .contains("  Central bundle: 16 files, ")
                .contains("wrote target/publish/central-bundle.zip")
                .contains("    " + STEM + ".jar")
                .contains("    " + STEM + "-sources.jar.asc");
        assertThat(MarkdownReports.strip(Files.readString(dir.resolve("target/jk-results.md"))))
                .contains("| library | Central Portal | 16 | dry run |")
                .contains("- Central bundle: 16 files, ")
                .contains("- bundle (16 entries):");
    }

    @Test
    void an_assembly_app_dry_run_publishes_the_thin_jar(@TempDir Path dir) throws Exception {
        var key = GpgTestFixture.generate(dir, "");
        Path app = Files.createDirectories(dir.resolve("app"));
        Files.createDirectories(app.resolve("src/main/java/com/example"));
        Files.writeString(app.resolve("src/main/java/com/example/Main.java"), """
                package com.example;
                public class Main {
                    public static void main(String[] args) {}
                }
                """);
        String manifest = """
                group       = "com.example"
                name        = "widget"
                version     = "1.0.0"
                description = "A widget CLI"
                java        = 25
                sources     = "always"

                [application]
                main     = "com.example.Main"
                assembly = true

                [publish]
                repository = "central"
                key-file = "%s"
                sbom = false
                provenance = false
                url = "https://example.com/widget"
                licenses = [{ name = "Apache-2.0", url = "https://www.apache.org/licenses/LICENSE-2.0" }]
                developers = [{ id = "ada", name = "Ada Lovelace" }]
                scm = { url = "https://github.com/example/widget", connection = "scm:git:https://github.com/example/widget.git", developer-connection = "scm:git:ssh://git@github.com/example/widget.git" }
                """.formatted(key.secretKeyFile());
        Files.writeString(app.resolve("jk.toml"), manifest);

        assertThat(run("publish", "--no-ansi", "-C", app.toString(), "--dry-run"))
                .isEqualTo(0);

        Path zip = app.resolve("target/publish/central-bundle.zip");
        List<String> defaultEntries = zipEntries(Files.readAllBytes(zip));
        assertThat(defaultEntries)
                .hasSize(16)
                .contains(STEM + ".jar", STEM + ".pom", STEM + "-sources.jar", STEM + "-javadoc.jar")
                .noneMatch(e -> e.contains("-all.jar"));
        assertThat(app.resolve("target/widget-1.0.0-all.jar"))
                .as("the fat jar is built on disk")
                .exists();
    }

    @Test
    void central_without_a_signing_key_is_refused_naming_the_key_file_setting(@TempDir Path dir) throws Exception {
        writeLibrary(dir, "");
        Files.writeString(
                dir.resolve("jk.toml"), Files.readString(dir.resolve("jk.toml")).replaceAll("(?m)^key-file.*\\n", ""));
        String err = Capture.stderr(
                () -> assertThat(run("publish", "-C", dir.toString())).isEqualTo(2));
        assertThat(err).contains("[publish] key-file");
        assertThat(requests).isEmpty();
    }

    @Test
    void central_with_signing_turned_off_is_refused(@TempDir Path dir) throws Exception {
        writeLibrary(dir, "sign = false");
        String err = Capture.stderr(
                () -> assertThat(run("publish", "-C", dir.toString())).isEqualTo(2));
        assertThat(err).contains("sign = false");
        assertThat(requests).isEmpty();
    }

    @Test
    void central_without_the_library_artefacts_or_the_pom_metadata_is_refused_before_upload(@TempDir Path dir)
            throws Exception {
        var key = GpgTestFixture.generate(dir, "");
        Files.writeString(dir.resolve("jk.toml"), """
                group       = "com.example"
                name        = "widget"
                version     = "1.0.0"
                description = "A widget"
                java        = 25

                [publish]
                repository = "central"
                key-file = "%s"
                sbom = false
                provenance = false
                """.formatted(key.secretKeyFile()));
        writeZip(dir.resolve("target/widget-1.0.0.jar"));

        String[] args = {"publish", "-C", dir.toString()};
        assertThat(run(args)).isEqualTo(1);
        assertThat(MarkdownReports.strip(Files.readString(dir.resolve("target/jk-results.md"))))
                .contains("widget-1.0.0-sources.jar")
                .contains("widget-1.0.0-javadoc.jar");

        writeZip(dir.resolve("target/widget-1.0.0-sources.jar"));
        writeZip(dir.resolve("target/widget-1.0.0-javadoc.jar"));
        assertThat(run(args)).isEqualTo(1);
        assertThat(MarkdownReports.strip(Files.readString(dir.resolve("target/jk-results.md"))))
                .contains("Maven Central needs POM metadata: url, licenses, developers, scm");
        assertThat(requests).isEmpty();
    }

    /** A library that publishes to Central, signed with a fresh key; {@code extra} is more {@code [publish]} lines. */
    private static void writeLibrary(Path dir, String extra) throws Exception {
        var key = GpgTestFixture.generate(dir, "");
        Files.writeString(dir.resolve("jk.toml"), """
                group       = "com.example"
                name        = "widget"
                version     = "1.0.0"
                description = "A widget"
                java        = 25

                [publish]
                repository = "central"
                key-file = "%s"
                sbom = false
                provenance = false
                %s
                url = "https://example.com/widget"
                licenses = [{ name = "Apache-2.0", url = "https://www.apache.org/licenses/LICENSE-2.0" }]
                developers = [{ id = "ada", name = "Ada Lovelace" }]
                scm = { url = "https://github.com/example/widget", connection = "scm:git:https://github.com/example/widget.git", developer-connection = "scm:git:ssh://git@github.com/example/widget.git" }
                """.formatted(key.secretKeyFile(), extra));
        writeZip(dir.resolve("target/widget-1.0.0.jar"));
        writeZip(dir.resolve("target/widget-1.0.0-sources.jar"));
        writeZip(dir.resolve("target/widget-1.0.0-javadoc.jar"));
    }

    /** An empty zip: the four bytes of an end-of-central-directory record with no entries. */
    private static void writeZip(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, new byte[] {0x50, 0x4b, 0x05, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
    }

    /** The one file part of a multipart body: what follows the part headers, up to the closing delimiter. */
    private static byte[] multipartFile(byte[] body) {
        int start = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII), 0) + 4;
        int end = lastIndexOf(body, "\r\n--".getBytes(StandardCharsets.US_ASCII));
        assertThat(start).isGreaterThan(3);
        assertThat(end).isGreaterThan(start);
        byte[] out = new byte[end - start];
        System.arraycopy(body, start, out, 0, out.length);
        return out;
    }

    private static List<String> zipEntries(byte[] zip) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) names.add(e.getName());
        }
        return names;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = from; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    private static int lastIndexOf(byte[] haystack, byte[] needle) {
        int last = -1;
        for (int i = indexOf(haystack, needle, 0); i >= 0; i = indexOf(haystack, needle, i + 1)) last = i;
        return last;
    }
}
