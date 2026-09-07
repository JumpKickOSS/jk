// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.guard.extract.FactsExtractor;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.rules.GuardRules;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.guard.rules.LoadResult;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.guard.schema.Lane;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.model.GuardsConfig;
import cc.jumpkick.model.Scope;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tomlj.Toml;

class ExtractorsTest {

    private static final String RULES = """
            [guards.a]
            kind = "text"
            pattern = "zzz"
            hit = "zzz"
            instead = "i"
            why = "first why"
            [guards.b]
            kind = "forbid"
            signatures = ["java.lang.Runtime"]
            owner = "x.Owner"
            instead = "i"
            why = "second why"
            """;

    static FactsIndex sampleFacts() throws IOException {
        try (InputStream in = Objects.requireNonNull(SampleTier.class.getResourceAsStream("SampleTier.class"))) {
            ClassFacts c = FactsExtractor.extract(in.readAllBytes());
            return new FactsIndex(Map.of(c.name(), c), Map.of(), "sample");
        }
    }

    static EvalContext ctx(Path root, FactsIndex facts) {
        Files.exists(root);
        LoadResult load = GuardRules.load(root, GuardsConfig.ABSENT);
        return new EvalContext(Lane.TREE, root, "", null, List.of(), () -> facts, () -> null, List::of)
                .withRules(load.rules());
    }

    static Rule anyRule(EvalContext ctx) {
        return Objects.requireNonNull(ctx.rules().rules().values().iterator().next());
    }

    static Extraction extract(Path root, String spec) throws Exception {
        return extract(root, spec, FactsIndex.EMPTY);
    }

    static Extraction extract(Path root, String spec, FactsIndex facts) throws Exception {
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), RULES);
        EvalContext ctx = ctx(root, facts);
        Extractors.Spec s = Objects.requireNonNull(
                Extractors.parse(Toml.parse("x = " + spec).getTable("x")), spec);
        return Extractors.extract(s, anyRule(ctx), ctx);
    }

    @Test
    void workspace_modules_and_manifest_deps_read_the_manifests(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("jk.toml"), """
                group = "g"
                name = "root"
                version = "1"
                [workspace]
                modules = ["mod-a", "mod-b"]
                [dependencies]
                core = { group = "org.x", version = "1.2" }
                [test-dependencies]
                junit-jupiter = { group = "org.junit.jupiter", version = "5.10" }
                """);
        assertThat(extract(root, "{ workspace-modules = \"jk.toml\" }").keys()).containsExactly("mod-a", "mod-b");
        Extraction main = extract(root, "{ manifest-deps = { module = \"\", scope = \"main\" } }");
        assertThat(main.keys()).containsExactly("org.x:core");
        assertThat(main.rows().get(0).column("version")).isEqualTo("1.2");
        assertThat(extract(root, "{ manifest-deps = { module = \"\", scope = \"all\" } }")
                        .keys())
                .containsExactly("org.junit.jupiter:junit-jupiter", "org.x:core");
    }

    @Test
    void lock_artifacts_reads_the_lockfile(@TempDir Path root) throws Exception {
        Lockfile lock = new Lockfile(
                Lockfile.CURRENT_VERSION,
                "test",
                Lockfile.RESOLUTION_ALGORITHM,
                null,
                null,
                List.of(
                        new Lockfile.Artifact(
                                "org.x:core", "1.2", "central", null, null, List.of(Scope.MAIN), List.of(), null),
                        new Lockfile.Artifact(
                                "org.y:util", "3.0", "central", null, null, List.of(Scope.MAIN), List.of(), null)),
                List.of(),
                List.of());
        LockfileWriter.write(lock, root.resolve("jk-lock.toml"));
        Extraction x = extract(root, "{ lock-artifacts = \"jk-lock.toml\" }");
        assertThat(x.keys()).containsExactly("org.x:core", "org.y:util");
        assertThat(x.rows().get(0).column("version")).isEqualTo("1.2");
    }

    @Test
    void structured_text_extractors_read_keys(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("libs.versions.toml"), """
                [versions]
                junit = "5.10"
                [libraries]
                junit = { module = "org.junit:junit", version.ref = "junit" }
                assertj = "org.assertj:assertj-core:3.25"
                """);
        assertThat(extract(root, "{ catalog-aliases = \"libs.versions.toml\" }").keys())
                .containsExactly("assertj", "junit");
        assertThat(extract(root, "{ toml-keys = { file = \"libs.versions.toml\", table = \"versions\" } }")
                        .keys())
                .containsExactly("junit");
        Files.writeString(root.resolve("ci.yml"), """
                # a workflow
                name: ci
                on:
                  push:
                    branches: [main]
                jobs:
                  build:
                    runs-on: ubuntu-latest
                  self-host:
                    runs-on: ubuntu-latest
                """);
        Extraction jobs = extract(root, "{ yaml-keys = { file = \"ci.yml\", path = \"jobs\" } }");
        assertThat(jobs.keys()).containsExactly("build", "self-host");
        assertThat(extract(root, "{ yaml-keys = \"ci.yml\" }").keys()).containsExactly("jobs", "name", "on");
        Files.writeString(root.resolve("app.properties"), "a=1\nb.c=2\n# comment\n");
        assertThat(extract(root, "{ properties-keys = \"app.properties\" }").keys())
                .containsExactly("a", "b.c");
        Files.writeString(
                root.resolve("package.json"), "{\"name\":\"x\",\"scripts\":{\"build\":\"tsc\",\"test\":\"jest\"}}");
        assertThat(extract(root, "{ json-keys = { file = \"package.json\", path = \"scripts\" } }")
                        .keys())
                .containsExactly("build", "test");
    }

    @Test
    void regex_reads_code_with_comments_blanked_and_gradle_includes_map_dirs(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("build.gradle.kts"), """
                // gradleVersion = "0.0"
                tasks.wrapper { gradleVersion = "8.10" }
                """);
        Extraction v = extract(
                root, "{ regex = { file = \"build.gradle.kts\", pattern = 'gradleVersion\\s*=\\s*\"([^\"]+)\"' } }");
        assertThat(v.keys()).containsExactly("8.10");
        assertThat(v.rows().get(0).column("line")).isEqualTo("2");
        Files.writeString(root.resolve("settings.gradle.kts"), """
                include(":host", ":cli")
                include(":plugins:quarkus")
                project(":host").projectDir = file("shared/host")
                project(":cli").projectDir = file("clients/cli")
                """);
        Extraction inc = extract(root, "{ gradle-includes = \"settings.gradle.kts\" }");
        assertThat(inc.keys()).containsExactly("clients/cli", "plugins/quarkus", "shared/host");
    }

    @Test
    void facts_extractors_read_enum_constants_and_static_finals(@TempDir Path root) throws Exception {
        FactsIndex facts = sampleFacts();
        assertThat(extract(root, "{ enum-constants = \"cc.jumpkick.guard.eval.SampleTier\" }", facts)
                        .keys())
                .containsExactly("INTEGRATION", "SLOW", "UNIT");
        Extraction finals = extract(root, "{ static-finals = \"cc.jumpkick.guard.eval.SampleTier\" }", facts);
        assertThat(finals.keys()).containsExactly("FLOOR", "KIND");
        assertThat(finals.rows().stream()
                        .filter(r -> r.key().equals("KIND"))
                        .findFirst()
                        .orElseThrow()
                        .column("value"))
                .isEqualTo("tier");
        assertThatThrownBy(() -> extract(root, "{ enum-constants = \"no.Such\" }", facts))
                .isInstanceOf(ExtractorException.class)
                .hasMessageContaining("no.Such");
    }

    @Test
    void guard_ids_lists_the_rules_and_markdown_extractors_read_tables_and_links(@TempDir Path root) throws Exception {
        Extraction ids = extract(root, "{ guard-ids = true }");
        assertThat(ids.keys()).containsExactly("a", "b");
        assertThat(ids.rows().get(0).column("kind")).isEqualTo("text");
        assertThat(ids.rows().get(0).column("why")).isEqualTo("first why");
        Files.writeString(root.resolve("doc.md"), """
                # Doc

                See [the guide](guide.md) and [api](api/index.md "API").

                | id | kind | why |
                |---|---|---|
                | a | text | pipes \\| escaped |
                | b | forbid | second |

                after
                """);
        Extraction table = extract(root, "{ markdown-table = { file = \"doc.md\", column = \"id\" } }");
        assertThat(table.keys()).containsExactly("a", "b");
        assertThat(table.rows().get(0).column("why")).isEqualTo("pipes | escaped");
        assertThat(extract(root, "{ markdown-links = \"doc.md\" }").keys()).containsExactly("api/index.md", "guide.md");
    }

    @Test
    void an_unknown_extractor_is_a_load_error_listing_the_set(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), """
                [guards.p]
                kind = "parity"
                left = { sparkle = "x" }
                right = { toml-keys = { file = "a.toml" } }
                why = "w"
                [guards.g]
                kind = "generated"
                source = { guard-ids = true }
                template = { ribbon = true }
                into = "doc.md"
                why = "w"
                [guards.r]
                kind = "parity"
                left = { regex = { file = "a", pattern = "(" } }
                right = { regex = { file = "a", pattern = "(x)", group = 4 } }
                why = "w"
                """);
        LoadResult load = GuardRules.load(root, GuardsConfig.ABSENT);
        assertThat(load.hasErrors()).isTrue();
        String all = load.problems().toString();
        assertThat(all)
                .contains("unknown extractor `sparkle`")
                .contains("workspace-modules")
                .contains("markdown-links");
        assertThat(all).contains("unknown template `ribbon`").contains("arrow-chain");
        assertThat(all).contains("does not compile").contains("group 4 is not in the pattern");
    }
}
