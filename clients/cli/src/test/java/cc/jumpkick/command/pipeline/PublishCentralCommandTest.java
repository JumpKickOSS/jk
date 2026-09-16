// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.publish.testkit.GpgTestFixture;
import cc.jumpkick.repo.RepoCredentialStore;
import cc.jumpkick.testing.SysProps;
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
 * {@code jk publish --central} against a stub Central Portal on loopback: the upload hands out a
 * deployment id, the status endpoint walks the Portal's states, and one variant fails validation
 * with two errors. The token comes from the {@code central} entry of the credential store, bound
 * to the stub's origin the way {@code jk repo login central --url …} binds it.
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
        server.createContext("/api/v1/publisher/status", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            String state = walk.get(Math.min(polls.getAndIncrement(), walk.size() - 1));
            String body = "{\"deploymentId\":\"dep-1234\",\"deploymentState\":\"" + state + "\""
                    + ("FAILED".equals(state) ? ",\"errors\":" + errorsJson : "") + "}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        store.write("central", new RepoCredential.Bearer("portal-token"), base);
    }

    @AfterEach
    void stop() {
        server.stop(0);
        store.clear("central");
    }

    @Test
    void a_user_managed_publish_uploads_the_signed_bundle_and_polls_to_validated(@TempDir Path dir) throws Exception {
        var key = GpgTestFixture.generate(dir, "pass");
        writeLibrary(dir);

        String out = Capture.stdout(() -> assertThat(run(
                        "publish",
                        "-C",
                        dir.toString(),
                        "--central",
                        "--repo-url",
                        base.toString(),
                        "--sign",
                        "--key-file",
                        key.secretKeyFile().toString(),
                        "--key-passphrase",
                        "pass"))
                .isEqualTo(0));

        assertThat(requests.get(0))
                .isEqualTo(
                        "POST /api/v1/publisher/upload?name=widget-1.0.0&publishingType=USER_MANAGED Bearer portal-token");
        assertThat(requests.subList(1, requests.size()))
                .containsExactly(
                        "POST /api/v1/publisher/status?id=dep-1234",
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
                .contains("deployment dep-1234 · VALIDATED");

        String results = Files.readString(dir.resolve("target/jk-results.md"));
        assertThat(results)
                .contains("## Publish")
                .contains("- destination: Central Portal (user-managed)")
                .contains("- files: 16")
                .contains("- deployment: `dep-1234` · **VALIDATED**");
    }

    @Test
    void an_automatic_publish_polls_through_to_published(@TempDir Path dir) throws Exception {
        walk = List.of("PENDING", "VALIDATED", "PUBLISHING", "PUBLISHED");
        var key = GpgTestFixture.generate(dir, "pass");
        writeLibrary(dir);

        int exit = run(
                "publish",
                "-C",
                dir.toString(),
                "--central",
                "--repo-url",
                base.toString(),
                "--publishing-type",
                "automatic",
                "--sign",
                "--key-file",
                key.secretKeyFile().toString(),
                "--key-passphrase",
                "pass");
        assertThat(exit).isEqualTo(0);
        assertThat(requests.get(0)).contains("publishingType=AUTOMATIC");
        assertThat(polls.get()).isEqualTo(4);
        assertThat(Files.readString(dir.resolve("target/jk-results.md")))
                .contains("- destination: Central Portal (automatic)")
                .contains("- deployment: `dep-1234` · **PUBLISHED**");
    }

    @Test
    void a_rejected_deployment_fails_the_run_and_writes_every_validation_error(@TempDir Path dir) throws Exception {
        walk = List.of("PENDING", "FAILED");
        errorsJson = "{\"common\":[\"Missing signature for file: widget-1.0.0.pom\"],"
                + "\"pkg:maven/com.example/widget@1.0.0\":[\"Javadocs must be provided but not found in entries\"]}";
        var key = GpgTestFixture.generate(dir, "pass");
        writeLibrary(dir);

        Capture.Streams streams = Capture.both(() -> assertThat(run(
                        "publish",
                        "-C",
                        dir.toString(),
                        "--central",
                        "--repo-url",
                        base.toString(),
                        "--sign",
                        "--key-file",
                        key.secretKeyFile().toString(),
                        "--key-passphrase",
                        "pass"))
                .isEqualTo(1));

        assertThat(streams.err())
                .contains("dep-1234")
                .contains("Missing signature for file: widget-1.0.0.pom")
                .contains("Javadocs must be provided but not found in entries");
        String results = Files.readString(dir.resolve("target/jk-results.md"));
        assertThat(results)
                .startsWith("# jk results — FAIL")
                .contains("- deployment: `dep-1234` · **FAILED**")
                .contains("- validation errors:\n  - Missing signature for file: widget-1.0.0.pom\n"
                        + "  - Javadocs must be provided but not found in entries\n");
    }

    @Test
    void a_dry_run_writes_the_bundle_under_target_and_lists_its_entries(@TempDir Path dir) throws Exception {
        var key = GpgTestFixture.generate(dir, "pass");
        writeLibrary(dir);

        String out = Capture.stdout(() -> assertThat(run(
                        "publish",
                        "-C",
                        dir.toString(),
                        "--central",
                        "--dry-run",
                        "--sign",
                        "--key-file",
                        key.secretKeyFile().toString(),
                        "--key-passphrase",
                        "pass"))
                .isEqualTo(0));

        assertThat(requests).isEmpty();
        Path zip = dir.resolve("target/publish/central-bundle.zip");
        assertThat(zip).exists();
        assertThat(zipEntries(Files.readAllBytes(zip)))
                .hasSize(16)
                .contains(STEM + ".pom.asc", STEM + "-javadoc.jar.sha1");
        assertThat(out)
                .contains("(dry-run)")
                .contains("wrote target/publish/central-bundle.zip")
                .contains("    " + STEM + ".jar")
                .contains("    " + STEM + "-sources.jar.asc");
        assertThat(Files.readString(dir.resolve("target/jk-results.md")))
                .contains("- destination: Central Portal (user-managed) (dry run)")
                .contains("- bundle (16 entries):");
    }

    @Test
    void central_without_a_signing_key_is_refused_with_the_flags_that_fix_it(@TempDir Path dir) throws Exception {
        writeLibrary(dir);
        String err = Capture.stderr(
                () -> assertThat(run("publish", "-C", dir.toString(), "--central", "--repo-url", base.toString()))
                        .isEqualTo(64));
        assertThat(err).contains("--sign --key-file");
        assertThat(requests).isEmpty();
    }

    @Test
    void central_without_the_library_artefacts_or_the_pom_metadata_is_refused_before_upload(@TempDir Path dir)
            throws Exception {
        var key = GpgTestFixture.generate(dir, "pass");
        Files.writeString(dir.resolve("jk.toml"), """
                group       = "com.example"
                name        = "widget"
                version     = "1.0.0"
                description = "A widget"
                java        = 25
                """);
        writeZip(dir.resolve("target/lib/widget-1.0.0.jar"));

        String[] args = {
            "publish",
            "-C",
            dir.toString(),
            "--central",
            "--repo-url",
            base.toString(),
            "--sign",
            "--key-file",
            key.secretKeyFile().toString(),
            "--key-passphrase",
            "pass"
        };
        assertThat(run(args)).isEqualTo(1);
        assertThat(Files.readString(dir.resolve("target/jk-results.md")))
                .contains("widget-1.0.0-sources.jar")
                .contains("widget-1.0.0-javadoc.jar");

        writeZip(dir.resolve("target/lib/widget-1.0.0-sources.jar"));
        writeZip(dir.resolve("target/lib/widget-1.0.0-javadoc.jar"));
        assertThat(run(args)).isEqualTo(1);
        assertThat(Files.readString(dir.resolve("target/jk-results.md")))
                .contains("Maven Central requires POM metadata this manifest lacks: url, licenses, developers, scm");
        assertThat(requests).isEmpty();
    }

    private static void writeLibrary(Path dir) throws IOException {
        Files.writeString(dir.resolve("jk.toml"), """
                group       = "com.example"
                name        = "widget"
                version     = "1.0.0"
                description = "A widget"
                java        = 25

                [publish]
                url = "https://example.com/widget"
                licenses = [{ name = "Apache-2.0", url = "https://www.apache.org/licenses/LICENSE-2.0" }]
                developers = [{ id = "ada", name = "Ada Lovelace" }]
                scm = { url = "https://github.com/example/widget", connection = "scm:git:https://github.com/example/widget.git", developer-connection = "scm:git:ssh://git@github.com/example/widget.git" }
                """);
        writeZip(dir.resolve("target/lib/widget-1.0.0.jar"));
        writeZip(dir.resolve("target/lib/widget-1.0.0-sources.jar"));
        writeZip(dir.resolve("target/lib/widget-1.0.0-javadoc.jar"));
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
