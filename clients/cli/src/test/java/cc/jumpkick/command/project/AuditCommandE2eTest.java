// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.engine.EngineTestSupport;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.lock.LockManifestDigest;
import cc.jumpkick.model.command.Exit;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk audit} end to end — the CLI, a real engine, the forked auditor worker and a mock OSV:
 * one HIGH finding fails the gate, an {@code [audit] ignore} entry naming it passes, and an
 * expired entry fails again, with the finding's state on the {@code --output json} line.
 */
@Tag("integration")
class AuditCommandE2eTest {

    private HttpServer osv;
    private String base;

    @BeforeAll
    static void materializeEngine() {
        EngineTestSupport.ensureEngineMaterialized();
    }

    @BeforeEach
    void startOsv() throws IOException {
        osv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        osv.createContext("/querybatch", exchange -> {
            byte[] body = "{\"results\":[{\"vulns\":[{\"id\":\"GHSA-test-1\"}]}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        osv.createContext("/vulns/", exchange -> {
            byte[] body = """
                    {"id":"GHSA-test-1","summary":"Stub vulnerability","database_specific":{"severity":"HIGH"},
                     "affected":[{"package":{"ecosystem":"Maven","name":"com.foo:leaf"},
                                  "ranges":[{"type":"ECOSYSTEM","events":[{"introduced":"0"},{"fixed":"1.1"}]}]}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        osv.start();
        base = "http://127.0.0.1:" + osv.getAddress().getPort();
    }

    @AfterEach
    void stopOsv() {
        osv.stop(0);
    }

    @Test
    void one_high_finding_fails_the_gate_and_is_one_json_line(@TempDir Path dir) throws Exception {
        project(dir, "");

        Run r = audit(dir);

        assertThat(r.exit()).as("stdout:%n%s%nstderr:%n%s", r.out(), r.err()).isEqualTo(Exit.FAILURE);
        String line = r.findingLine();
        assertThat(Jsonl.str(line, "id")).isEqualTo("GHSA-test-1");
        assertThat(Jsonl.str(line, "package")).isEqualTo("com.foo:leaf");
        assertThat(Jsonl.str(line, "version")).isEqualTo("1.0");
        assertThat(Jsonl.str(line, "severity")).isEqualTo("HIGH");
        assertThat(Jsonl.str(line, "fixedIn")).isEqualTo("1.1");
        assertThat(Jsonl.bool(line, "ignored", true)).isFalse();
        assertThat(r.err()).contains("1 finding at or above HIGH");
    }

    @Test
    void an_ignore_entry_naming_the_advisory_passes_the_gate_and_says_why(@TempDir Path dir) throws Exception {
        project(dir, """

                [audit]
                ignore = [{ id = "GHSA-test-1", reason = "stub advisory, reviewed" }]
                """);

        Run r = audit(dir);

        assertThat(r.exit()).as("stdout:%n%s%nstderr:%n%s", r.out(), r.err()).isEqualTo(Exit.SUCCESS);
        String line = r.findingLine();
        assertThat(Jsonl.bool(line, "ignored", false)).isTrue();
        assertThat(Jsonl.str(line, "reason")).isEqualTo("stub advisory, reviewed");
    }

    @Test
    void an_expired_ignore_entry_fails_the_gate_again(@TempDir Path dir) throws Exception {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        project(dir, """

                [audit]
                ignore = [{ id = "GHSA-test-1", reason = "was waiting on upstream", until = "%s" }]
                """.formatted(yesterday));

        Run r = audit(dir);

        assertThat(r.exit()).as("stdout:%n%s%nstderr:%n%s", r.out(), r.err()).isEqualTo(Exit.FAILURE);
        String line = r.findingLine();
        assertThat(Jsonl.bool(line, "ignored", true)).isFalse();
        assertThat(Jsonl.bool(line, "ignoreExpired", false)).isTrue();
        assertThat(Jsonl.str(line, "until")).isEqualTo(yesterday.toString());
    }

    /** A manifest plus a lock stamped fresh against it, so the invisible freshen leaves the lock alone. */
    private static void project(Path dir, String auditTable) throws IOException {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.foo"
                name = "app"
                version = "0.1.0"
                java = 25
                """ + auditTable);
        Files.writeString(dir.resolve("jk-lock.toml"), """
                version = 1
                generated-by = "jk test"
                resolution-algorithm = "pubgrub-v1"
                manifests-sha256 = "%s"

                [jdk]
                suggested-vendor = "temurin"
                suggested-version = "25.0.3"

                [[artifact]]
                name     = "com.foo:leaf"
                version  = "1.0"
                source   = "central+https://repo.maven.apache.org/maven2/"
                checksum = "sha256:d65226949713c4c61a784f41c51167e7b0316f93764398ebba9e4336b3d954c2"
                scopes   = ["main"]
                """.formatted(LockManifestDigest.compute(dir)));
    }

    private Run audit(Path dir) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream origOut = System.out;
        PrintStream origErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        int exit;
        try {
            exit = run(
                    "audit",
                    "-C",
                    dir.toString(),
                    "--severity",
                    "HIGH",
                    "--output",
                    "json",
                    "--osv-batch-url",
                    base + "/querybatch",
                    "--osv-vulns-url",
                    base + "/vulns/");
        } finally {
            System.setOut(origOut);
            System.setErr(origErr);
        }
        return new Run(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Run(int exit, String out, String err) {
        /** The one {@code audit-finding} line among the run's JSONL. */
        String findingLine() {
            List<String> lines = out.lines()
                    .filter(l -> "audit-finding".equals(Jsonl.str(l, "type")))
                    .toList();
            assertThat(lines).as("audit-finding lines in%n%s", out).hasSize(1);
            String line = lines.getFirst();
            assertThat(Jsonl.intValue(line, "schema", -1)).isEqualTo(1);
            return line;
        }
    }
}
