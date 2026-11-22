// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.engine.EngineTestSupport;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.lock.testing.CommittedLocks;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk audit} end to end — the CLI, a real engine, the forked auditor worker and a mock OSV:
 * one HIGH finding fails the gate, an {@code [audit] ignore} entry naming it passes, and an
 * expired entry fails again, with the finding's state on the {@code -O jsonl} line.
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

    /** The verdict an agent or a CI log reads says what the exit status says, and names the finding. */
    @Test
    void the_agent_verdict_fails_and_names_the_blocking_finding(@TempDir Path dir) throws Exception {
        project(dir, "");

        Run r = audit(dir, "--agent");

        assertThat(r.exit()).isEqualTo(Exit.FAILURE);
        assertThat(r.out() + r.err())
                .as("stdout:%n%s%nstderr:%n%s", r.out(), r.err())
                .doesNotContain("OK audit")
                .contains("GHSA-test-1");
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
        // A day long past in every zone; the day boundary itself is the engine's test, on injected dates.
        LocalDate lapsed = LocalDate.of(2020, 1, 31);
        project(dir, """

                [audit]
                ignore = [{ id = "GHSA-test-1", reason = "was waiting on upstream", until = "%s" }]
                """.formatted(lapsed));

        Run r = audit(dir);

        assertThat(r.exit()).as("stdout:%n%s%nstderr:%n%s", r.out(), r.err()).isEqualTo(Exit.FAILURE);
        String line = r.findingLine();
        assertThat(Jsonl.bool(line, "ignored", true)).isFalse();
        assertThat(Jsonl.bool(line, "ignoreExpired", false)).isTrue();
        assertThat(Jsonl.str(line, "until")).isEqualTo(lapsed.toString());
    }

    /** The OpenVEX document is written whatever the gate says, and a test-only jar is not in the product. */
    @Test
    void vex_says_a_test_only_jar_is_not_present_and_a_shipped_one_is_under_investigation(@TempDir Path dir)
            throws Exception {
        project(dir, "", "test");
        Run tested = audit(dir, "--output", "json", "--vex", "tested.vex.json");
        project(dir, "", "main");
        Run shipped = audit(dir, "--output", "json", "--vex", "shipped.vex.json");

        assertThat(tested.exit()).as("stderr:%n%s", tested.err()).isEqualTo(Exit.FAILURE);
        String notPresent = Jsonl.objectArray(Files.readString(dir.resolve("tested.vex.json")), "statements")
                .getFirst();
        assertThat(Jsonl.str(Objects.requireNonNull(Jsonl.nested(notPresent, "vulnerability")), "name"))
                .isEqualTo("GHSA-test-1");
        assertThat(Jsonl.str(Jsonl.objectArray(notPresent, "products").getFirst(), "@id"))
                .isEqualTo("pkg:maven/com.foo/app@0.1.0");
        assertThat(Jsonl.str(notPresent, "status")).isEqualTo("not_affected");
        assertThat(Jsonl.str(notPresent, "justification")).isEqualTo("component_not_present");

        assertThat(shipped.exit()).as("stderr:%n%s", shipped.err()).isEqualTo(Exit.FAILURE);
        String present = Jsonl.objectArray(Files.readString(dir.resolve("shipped.vex.json")), "statements")
                .getFirst();
        assertThat(Jsonl.str(present, "status")).isEqualTo("under_investigation");
    }

    private static void project(Path dir, String auditTable) throws IOException {
        project(dir, auditTable, "main");
    }

    /** A manifest plus a lock stamped fresh against it, so the invisible freshen leaves the lock alone. */
    private static void project(Path dir, String auditTable, String scope) throws IOException {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.foo"
                name = "app"
                version = "0.1.0"
                java = 25
                """ + auditTable);
        CommittedLocks.write(dir, """
                [jdk]
                suggested-vendor = "temurin"
                suggested-version = "25.0.3"

                [[artifact]]
                name     = "com.foo:leaf"
                version  = "1.0"
                source   = "central+https://repo.maven.apache.org/maven2/"
                checksum = "sha256:d65226949713c4c61a784f41c51167e7b0316f93764398ebba9e4336b3d954c2"
                scopes   = ["%s"]
                """.formatted(scope));
    }

    private Run audit(Path dir) {
        return audit(dir, "--output", "json");
    }

    private Run audit(Path dir, String... mode) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream origOut = System.out;
        PrintStream origErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        int exit;
        try {
            List<String> args = new ArrayList<>(List.of("audit", "-C", dir.toString(), "--severity", "HIGH"));
            args.addAll(List.of(mode));
            args.addAll(List.of("--osv-batch-url", base + "/querybatch", "--osv-vulns-url", base + "/vulns/"));
            exit = run(args.toArray(String[]::new));
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
