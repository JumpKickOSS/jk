// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.rules.GuardRules;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.guard.rules.LoadResult;
import cc.jumpkick.guard.schema.Lane;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.GuardsConfig;
import cc.jumpkick.model.Scope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolchainEvaluatorTest {

    private static final String SHA = "0".repeat(64);

    private static void workspace(Path root) throws IOException {
        Files.writeString(root.resolve(ManifestPaths.MANIFEST), """
                group = "acme"
                name = "ws"
                version = "1.0.0"
                jdk = 25
                java = 17
                kotlin = "1.9.20"

                [workspace]
                modules = ["app", "lib"]

                [repositories]
                corp = { url = "https://nexus.example/repository/maven-releases/" }

                [plugins]
                fmt = { group = "acme.tools", name = "fmt", version = "latest", sha256 = "%s" }
                lint = { group = "acme.tools", name = "lint", version = "2.1.0", sha256 = "%s" }
                """.formatted(SHA, SHA));
        Files.createDirectories(root.resolve("app"));
        Files.writeString(root.resolve("app/" + ManifestPaths.MANIFEST), """
                name = "app"
                group = "acme"
                version = "1.0.0"
                java = 17
                """);
        Files.createDirectories(root.resolve("lib"));
        Files.writeString(root.resolve("lib/" + ManifestPaths.MANIFEST), """
                name = "lib"
                group = "acme"
                version = "1.0.0"
                java = 21
                """);
        Lockfile lock = new Lockfile(
                Lockfile.CURRENT_VERSION,
                "test",
                Lockfile.RESOLUTION_ALGORITHM,
                null,
                null,
                null,
                null,
                List.of(
                        artifact("junit:junit:jar:", "4.13.2", "central+https://repo.maven.apache.org/maven2/"),
                        artifact("acme:internal:jar:", "1.0", "corp+https://nexus.example/repository/maven-releases/"),
                        artifact("acme:leaked:jar:", "3.0", "home-mirror+https://mirror.example/m2/")),
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null,
                null,
                null);
        LockfileWriter.write(lock, root.resolve(ManifestPaths.LOCK));
    }

    private static Lockfile.Artifact artifact(String name, String version, String source) {
        return new Lockfile.Artifact(name, version, source, null, null, List.of(Scope.MAIN), List.of(), null);
    }

    private static Map<String, Evaluation> run(Path root, String rules) throws Exception {
        workspace(root);
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), rules);
        LoadResult load = GuardRules.load(root, GuardsConfig.ABSENT);
        assertThat(load.hasErrors()).as(load.problems().toString()).isFalse();
        EvalContext ctx = new EvalContext(
                Lane.MODEL,
                root,
                "",
                null,
                List.of(root.resolve("app"), root.resolve("lib")),
                () -> FactsIndex.EMPTY,
                () -> null,
                List::of);
        return LaneRun.evaluate(LaneRun.rulesFor(Lane.MODEL, load.rules(), ""), ctx);
    }

    private static Evaluation ev(Map<String, Evaluation> r, String id) {
        return Objects.requireNonNull(r.get(id), id);
    }

    @Test
    void java_floor_sees_the_root_and_every_member_override(@TempDir Path root) throws Exception {
        Evaluation e = ev(run(root, """
                [guards.floor]
                kind = "toolchain"
                java = ">=21"
                why  = "one JDK floor"
                """), "floor");
        assertThat(e.outcome()).as(e.note()).isEqualTo(Outcome.VIOLATIONS);
        assertThat(e.observations()).extracting(Observation::key).containsExactlyInAnyOrder("java root", "java app");
        assertThat(e.observations()).extracting(Observation::file).containsExactlyInAnyOrder("jk.toml", "app/jk.toml");
        assertThat(e.observations().get(0).detail()).contains("builds for Java").contains("outside >=21");
        assertThat(e.population()).containsEntry("manifests", 3L);
        Evaluation ok = ev(run(root, "[guards.floor]\nkind = \"toolchain\"\njava = \">=17\"\nwhy = \"w\"\n"), "floor");
        assertThat(ok.outcome()).isEqualTo(Outcome.CLEAN);
        Evaluation kotlin = ev(run(root, "[guards.k]\nkind = \"toolchain\"\nkotlin = \">=2.0\"\nwhy = \"w\"\n"), "k");
        assertThat(kotlin.observations()).extracting(Observation::key).containsExactly("kotlin root");
        assertThat(kotlin.observations().get(0).detail()).contains("Kotlin 1.9.20");
    }

    @Test
    void pinned_plugins_reject_a_floating_selector_only(@TempDir Path root) throws Exception {
        Evaluation e = ev(run(root, """
                [guards.pins]
                kind    = "toolchain"
                plugins = { pinned = true }
                why     = "a plugin moves the build; a release was tested with one version"
                """), "pins");
        assertThat(e.outcome()).as(e.note()).isEqualTo(Outcome.VIOLATIONS);
        assertThat(e.observations()).extracting(Observation::key).containsExactly("plugin:acme.tools:fmt");
        assertThat(e.observations().get(0).detail()).contains("\"latest\"").contains("floats");
        assertThat(e.population()).containsEntry("plugins", 2L);
    }

    @Test
    void repositories_only_names_the_manifest_that_declares_and_the_lock_that_leaks(@TempDir Path root)
            throws Exception {
        Evaluation e = ev(run(root, """
                [guards.repos]
                kind         = "toolchain"
                repositories = { only = ["central"] }
                why          = "no artifact from an unvetted repository"
                """), "repos");
        assertThat(e.observations())
                .extracting(Observation::key)
                .containsExactlyInAnyOrder("repository:corp", "repository:home-mirror");
        Observation corp = e.observations().stream()
                .filter(o -> o.key().equals("repository:corp"))
                .findFirst()
                .orElseThrow();
        assertThat(corp.file()).isEqualTo("jk.toml");
        assertThat(corp.detail()).contains("root declares repository `corp`").contains("nexus.example");
        Observation leak = e.observations().stream()
                .filter(o -> o.key().equals("repository:home-mirror"))
                .findFirst()
                .orElseThrow();
        assertThat(leak.file()).isEqualTo("jk-lock.toml");
        assertThat(leak.detail())
                .contains("acme:leaked@3.0")
                .contains("no manifest declares")
                .contains("config.toml");
        // the declared corp repository, once allowed, also covers its lock sources
        Evaluation allowed = ev(run(root, """
                [guards.repos]
                kind         = "toolchain"
                repositories = { only = ["central", "corp"] }
                why          = "w"
                """), "repos");
        assertThat(allowed.observations()).extracting(Observation::key).containsExactly("repository:home-mirror");
    }

    @Test
    void the_documented_rule_loads_and_a_bad_range_is_a_rule_error(@TempDir Path root) throws Exception {
        Evaluation e = ev(run(root, """
                [guards.toolchain]
                kind         = "toolchain"
                java         = ">=21"
                repositories = { only = ["central", "corp-nexus"] }
                why          = "one JDK floor; no artifact from an unvetted repository"
                """), "toolchain");
        assertThat(e.outcome()).isEqualTo(Outcome.VIOLATIONS);
        assertThat(e.observations())
                .extracting(Observation::key)
                .containsExactlyInAnyOrder("java root", "java app", "repository:corp", "repository:home-mirror");
        Evaluation bad = ev(run(root, "[guards.t]\nkind = \"toolchain\"\njava = \">=\"\nwhy = \"w\"\n"), "t");
        assertThat(bad.outcome()).isEqualTo(Outcome.SCANNER_FAILED);
        assertThat(bad.note()).contains("not a version range");
    }

    @Test
    void an_edit_that_adds_a_disallowed_repository_is_refused_before_it_is_written(@TempDir Path root)
            throws Exception {
        workspace(root);
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), """
                [guards.repos]
                kind         = "toolchain"
                repositories = { only = ["central", "corp"] }
                why          = "no artifact from an unvetted repository"
                """);
        String current = Files.readString(root.resolve("lib/" + ManifestPaths.MANIFEST));
        assertThat(MutationCheck.check(root.resolve("lib/" + ManifestPaths.MANIFEST), current))
                .isNull();
        String proposed = current + "\n[repositories]\nshady = { url = \"https://shady.example/m2/\" }\n";
        String refusal = MutationCheck.check(root.resolve("lib/" + ManifestPaths.MANIFEST), proposed);
        assertThat(refusal)
                .isNotNull()
                .startsWith("GUARD repos  refused this edit")
                .contains("lib declares repository `shady`")
                .contains("jk guard explain repos");
    }

    @Test
    void a_member_outside_the_root_is_keyed_by_its_relative_spelling(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("ws"));
        workspace(root);
        Path sibling = Files.createDirectories(tmp.resolve("sib"));
        Files.writeString(sibling.resolve(ManifestPaths.MANIFEST), """
                name = "sib"
                group = "acme"
                version = "1.0.0"
                java = 17
                """);
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), """
                [guards.floor]
                kind = "toolchain"
                java = ">=21"
                why  = "w"
                """);
        LoadResult load = GuardRules.load(root, GuardsConfig.ABSENT);
        assertThat(load.hasErrors()).as(load.problems().toString()).isFalse();
        EvalContext ctx = new EvalContext(
                Lane.MODEL,
                root,
                "",
                null,
                List.of(root.resolve("app"), root.resolve("lib"), sibling),
                () -> FactsIndex.EMPTY,
                () -> null,
                List::of);
        Evaluation e = ev(LaneRun.evaluate(LaneRun.rulesFor(Lane.MODEL, load.rules(), ""), ctx), "floor");
        assertThat(e.observations())
                .extracting(Observation::key)
                .containsExactlyInAnyOrder("java root", "java app", "java ../sib");
        assertThat(e.observations()).extracting(Observation::file).contains("../sib/jk.toml");
    }
}
