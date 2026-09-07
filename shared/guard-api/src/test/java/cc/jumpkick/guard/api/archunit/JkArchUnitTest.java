// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api.archunit;

import static com.tngtech.archunit.library.Architectures.onionArchitecture;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.api.MetricSite;
import cc.jumpkick.guard.api.Site;
import cc.jumpkick.guard.api.ToolSite;
import cc.jumpkick.guard.api.Violations;
import cc.jumpkick.guard.api.runtime.GuardConfig;
import cc.jumpkick.guard.api.runtime.GuardExtension;
import cc.jumpkick.guard.api.runtime.GuardRuntime;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ArchUnit's violations become guard sites whose fingerprints ignore where in the file they are. */
class JkArchUnitTest {

    /** {@code demo.domain.model.Order#total} calls {@code demo.web.Prices#quote} at {@code line}: an adapter the domain must not see. */
    private static void classes(Path dir, int line) throws IOException {
        Path src = Files.createDirectories(dir.resolve("src"));
        Path web = Files.createDirectories(src.resolve("demo/web")).resolve("Prices.java");
        Files.writeString(web, "package demo.web;\npublic class Prices { public static int quote() { return 1; } }\n");
        Path order = Files.createDirectories(src.resolve("demo/domain/model")).resolve("Order.java");
        StringBuilder body =
                new StringBuilder("package demo.domain.model;\npublic class Order {\n  public int total() {\n");
        for (int n = 4; n < line; n++) body.append("\n"); // the call lands on line `line`
        body.append("    return demo.web.Prices.quote();\n  }\n}\n");
        Files.writeString(order, body.toString());
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        int rc = javac.run(null, null, null, "-d", dir.toString(), "-proc:none", web.toString(), order.toString());
        if (rc != 0) throw new IOException("javac failed");
    }

    private static ArchRule onion() {
        return onionArchitecture()
                .withOptionalLayers(true)
                .domainModels("..domain.model..")
                .adapter("web", "..web..");
    }

    /** Captures what a guard reports. */
    static final class Collector implements Violations {
        final List<Site> sites = new ArrayList<>();
        final List<String> details = new ArrayList<>();

        @Override
        public void add(Site site, String detail) {
            sites.add(site);
            details.add(detail);
        }

        @Override
        public void metric(MetricSite site, String detail) {}

        @Override
        public void population(long examined) {}
    }

    @Test
    void a_violation_is_a_tool_site_with_a_line_independent_fingerprint(@TempDir Path a, @TempDir Path b)
            throws Exception {
        classes(a, 12);
        classes(b, 40);
        Collector first = new Collector();
        JkArchUnit.check(onion(), new ClassFileImporter().importPaths(List.of(a)), first);
        assertThat(first.sites).isNotEmpty();
        ToolSite site = (ToolSite) first.sites.get(0);
        assertThat(site.fingerprint())
                .contains("Order.total()")
                .contains("Prices.quote()")
                .doesNotContain(":12")
                .endsWith("(Order.java)");
        assertThat(site.file()).isEqualTo("demo/domain/model/Order.java");
        assertThat(site.line()).isEqualTo(12);
        assertThat(first.details.get(0)).contains("(Order.java:12)");

        Collector second = new Collector();
        JkArchUnit.reporter(second).accept(onion().evaluate(new ClassFileImporter().importPaths(List.of(b))));
        assertThat(((ToolSite) second.sites.get(0)).fingerprint()).isEqualTo(site.fingerprint());
        assertThat(((ToolSite) second.sites.get(0)).line()).isEqualTo(40);
        assertThat(JkArchUnit.normalise("Method <a.B.c()> calls <x.Y.z()> in (B.java:77)"))
                .isEqualTo("Method <a.B.c()> calls <x.Y.z()> in (B.java)");
    }

    @Test
    void the_store_reads_the_baseline_for_the_running_guard_and_never_writes(@TempDir Path root, @TempDir Path classes)
            throws Exception {
        classes(classes, 9);
        Collector before = new Collector();
        JkArchUnit.check(onion(), new ClassFileImporter().importPaths(List.of(classes)), before);
        String fingerprint = ((ToolSite) before.sites.get(0)).fingerprint();
        Files.writeString(root.resolve("jk-guards-baseline.toml"), """
                [onion]
                population = { examined = 2 }
                [[onion.entries]]
                at     = "%s"
                reason = "the legacy adapter, until the port lands"
                """.formatted(
                        fingerprint.replace("\\", "\\\\").replace("\"", "\\\"")));
        assertThat(JkViolationStore.entriesFor(Files.readString(root.resolve("jk-guards-baseline.toml")), "onion"))
                .containsExactly(fingerprint);
        assertThat(JkViolationStore.entriesFor(Files.readString(root.resolve("jk-guards-baseline.toml")), "other"))
                .isEmpty();

        GuardRuntime.install(new GuardConfig(
                root.resolve("target/guard/report.jsonl"),
                root,
                "",
                List.of(),
                List.of(),
                null,
                List.of(),
                List.of(classes),
                List.of(),
                List.of(),
                null));
        JkArchUnit.configureFreezing();
        JavaClasses imported = new ClassFileImporter().importPaths(List.of(classes));
        Collector frozen = new Collector();
        GuardExtension.asGuard("onion", () -> JkArchUnit.check(FreezingArchRule.freeze(onion()), imported, frozen));
        assertThat(frozen.sites).as("the baselined violation is not new").isEmpty();
        Collector unknown = new Collector();
        GuardExtension.asGuard(
                "other-guard", () -> JkArchUnit.check(FreezingArchRule.freeze(onion()), imported, unknown));
        assertThat(unknown.sites)
                .as("another guard has no entries, so the violation is new")
                .hasSize(1);
        assertThat(Files.readString(root.resolve("jk-guards-baseline.toml")))
                .as("a run never writes the baseline")
                .contains("the legacy adapter");
        assertThat(GuardExtension.currentGuardId()).isNull();
    }
}
