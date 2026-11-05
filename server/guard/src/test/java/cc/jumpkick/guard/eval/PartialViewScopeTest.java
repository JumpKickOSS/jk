// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.baseline.Baseline;
import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.baseline.RuleBaseline;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.rules.GuardRules;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.guard.rules.LoadResult;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.guard.schema.Lane;
import cc.jumpkick.model.GuardsConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A scope floor, and the output lane's packaged-set check, are judged only by a lane whose view
 * covers every module they count: a build scoped to one module leaves the rest unseen, not gone.
 */
class PartialViewScopeTest {

    private static final String PUBLISHED_POMS = """
            [guards.published-poms]
            kind = "output"
            pom  = { no-unspecified = true, groups = ["com.acme"] }
            why  = "a POM with an unspecified coordinate is an artifact nobody can depend on"
            """;

    /** A workspace of {@code core} (depending on {@code api}) and {@code api}; nothing packaged yet. */
    private static List<OutputArtifacts.Module> workspace(Path root) throws IOException {
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.acme"
                name = "ws"
                version = "1.0.0"
                java = 25
                [workspace]
                modules = ["core", "api"]
                """);
        for (String m : List.of("core", "api")) {
            Files.createDirectories(root.resolve(m));
            Files.writeString(
                    root.resolve(m + "/jk.toml"),
                    "group = \"com.acme\"\nname = \"" + m + "\"\nversion = \"1.0.0\"\njava = 25\n");
        }
        List<OutputArtifacts.Module> mods = OutputArtifacts.of(root, modules(root), null);
        assertThat(mods).hasSize(2);
        return mods;
    }

    private static List<Path> modules(Path root) {
        return List.of(root.resolve("core"), root.resolve("api"));
    }

    private static LaneRun.Result output(Path root, Baseline baseline) throws IOException {
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), PUBLISHED_POMS);
        LoadResult load = GuardRules.load(root, GuardsConfig.ABSENT);
        assertThat(load.hasErrors()).as(load.problems().toString()).isFalse();
        EvalContext ctx = new EvalContext(
                        Lane.OUTPUT, root, "", null, modules(root), () -> FactsIndex.EMPTY, () -> null, List::of)
                .withRules(load.rules());
        return LaneRun.run(Lane.OUTPUT, LaneRun.rulesFor(Lane.OUTPUT, load.rules(), ""), ctx, baseline);
    }

    private static Baseline floor(long poms) {
        return Baseline.EMPTY.with("published-poms", RuleBaseline.EMPTY.withLane("", Map.of("poms", poms), List.of()));
    }

    @Test
    void an_output_view_missing_a_module_judges_neither_its_floor_nor_the_packaged_set(@TempDir Path root)
            throws IOException {
        List<OutputArtifacts.Module> mods = workspace(root);
        // `jk test -m core` packaged core alone; api is not built yet
        OutputEvaluatorTest.pom(mods.get(0).pom(), "com.acme", "core", "1.0.0", "com.acme:api:1.0.0");

        LaneRun.Result result = output(root, floor(2));

        assertThat(result.reports()).singleElement().satisfies(r -> {
            assertThat(r.outcome()).isNotEqualTo(Outcome.SCOPE_SHRUNK);
            assertThat(r.red()).as(r.note()).isFalse();
            assertThat(r.evaluation().observations())
                    .as("a dependency on a sibling this build did not reach is not a site")
                    .isEmpty();
        });
        assertThat(result.baseline().of("published-poms").population("")).containsEntry("poms", 2L);
    }

    @Test
    void an_output_view_of_every_module_judges_its_floor_and_the_packaged_set(@TempDir Path root) throws IOException {
        List<OutputArtifacts.Module> mods = workspace(root);
        // both packaged, api without a POM: the floor shrank and core depends on nothing packaged
        OutputEvaluatorTest.pom(mods.get(0).pom(), "com.acme", "core", "1.0.0", "com.acme:api:1.0.0");
        OutputEvaluatorTest.jar(mods.get(1).jar(), Map.of(), "com/acme/Api.class");

        LaneRun.Result shrunk = output(root, floor(2));
        assertThat(shrunk.reports()).singleElement().satisfies(r -> assertThat(r.outcome())
                .isEqualTo(Outcome.SCOPE_SHRUNK));

        LaneRun.Result judged = output(root, floor(1));
        assertThat(judged.reports()).singleElement().satisfies(r -> {
            assertThat(r.red()).isTrue();
            assertThat(r.evaluation().observations().stream().map(Observation::detail))
                    .anySatisfy(d -> assertThat(d).contains("com.acme:api").contains("not an artifact it packages"));
        });
    }

    @Test
    void a_workspace_view_missing_modules_does_not_call_the_scope_shrunk(@TempDir Path root) throws IOException {
        Path module = root.resolve("m");
        List<String> packages = List.of("a", "b", "c", "d", "e", "f", "g", "h");
        for (String p : packages) {
            Path f = module.resolve("src/main/java/p/" + p + "/" + p.toUpperCase(Locale.ROOT) + ".java");
            Files.createDirectories(f.getParent());
            Files.writeString(f, "");
        }
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), """
                [guards.r]
                kind           = "cycles"
                matching       = "p.(**)"
                across-modules = true
                baseline       = true
                why            = "w"
                """);
        LoadResult load = GuardRules.load(root, GuardsConfig.ABSENT);
        assertThat(load.hasErrors()).as(load.problems().toString()).isFalse();
        Rule rule = load.rules().rule("r").orElseThrow();
        assertThat(Evaluators.laneOf(rule)).isEqualTo(Lane.WORKSPACE);
        // three of the eight packages have no class in the view: their module is not indexed yet
        Map<String, ClassFacts> classes = new LinkedHashMap<>();
        for (String p : packages.subList(3, packages.size())) {
            String simple = p.toUpperCase(Locale.ROOT);
            String name = "p/" + p + "/" + simple;
            classes.put(
                    name,
                    new ClassFacts(
                            name,
                            1,
                            "java/lang/Object",
                            List.of(),
                            simple + ".java",
                            List.of(),
                            List.of(),
                            List.of(),
                            Set.of()));
        }
        FactsIndex partial = new FactsIndex(classes, Map.of(), "");
        EvalContext ctx =
                new EvalContext(Lane.WORKSPACE, root, "", null, List.of(module), () -> partial, () -> null, List::of);
        Baseline banked = Baseline.EMPTY.with("r", RuleBaseline.EMPTY.withLane("", Map.of("slices", 8L), List.of()));

        LaneRun.Result result = LaneRun.run(Lane.WORKSPACE, List.of(rule), ctx, banked);

        assertThat(result.reports()).singleElement().satisfies(r -> {
            assertThat(r.outcome()).isNotEqualTo(Outcome.SCOPE_SHRUNK);
            assertThat(r.red()).as(r.note()).isFalse();
        });
        assertThat(result.baseline().of("r").population("")).containsEntry("slices", 8L);
    }
}
