// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.rules.GuardRules;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.guard.rules.LoadResult;
import cc.jumpkick.guard.schema.Lane;
import cc.jumpkick.model.GuardsConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
