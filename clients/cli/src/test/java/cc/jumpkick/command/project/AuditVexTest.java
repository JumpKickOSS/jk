// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.audit.AuditReport;
import cc.jumpkick.audit.AuditReport.Finding;
import cc.jumpkick.audit.AuditReport.Severity;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Scope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk audit --vex}: a finding is {@code not_affected} only when its jar is on no runtime
 * classpath, and {@code under_investigation} in every other case.
 */
class AuditVexTest {

    private static final String PRODUCT = "pkg:maven/com.foo/app@0.1.0";

    @Test
    void a_jar_only_tests_build_or_dev_use_is_not_present() {
        for (Scope scope : List.of(Scope.TEST, Scope.PROCESSOR, Scope.TEST_PROCESSOR, Scope.DEV, Scope.PLUGIN)) {
            assertThat(AuditVex.present(lock(row("com.foo:leaf", "1.0", scope)), "com.foo:leaf", "1.0"))
                    .as("%s", scope)
                    .isFalse();
        }
    }

    @Test
    void a_jar_on_a_runtime_classpath_or_supplied_by_the_platform_is_present() {
        for (Scope scope : List.of(Scope.MAIN, Scope.RUNTIME, Scope.EXPORT, Scope.PROVIDED)) {
            assertThat(AuditVex.present(lock(row("com.foo:leaf", "1.0", scope, Scope.TEST)), "com.foo:leaf", "1.0"))
                    .as("%s", scope)
                    .isTrue();
        }
    }

    /** The classifier jars of one version are one package to OSV: one shipped jar makes it present. */
    @Test
    void one_shipped_classifier_jar_makes_the_package_present() {
        Lockfile lock = lock(
                row("com.foo:leaf:jar:linux-x86_64", "1.0", Scope.TEST), row("com.foo:leaf:jar:", "1.0", Scope.MAIN));

        assertThat(AuditVex.present(lock, "com.foo:leaf", "1.0")).isTrue();
    }

    @Test
    void nothing_proves_a_jar_absent_that_the_lock_does_not_name() {
        Lockfile lock = lock(row("com.foo:leaf", "2.0", Scope.TEST));

        assertThat(AuditVex.present(lock, "com.foo:leaf", "1.0")).isTrue();
        assertThat(AuditVex.present(lock, "com.foo:other", "2.0")).isTrue();
    }

    @Test
    void the_document_states_each_finding_against_the_product() {
        Lockfile lock = lock(row("com.foo:shipped", "1.0", Scope.MAIN), row("com.foo:tested", "2.0", Scope.TEST));
        List<Finding> findings = List.of(
                new Finding("com.foo:shipped", "1.0", "GHSA-ship", "s", Severity.HIGH, null, List.of("CVE-2026-1")),
                new Finding("com.foo:tested", "2.0", "GHSA-test", "t", Severity.LOW, null));

        String doc =
                AuditVex.write("urn:uuid:0", PRODUCT, "jk 1.0", Instant.parse("2026-11-21T00:00:00Z"), findings, lock);

        assertThat(Jsonl.str(doc, "@context")).isEqualTo("https://openvex.dev/ns/v0.2.0");
        assertThat(Jsonl.str(doc, "@id")).isEqualTo("urn:uuid:0");
        assertThat(Jsonl.str(doc, "timestamp")).isEqualTo("2026-11-21T00:00:00Z");
        List<String> statements = Jsonl.objectArray(doc, "statements");
        assertThat(statements).hasSize(2);

        String shipped = statements.get(0);
        String vulnerability = Objects.requireNonNull(Jsonl.nested(shipped, "vulnerability"));
        assertThat(Jsonl.str(vulnerability, "name")).isEqualTo("GHSA-ship");
        assertThat(Jsonl.strArray(vulnerability, "aliases")).containsExactly("CVE-2026-1");
        String product = Jsonl.objectArray(shipped, "products").getFirst();
        assertThat(Jsonl.str(product, "@id")).isEqualTo(PRODUCT);
        assertThat(Jsonl.str(Jsonl.objectArray(product, "subcomponents").getFirst(), "@id"))
                .isEqualTo("pkg:maven/com.foo/shipped@1.0");
        assertThat(Jsonl.str(shipped, "status")).isEqualTo("under_investigation");
        assertThat(Jsonl.has(shipped, "justification")).isFalse();

        String tested = statements.get(1);
        assertThat(Jsonl.str(tested, "status")).isEqualTo("not_affected");
        assertThat(Jsonl.str(tested, "justification")).isEqualTo("component_not_present");
    }

    @Test
    void no_findings_is_a_document_with_no_statements() {
        String doc = AuditVex.write("urn:uuid:0", PRODUCT, "jk 1.0", Instant.EPOCH, List.of(), lock());

        assertThat(Jsonl.objectArray(doc, "statements")).isEmpty();
        assertThat(Jsonl.str(doc, "author")).isEqualTo("jk audit");
    }

    /** A member names the product by its own name and the group and version its workspace root declares. */
    @Test
    void a_workspace_member_is_the_product_with_the_roots_group_and_version(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.foo"
                version = "0.2.0"

                [workspace]
                modules = ["app"]
                """);
        Path app = Files.createDirectories(root.resolve("app"));
        Files.writeString(app.resolve("jk.toml"), "name = \"app\"\n");
        Files.writeString(root.resolve("jk-lock.toml"), """
                version = 1
                generated-by = "jk test"
                resolution-algorithm = "pubgrub-v1"

                [[artifact]]
                name     = "com.foo:leaf"
                version  = "1.0"
                source   = "central+https://repo.maven.apache.org/maven2/"
                checksum = "sha256:00"
                scopes   = ["test"]
                """);
        Path vex = root.resolve("out.vex.json");

        AuditCommand.writeVex(
                vex,
                app,
                root.resolve("jk-lock.toml"),
                new AuditReport(List.of(new Finding("com.foo:leaf", "1.0", "GHSA-1", "", Severity.HIGH, null))));

        String statement =
                Jsonl.objectArray(Files.readString(vex), "statements").getFirst();
        assertThat(Jsonl.str(Jsonl.objectArray(statement, "products").getFirst(), "@id"))
                .isEqualTo("pkg:maven/com.foo/app@0.2.0");
        assertThat(Jsonl.str(statement, "status")).isEqualTo("not_affected");
    }

    @Test
    void a_project_with_no_version_is_refused_and_nothing_is_written(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), "group = \"com.foo\"\nname = \"app\"\n");
        Files.writeString(dir.resolve("jk-lock.toml"), """
                version = 1
                generated-by = "jk test"
                resolution-algorithm = "pubgrub-v1"
                """);
        Path vex = dir.resolve("out.vex.json");

        assertThatThrownBy(
                        () -> AuditCommand.writeVex(vex, dir, dir.resolve("jk-lock.toml"), new AuditReport(List.of())))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no group, name and version");
        assertThat(vex).doesNotExist();
    }

    private static Lockfile.Artifact row(String name, String version, Scope... scopes) {
        return new Lockfile.Artifact(
                name,
                version,
                "central+https://repo.maven.apache.org/maven2/",
                "sha256:00",
                null,
                List.of(scopes),
                List.of());
    }

    private static Lockfile lock(Lockfile.Artifact... rows) {
        return new Lockfile(1, "jk test", "pubgrub-v1", List.of(rows));
    }
}
