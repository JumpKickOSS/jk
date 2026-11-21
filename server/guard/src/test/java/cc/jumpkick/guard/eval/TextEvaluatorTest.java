// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.baseline.Baseline;
import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.rules.GuardRules;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.guard.rules.LoadResult;
import cc.jumpkick.guard.schema.Lane;
import cc.jumpkick.model.GuardsConfig;
import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextEvaluatorTest {

    private static void tree(Path root) throws IOException {
        Path a = Files.createDirectories(root.resolve("mod-a/src/main/java/a"));
        Files.writeString(a.resolve("A.java"), """
                package a;
                /** Ripe bananas were a servlet. */
                class A {
                    String s = "bananas"; // TODO fix later
                    void f() {
                        System
                            .exit(1);
                    }
                }
                """);
        Path b = Files.createDirectories(root.resolve("mod-b/src/main/java/b"));
        Files.writeString(b.resolve("B.java"), "package b;\nclass B { int x = 1; } // TODO(bsant) later\n");
        Files.writeString(
                b.resolve("Wire.java"), "package b;\nclass Wire { String p = \"##JKX:\"; String q = \"##JKY:\"; }\n");
        Path c = Files.createDirectories(root.resolve("mod-c/src/main/java/c"));
        Files.writeString(c.resolve("Host.java"), "package c;\nclass Host { String p = \"##JKX:\"; }\n");
        Files.writeString(root.resolve("README.md"), "# Readme\n\nRipe bananas were different.\n");
        Files.createDirectories(root.resolve("mod-a/target/classes"));
        Files.writeString(root.resolve("mod-a/target/classes/Gen.java"), "bananas bananas\n");
        Files.createDirectories(root.resolve("mod-a/src/main/java/build"));
        Files.writeString(
                root.resolve("mod-a/src/main/java/build/Pkg.java"),
                "package build;\nclass Pkg { String s = \"bananas\"; }\n");
    }

    private static Evaluation ev(Map<String, Evaluation> r, String id) {
        return Objects.requireNonNull(r.get(id), id);
    }

    private static Map<String, Evaluation> run(Path root, String rules) throws Exception {
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), rules);
        LoadResult load = GuardRules.load(root, GuardsConfig.ABSENT);
        assertThat(load.hasErrors()).as(load.problems().toString()).isFalse();
        EvalContext ctx =
                new EvalContext(Lane.TREE, root, "", null, List.of(), () -> FactsIndex.EMPTY, () -> null, List::of);
        return LaneRun.evaluate(LaneRun.rulesFor(Lane.TREE, load.rules(), ""), ctx);
    }

    @Test
    void a_pattern_matching_nothing_needs_a_hit_to_prove_it_can_bite(@TempDir Path root) throws Exception {
        tree(root);
        String rules = """
                [guards.nothing]
                kind = "text"
                pattern = "zzz-never-here"
                instead = "i"
                why = "w"
                [guards.with-hit]
                kind = "text"
                pattern = "zzz-never-here"
                hit = "String s = \\"zzz-never-here\\";"
                instead = "i"
                why = "w"
                """;
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), rules);
        LoadResult load = GuardRules.load(root, GuardsConfig.ABSENT);
        assertThat(load.hasErrors()).as(load.problems().toString()).isFalse();
        EvalContext ctx =
                new EvalContext(Lane.TREE, root, "", null, List.of(), () -> FactsIndex.EMPTY, () -> null, List::of);
        LaneRun.Result r = LaneRun.run(Lane.TREE, LaneRun.rulesFor(Lane.TREE, load.rules(), ""), ctx, Baseline.EMPTY);
        Map<String, Outcome> outcomes = new HashMap<>();
        for (RuleReport rep : r.reports()) outcomes.put(rep.id(), rep.outcome());
        assertThat(outcomes).containsEntry("nothing", Outcome.NO_BITE).containsEntry("with-hit", Outcome.CLEAN);
    }

    @Test
    void views_decide_what_a_pattern_can_see(@TempDir Path root) throws Exception {
        tree(root);
        String base = "pattern = \"bananas\"\ninstead = \"state the invariant\"\nwhy = \"w\"\n";
        Map<String, Evaluation> r = run(root, """
                [guards.code]
                kind = "text"
                %s
                [guards.comments]
                kind = "text"
                blank = "code"
                %s
                [guards.everything]
                kind = "text"
                blank = "none"
                files = ["**"]
                %s
                """.formatted(base, base, base));
        // Default view: comments blanked, strings kept — the literal in A and in build/Pkg fire, the javadoc does not.
        assertThat(ev(r, "code").observations())
                .extracting(Observation::file)
                .containsExactlyInAnyOrder("mod-a/src/main/java/a/A.java", "mod-a/src/main/java/build/Pkg.java");
        assertThat(ev(r, "code").observations()).extracting(Observation::line).contains(4);
        // Comments-only view: the javadoc fires, the literal does not.
        assertThat(ev(r, "comments").observations()).singleElement().satisfies(o -> {
            assertThat(o.file()).isEqualTo("mod-a/src/main/java/a/A.java");
            assertThat(o.line()).isEqualTo(2);
        });
        // Everything, tree-wide: the README too; never target/ output.
        assertThat(ev(r, "everything").observations())
                .extracting(Observation::file)
                .contains("README.md", "mod-a/src/main/java/a/A.java")
                .doesNotContain("mod-a/target/classes/Gen.java");
        // six tree files plus the rule file itself
        assertThat(ev(r, "everything").population()).containsEntry("files", 7L);
    }

    @Test
    void squashing_defeats_a_wrapped_call_and_the_line_is_the_original(@TempDir Path root) throws Exception {
        tree(root);
        Map<String, Evaluation> r = run(root, """
                [guards.exit]
                kind = "text"
                pattern = "System\\\\.exit\\\\("
                hit = "System.exit(2)"
                instead = "Exit"
                why = "w"
                """);
        assertThat(ev(r, "exit").observations()).singleElement().satisfies(o -> {
            assertThat(o.file()).isEqualTo("mod-a/src/main/java/a/A.java");
            assertThat(o.line()).isEqualTo(6);
        });
    }

    @Test
    void hit_and_miss_are_bite_proofs(@TempDir Path root) throws Exception {
        tree(root);
        Map<String, Evaluation> r = run(root, """
                [guards.wrong-layer]
                kind = "text"
                pattern = "bananas"
                hit = "// bananas"
                instead = "i"
                why = "w"
                [guards.too-wide]
                kind = "text"
                pattern = "TODO"
                hit = "// TODO x"
                miss = "// TODO(bsant) x"
                blank = "code"
                instead = "i"
                why = "w"
                [guards.right]
                kind = "text"
                pattern = "\\\\bTODO\\\\b(?!\\\\()"
                hit = "// TODO x"
                miss = "// TODO(bsant) x"
                blank = "code"
                instead = "i"
                why = "w"
                """);
        assertThat(ev(r, "wrong-layer").outcome()).isEqualTo(Outcome.SCANNER_FAILED);
        assertThat(ev(r, "wrong-layer").note()).contains("wrong layer");
        assertThat(ev(r, "too-wide").outcome()).isEqualTo(Outcome.SCANNER_FAILED);
        assertThat(ev(r, "too-wide").note()).contains("miss matches");
        assertThat(ev(r, "right").observations())
                .singleElement()
                .extracting(Observation::file)
                .isEqualTo("mod-a/src/main/java/a/A.java");
    }

    @Test
    void count_per_match_exactly_twice(@TempDir Path root) throws Exception {
        tree(root);
        Map<String, Evaluation> r = run(root, """
                [guards.prefix-pairs]
                kind = "text"
                patterns = ["##JK[A-Z]+:"]
                count = { exactly = 2, per = "match" }
                instead = "declare once here and once there"
                why = "w"
                """);
        // ##JKX: appears twice (Wire, Host) → fine; ##JKY: once → violation.
        assertThat(ev(r, "prefix-pairs").observations()).singleElement().satisfies(o -> {
            assertThat(o.key()).isEqualTo("count | ##JKY:");
            assertThat(o.detail()).contains("expected exactly 2, found 1");
        });
    }

    @Test
    void count_per_file_judges_a_file_with_no_match(@TempDir Path root) throws Exception {
        tree(root);
        String rule = """
                [guards.prefix-everywhere]
                kind = "text"
                patterns = ["##JKX:"]
                files = ["mod-b/**", "mod-c/**"]
                count = { min = 1 }
                instead = "i"
                why = "w"
                """;
        // Wire and Host carry the prefix; B carries none, and a file with none is below the minimum.
        assertThat(ev(run(root, rule), "prefix-everywhere").observations())
                .singleElement()
                .satisfies(o -> {
                    assertThat(o.key()).isEqualTo("count | mod-b/src/main/java/b/B.java");
                    assertThat(o.file()).isEqualTo("mod-b/src/main/java/b/B.java");
                    assertThat(o.detail()).contains("expected at least 1, found 0");
                });
        Evaluation allowed = ev(
                run(root, rule + "allow = [{ in = \"mod-b/src/main/java/b/B.java\", reason = \"r\" }]\n"),
                "prefix-everywhere");
        assertThat(allowed.outcome()).as("the allow excuses B and is used").isEqualTo(Outcome.CLEAN);
        Evaluation atMost = ev(run(root, rule.replace("min = 1", "max = 1")), "prefix-everywhere");
        assertThat(atMost.outcome()).isEqualTo(Outcome.CLEAN);
    }

    /** The per-file presence rule docs/user/guards.md shows, run as written. */
    @Test
    void the_documented_header_rule_requires_the_header_on_every_files_first_line(@TempDir Path root) throws Exception {
        String page = Files.readString(RepoRoot.file(TextEvaluatorTest.class, "docs/user/guards.md"));
        int start = page.indexOf("[guards.spdx-header]");
        assertThat(start).as("guards.md shows the spdx-header rule").isPositive();
        String rule = page.substring(start, page.indexOf("```", start));
        Path a = Files.createDirectories(root.resolve("mod-a/src/main/java/a"));
        Files.writeString(a.resolve("Headed.java"), "// SPDX-License-Identifier: MIT\npackage a;\nclass Headed {}\n");
        Files.writeString(a.resolve("Late.java"), "package a;\n// SPDX-License-Identifier: MIT\nclass Late {}\n");
        Files.writeString(a.resolve("Bare.java"), "package a;\nclass Bare {}\n");
        Files.writeString(root.resolve("README.md"), "# Readme\n");

        Evaluation e = ev(run(root, rule), "spdx-header");

        assertThat(e.observations())
                .extracting(Observation::file)
                .containsExactlyInAnyOrder("mod-a/src/main/java/a/Late.java", "mod-a/src/main/java/a/Bare.java");
        assertThat(e.observations()).allSatisfy(o -> assertThat(o.detail()).contains("expected at least 1, found 0"));
    }

    @Test
    void owner_allow_and_stale_allow(@TempDir Path root) throws Exception {
        tree(root);
        Map<String, Evaluation> r = run(root, """
                [guards.owned]
                kind = "text"
                pattern = "bananas"
                owner = ["mod-a/src/main/java/a/**"]
                allow = [{ in = "mod-a/src/main/java/build/**", reason = "fixture" }]
                instead = "i"
                why = "w"
                [guards.stale]
                kind = "text"
                pattern = "bananas"
                allow = [{ in = "nowhere/**", reason = "gone" }]
                instead = "i"
                why = "w"
                [guards.owner-gone]
                kind = "text"
                pattern = "bananas"
                owner = ["mod-b/**"]
                instead = "i"
                why = "w"
                """);
        assertThat(ev(r, "owned").outcome()).isEqualTo(Outcome.CLEAN);
        assertThat(ev(r, "stale").outcome()).isEqualTo(Outcome.STALE_ALLOW);
        assertThat(ev(r, "owner-gone").outcome()).isEqualTo(Outcome.OWNER_MISSING);
    }

    @Test
    void unsupported_languages_and_the_deadline(@TempDir Path root) throws Exception {
        tree(root);
        Files.createDirectories(root.resolve("mod-a/src/main/groovy"));
        Files.writeString(root.resolve("mod-a/src/main/groovy/x.groovy"), "def x = /bananas/\n");
        Map<String, Evaluation> r = run(root, """
                [guards.groovy-blind]
                kind = "text"
                pattern = "bananas"
                instead = "i"
                why = "w"
                [guards.groovy-ok]
                kind = "text"
                pattern = "bananas"
                blank = "none"
                instead = "i"
                why = "w"
                [guards.slow]
                kind = "text"
                pattern = "(a+)+$"
                files = ["**/*.md"]
                instead = "i"
                why = "w"
                """);
        assertThat(ev(r, "groovy-blind").outcome()).isEqualTo(Outcome.UNSUPPORTED);
        assertThat(ev(r, "groovy-ok").outcome()).isEqualTo(Outcome.VIOLATIONS);
        assertThat(ev(r, "slow").outcome())
                .as("no catastrophic input here; the rule simply finds nothing")
                .isEqualTo(Outcome.CLEAN);
        assertThat(Squashed.of("int  x =\n  y;").text).isEqualTo("int x=y;");
    }

    @Test
    void a_deadline_past_the_clocks_range_has_not_passed() {
        // the deadline wraps past Long.MAX_VALUE; only a difference of nanoTime readings is meaningful
        CharSequence text = new DeadlineCharSequence("a".repeat(10_000), Long.MAX_VALUE / 1_000_000);
        for (int i = 0; i < text.length(); i++) text.charAt(i);
        assertThat(text.length()).isEqualTo(10_000);
    }

    @Test
    void a_member_file_text_rule_reads_its_member_and_not_a_sibling(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("jk.toml"), """
                group = "t"
                name = "ws"
                version = "0.0.1"
                jdk = { version = "25" }
                [workspace]
                modules = ["core", "core/plugin", "app"]
                """);
        for (String m : List.of("core", "core/plugin", "app")) {
            Path src = Files.createDirectories(root.resolve(m + "/src/main/java/p"));
            Files.writeString(
                    root.resolve(m + "/jk.toml"),
                    "group = \"t\"\nname = \"" + m.replace('/', '-')
                            + "\"\nversion = \"0.0.1\"\njdk = { version = \"25\" }\n");
            Files.writeString(src.resolve("X.java"), "package p;\nclass X { String s = \"bananas\"; }\n");
        }
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), "");
        Files.writeString(root.resolve("core/" + GuardsPresence.RULES_FILE), """
                [guards.no-bananas]
                kind = "text"
                pattern = "bananas"
                blank = "none"
                instead = "i"
                why = "w"
                """);
        LoadResult load = GuardRules.load(root, GuardsConfig.ABSENT);
        assertThat(load.hasErrors()).as(load.problems().toString()).isFalse();
        List<Path> modules = WorkspaceModules.of(root);
        EvalContext ctx =
                new EvalContext(Lane.TREE, root, "", null, modules, () -> FactsIndex.EMPTY, () -> null, List::of);
        Evaluation e = ev(LaneRun.evaluate(LaneRun.rulesFor(Lane.TREE, load.rules(), ""), ctx), "no-bananas");

        assertThat(e.observations())
                .as("the member's own file only: not the sibling's, nor the member nested in its directory")
                .extracting(Observation::file)
                .containsExactly("core/src/main/java/p/X.java");
        assertThat(e.population()).containsEntry("files", 1L);
        FileModules files = FileModules.of(root, modules);
        assertThat(files.moduleOf("core/plugin/src/main/java/p/X.java")).isEqualTo("core/plugin");
        assertThat(files.moduleOf("core/jk.toml")).isEqualTo("core");
        assertThat(files.moduleOf("corex/README.md"))
                .as("a prefix is not a directory")
                .isEmpty();
        assertThat(files.moduleOf("README.md")).isEmpty();
    }

    @Test
    void a_scoped_text_rule_finds_its_owner_file_outside_its_scope(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("jk.toml"), """
                group = "t"
                name = "ws"
                version = "0.0.1"
                jdk = { version = "25" }
                [workspace]
                modules = ["core", "app"]
                """);
        for (String m : List.of("core", "app")) {
            Files.createDirectories(root.resolve(m + "/src/main/java/p"));
            Files.writeString(
                    root.resolve(m + "/jk.toml"),
                    "group = \"t\"\nname = \"" + m + "\"\nversion = \"0.0.1\"\njdk = { version = \"25\" }\n");
        }
        Files.writeString(
                root.resolve("core/src/main/java/p/Wire.java"), "package p;\nclass Wire { String p = \"##JKX:\"; }\n");
        Files.writeString(
                root.resolve("app/src/main/java/p/Host.java"), "package p;\nclass Host { String p = \"##JKX:\"; }\n");
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), """
                [guards.wire-prefix]
                kind = "text"
                pattern = "##JKX:"
                blank = "none"
                owner = ["core/src/main/java/p/Wire.java"]
                scope = ["app"]
                instead = "Wire.PREFIX"
                why = "w"
                """);
        LoadResult load = GuardRules.load(root, GuardsConfig.ABSENT);
        assertThat(load.hasErrors()).as(load.problems().toString()).isFalse();
        EvalContext ctx = new EvalContext(
                Lane.TREE, root, "", null, WorkspaceModules.of(root), () -> FactsIndex.EMPTY, () -> null, List::of);

        Evaluation e = ev(LaneRun.evaluate(LaneRun.rulesFor(Lane.TREE, load.rules(), ""), ctx), "wire-prefix");

        assertThat(e.outcome()).as(e.note()).isEqualTo(Outcome.VIOLATIONS);
        assertThat(e.observations())
                .as("the owner in core is read, not judged; the copy in app, in scope, is the violation")
                .extracting(Observation::file)
                .containsExactly("app/src/main/java/p/Host.java");
    }
}
