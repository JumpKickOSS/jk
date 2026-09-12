// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.guard.eval.FixtureCheck;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Fixtures compile once per owning module, are judged per rule, and a second run is a cache hit. */
class GuardFixturesTest {

    private static void project(Path root) throws IOException {
        Files.writeString(root.resolve("jk.toml"), "group = \"t\"\nname = \"m\"\nversion = \"0.0.1\"\njdk = 25\n");
        Files.writeString(root.resolve("jk-guards.toml"), """
                [guards.no-sysprop]
                kind       = "forbid"
                signatures = ["java.lang.System#getProperty"]
                instead    = "EnvValues"
                fixture    = "guard-fixtures/no-sysprop"
                why        = "w"

                [guards.no-exit]
                kind       = "forbid"
                signatures = ["java.lang.System#exit"]
                instead    = "Exit"
                fixture    = "guard-fixtures/no-exit"
                why        = "w"

                [guards.silent]
                kind       = "forbid"
                signatures = ["java.lang.Runtime#halt"]
                instead    = "Exit"
                fixture    = "guard-fixtures/silent"
                why        = "w"

                [guards.wide]
                kind       = "forbid"
                signatures = ["java.lang.String#trim"]
                instead    = "strip"
                fixture    = "guard-fixtures/wide"
                why        = "w"
                """);
        fixture(
                root,
                "no-sysprop",
                "class Bad { String x = System.getProperty(\"os.name\"); }",
                "class Ok { String x = \"linux\"; }");
        fixture(root, "no-exit", "class Bad { void f() { System.exit(1); } }", null);
        fixture(root, "silent", "class Bad { void f() { System.exit(1); } }", null); // never calls Runtime.halt
        fixture(
                root,
                "wide",
                "class Bad { String f(String s) { return s.trim(); } }",
                "class Ok { String f(String s) { return s.trim(); } }");
    }

    private static void fixture(Path root, String id, String bad, @Nullable String ok) throws IOException {
        Path dir = Files.createDirectories(root.resolve("guard-fixtures").resolve(id));
        String pkg = "package fx." + id.replace('-', '_') + ";\n";
        Files.writeString(dir.resolve("Bad.java"), pkg + bad + "\n");
        if (ok != null) Files.writeString(dir.resolve("Ok.java"), pkg + ok + "\n");
    }

    @Test
    void one_javac_per_module_and_a_verdict_per_rule(@TempDir Path root, @TempDir Path store) throws Exception {
        project(root);
        int before = GuardFixtures.COMPILES.get();
        GuardFixtures.Result r = GuardFixtures.run(root, new Cas(store));
        assertThat(r.loadErrors()).isEmpty();
        assertThat(GuardFixtures.COMPILES.get() - before)
                .as("four rules, one owning module, one javac")
                .isEqualTo(1);
        assertThat(r.verdicts())
                .extracting(FixtureCheck.Verdict::id)
                .containsExactly("no-exit", "no-sysprop", "silent", "wide");
        assertThat(r.verdicts())
                .extracting(FixtureCheck.Verdict::outcome)
                .containsExactly("bites", "bites", "Bad silent", "Ok fires");
        assertThat(r.ok()).isFalse();
        assertThat(r.failures()).isEqualTo(2);
        assertThat(r.text())
                .contains("no-sysprop  bites")
                .contains("silent      Bad silent")
                .contains("wide        Ok fires")
                .contains("4 fixtures, 2 not proven");

        GuardFixtures.Result again = GuardFixtures.run(root, new Cas(store));
        assertThat(GuardFixtures.COMPILES.get() - before)
                .as("unchanged fixtures: no second javac")
                .isEqualTo(1);
        assertThat(again.verdicts()).hasSize(4);

        Files.writeString(
                root.resolve("guard-fixtures/silent/Bad.java"),
                "package fx.silent;\nclass Bad { void f() { Runtime.getRuntime().halt(1); } }\n");
        GuardFixtures.Result fixed = GuardFixtures.run(root, new Cas(store));
        assertThat(GuardFixtures.COMPILES.get() - before)
                .as("a changed fixture recompiles once")
                .isEqualTo(2);
        assertThat(fixed.verdicts().stream()
                        .filter(v -> v.id().equals("silent"))
                        .findFirst()
                        .orElseThrow()
                        .outcome())
                .isEqualTo("bites");
    }

    /**
     * The compile key names classpath entries by content. The owning module's classes dir keeps
     * its path across builds, so a path-keyed entry would reuse fixtures compiled against the
     * previous build's bytecode after the module changed under them.
     */
    @Test
    void a_changed_classpath_entry_recompiles_the_fixtures(@TempDir Path root) throws Exception {
        Path src = Files.createDirectories(root.resolve("fx"));
        Path ok = Files.writeString(src.resolve("Ok.java"), "class Ok {}\n");
        Path moduleClasses = Files.createDirectories(root.resolve("target/classes/main"));
        Files.writeString(moduleClasses.resolve("Dep.class"), "bytes-v1");
        Path out = root.resolve("target/jk-guards/fixtures/classes");
        int before = GuardFixtures.COMPILES.get();

        assertThat(GuardFixtures.compile(List.of(ok), List.of(moduleClasses), out))
                .isNull();
        assertThat(GuardFixtures.compile(List.of(ok), List.of(moduleClasses), out))
                .isNull();
        assertThat(GuardFixtures.COMPILES.get() - before)
                .as("same sources, same classpath bytes: one javac")
                .isEqualTo(1);

        Files.writeString(moduleClasses.resolve("Dep.class"), "bytes-v2-longer");
        assertThat(GuardFixtures.compile(List.of(ok), List.of(moduleClasses), out))
                .isNull();
        assertThat(GuardFixtures.COMPILES.get() - before)
                .as("the same path holding different bytes is a different classpath")
                .isEqualTo(2);
    }

    @Test
    void load_errors_are_all_listed_and_nothing_compiles(@TempDir Path root, @TempDir Path store) throws Exception {
        Files.writeString(root.resolve("jk.toml"), "group = \"t\"\nname = \"m\"\nversion = \"0.0.1\"\njdk = 25\n");
        Files.writeString(
                root.resolve("jk-guards.toml"),
                "[guards.a]\nkind = \"forbid\"\nwhy = \"w\"\n\n[guards.b]\nkind = \"nope\"\nwhy = \"w\"\n");
        int before = GuardFixtures.COMPILES.get();
        GuardFixtures.Result r = GuardFixtures.run(root, new Cas(store));
        assertThat(r.loadErrors()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(r.failures()).isEqualTo(r.loadErrors().size());
        assertThat(r.text()).startsWith("load error  ");
        assertThat(GuardFixtures.COMPILES.get()).isEqualTo(before);
        Files.writeString(
                root.resolve("jk-guards.toml"),
                "[guards.a]\nkind = \"text\"\npattern = \"x\"\ninstead = \"y\"\nwhy = \"w\"\n");
        assertThat(GuardFixtures.run(root, new Cas(store)).text()).startsWith("no fixtures");
    }
}
