// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.rules.GuardRules;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.guard.rules.LoadResult;
import cc.jumpkick.model.GuardsConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FixtureCheckTest {

    private static ClassFacts cls(String internal) {
        return new ClassFacts(
                internal, 1, "java/lang/Object", List.of(), "X.java", List.of(), List.of(), List.of(), Set.of());
    }

    @Test
    void declared_classes_are_read_from_the_source_and_slices_follow_outermost_names() {
        assertThat(
                        FixtureCheck.declaredClasses(
                                "package a.b;\n\npublic final class Bad {\n  static class Inner {}\n}\nrecord Pair() {}\ninterface Shape {}\n"))
                .containsExactly("a.b.Bad", "a.b.Pair", "a.b.Shape");
        assertThat(FixtureCheck.declaredClasses("class Bare {}")).containsExactly("Bare");
        Map<String, ClassFacts> m = new LinkedHashMap<>();
        for (ClassFacts c : List.of(cls("a/b/Bad"), cls("a/b/Bad$Inner"), cls("a/b/Ok"), cls("c/Other")))
            m.put(c.name(), c);
        FactsIndex all = new FactsIndex(m, Map.of(), "d");
        assertThat(FixtureCheck.slice(all, Set.of("a.b.Bad")).classList())
                .extracting(ClassFacts::name)
                .containsExactly("a/b/Bad", "a/b/Bad$Inner");
        assertThat(FixtureCheck.slice(all, Set.of("a.b.Ok")).classList())
                .extracting(ClassFacts::name)
                .containsExactly("a/b/Ok");
    }

    @Test
    void the_verdict_table_is_bad_fires_ok_quiet() {
        assertThat(FixtureCheck.verdict("r", 1, 2, 1, 0)).satisfies(v -> {
            assertThat(v.ok()).isTrue();
            assertThat(v.note()).isEqualTo("Bad 2, Ok 0");
        });
        assertThat(FixtureCheck.verdict("r", 1, 0, 0, 0).outcome()).isEqualTo("Bad silent");
        assertThat(FixtureCheck.verdict("r", 1, 1, 1, 1).outcome()).isEqualTo("Ok fires");
        assertThat(FixtureCheck.verdict("r", 0, 0, 1, 0).outcome()).isEqualTo("error");
        String text = FixtureCheck.render(
                List.of(FixtureCheck.verdict("aa", 1, 1, 0, 0), FixtureCheck.verdict("bbb", 1, 0, 0, 0)),
                List.of("jk-guards.toml:3: [x] bad"));
        assertThat(text)
                .startsWith("load error  jk-guards.toml:3: [x] bad\n")
                .contains("aa    bites")
                .contains("bbb   Bad silent")
                .contains("2 fixtures, 1 not proven; 1 load error(s)");
        assertThat(FixtureCheck.render(List.of(), List.of())).startsWith("no fixtures");
    }

    @Test
    void a_case_that_is_a_directory_is_a_tree_laid_over_the_files_beside_the_cases(@TempDir Path fx, @TempDir Path work)
            throws Exception {
        Files.createDirectories(fx.resolve(".github/workflows"));
        Files.writeString(fx.resolve(".github/workflows/ci.yml"), "jobs:\n  self-host:\n    run: jk test\n");
        Files.writeString(fx.resolve("wall-baseline.toml"), "[noop.jk]\nmedian-s = 1.0\n");
        Files.createDirectories(fx.resolve("Ok-live/.jk"));
        Files.writeString(fx.resolve("Ok-live/jk.toml"), "version = \"1.4.0\"\n");
        Files.writeString(fx.resolve("Ok-live/.jk/ci-bootstrap-version"), "1.3.2\n");
        Files.createDirectories(fx.resolve("Bad-missing-verb/.github/workflows"));
        Files.writeString(fx.resolve("Bad-missing-verb/jk.toml"), "version = \"1.4.0\"\n");
        Files.writeString(
                fx.resolve("Bad-missing-verb/.github/workflows/ci.yml"), "jobs:\n  self-host:\n    run: true\n");
        // a Bad*.java inside a case directory belongs to that case's tree, not to the fixture's file form
        Files.writeString(fx.resolve("Bad-missing-verb/Bad.java"), "class Bad {}\n");

        List<FixtureCheck.Source> sources = FixtureCheck.sources(fx);
        assertThat(sources)
                .extracting(s -> s.file().getFileName().toString(), FixtureCheck.Source::bad, FixtureCheck.Source::tree)
                .containsExactly(tuple("Bad-missing-verb", true, true), tuple("Ok-live", false, true));
        assertThat(FixtureCheck.treeProblem(sources)).isNull();

        Path tree = work.resolve("tree");
        FixtureCheck.caseTree(fx, sources.get(1), tree);
        assertThat(Files.readString(tree.resolve(".github/workflows/ci.yml")))
                .as("the file beside the cases is the tree every case starts from")
                .contains("jk test");
        assertThat(tree.resolve("wall-baseline.toml")).exists();
        assertThat(tree.resolve(".jk/ci-bootstrap-version")).exists();
        assertThat(tree.resolve("Ok-live"))
                .as("case directories are not part of any tree")
                .doesNotExist();
        assertThat(tree.resolve("Bad-missing-verb")).doesNotExist();

        FixtureCheck.caseTree(fx, sources.get(0), tree);
        assertThat(Files.readString(tree.resolve(".github/workflows/ci.yml")))
                .as("the case's own file is laid over the shared one")
                .contains("run: true")
                .doesNotContain("jk test");
        assertThat(tree.resolve(".jk/ci-bootstrap-version"))
                .as("the previous case's files are gone: the tree is rebuilt per case")
                .doesNotExist();
        assertThat(tree.resolve("Bad.java")).exists();

        Files.writeString(fx.resolve("Bad-file.txt"), "x\n");
        assertThat(FixtureCheck.treeProblem(FixtureCheck.sources(fx)))
                .contains("mixes Bad/Ok files with Bad/Ok directories");
    }

    @Test
    void text_rules_are_judged_over_their_snippets_and_cases_come_from_toml_and_suites(@TempDir Path root)
            throws Exception {
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), """
                [guards.no-todo]
                kind    = "text"
                pattern = "TODO"
                instead = "a ticket"
                fixture = "guard-fixtures/no-todo"
                why     = "w"

                [guards.unfixtured]
                kind    = "text"
                pattern = "FIXME"
                instead = "a ticket"
                why     = "w"
                """);
        Path fx = Files.createDirectories(root.resolve("guard-fixtures/no-todo"));
        Files.writeString(fx.resolve("Bad.java"), "class Bad { // TODO later\n}\n");
        Files.writeString(fx.resolve("BadTwo.txt"), "TODO TODO\n");
        Files.writeString(fx.resolve("Ok.java"), "class Ok { int todo = 1; }\n");
        LoadResult load = GuardRules.load(root, GuardsConfig.ABSENT);
        assertThat(load.hasErrors()).as(load.problems().toString()).isFalse();
        List<FixtureCheck.Case> cases = FixtureCheck.cases(root, load.rules(), Map.of());
        assertThat(cases).singleElement().satisfies(c -> {
            assertThat(c.id()).isEqualTo("no-todo");
            assertThat(c.compiled()).isFalse();
            assertThat(c.module()).isEmpty();
            assertThat(c.dir()).isEqualTo(fx);
        });
        List<FixtureCheck.Source> sources = FixtureCheck.sources(fx);
        assertThat(sources)
                .extracting(s -> s.file().getFileName().toString())
                .containsExactly("Bad.java", "BadTwo.txt", "Ok.java");
        FixtureCheck.Verdict v = FixtureCheck.textVerdict(cases.get(0), sources);
        assertThat(v.ok()).as(v.note()).isTrue();
        assertThat(v.note())
                .as("the comment in Bad.java is blanked by the default view; BadTwo.txt has two")
                .isEqualTo("Bad 2, Ok 0");
        // an Ok that matches turns the verdict
        Files.writeString(fx.resolve("Ok.java"), "class Ok { int TODO = 1; }\n"); // in code, not a comment
        assertThat(FixtureCheck.textVerdict(cases.get(0), FixtureCheck.sources(fx))
                        .outcome())
                .isEqualTo("Ok fires");
    }
}
