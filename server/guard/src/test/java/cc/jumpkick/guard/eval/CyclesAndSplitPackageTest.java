// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.extract.FactsIndexing;
import cc.jumpkick.guard.extract.WorkspaceFacts;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.FactsFormat;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.rules.GuardRules;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.guard.rules.LoadResult;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.guard.schema.Lane;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.GuardsConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CyclesAndSplitPackageTest {

    private static ClassFacts cls(String internal, String... refs) {
        return new ClassFacts(
                internal, 1, "java/lang/Object", List.of(), "A.java", List.of(), List.of(), List.of(), Set.of(refs));
    }

    private static FactsIndex index(ClassFacts... classes) {
        Map<String, ClassFacts> m = new LinkedHashMap<>();
        for (ClassFacts c : classes) m.put(c.name(), c);
        return new FactsIndex(m, Map.of(), "");
    }

    private static Rule rule(Path dir, String kind, String body) throws IOException {
        Files.writeString(
                dir.resolve(GuardsPresence.RULES_FILE), "[guards.r]\nkind = \"" + kind + "\"\nwhy = \"w\"\n" + body);
        LoadResult load = GuardRules.load(dir, GuardsConfig.ABSENT);
        assertThat(load.hasErrors()).as(load.problems().toString()).isFalse();
        return load.rules().rule("r").orElseThrow();
    }

    private static Evaluation module(Path dir, Rule rule, FactsIndex idx) throws Exception {
        EvalContext ctx = new EvalContext(
                Lane.MODULE, dir, "m", dir.resolve("m"), List.of(dir.resolve("m")), () -> idx, () -> null, List::of);
        return Evaluators.forKind(rule.kind()).evaluate(rule, ctx);
    }

    @Test
    void a_package_cycle_is_one_metric_per_component_naming_members_not_cycles(@TempDir Path dir) throws Exception {
        // a → b → c → a, plus d → a (d is not in the cycle), plus e alone
        FactsIndex idx = index(
                cls("p/a/A", "p/b/B"),
                cls("p/b/B", "p/c/C"),
                cls("p/c/C", "p/a/A"),
                cls("p/d/D", "p/a/A"),
                cls("p/e/E"));
        Evaluation e = module(dir, rule(dir, "cycles", "matching = \"p.(**)\"\n"), idx);
        assertThat(e.outcome()).isEqualTo(Outcome.VIOLATIONS);
        assertThat(e.observations()).singleElement().satisfies(o -> {
            assertThat(o.isMetric()).isTrue();
            assertThat(o.key()).isEqualTo("m:a");
            assertThat(o.value()).isEqualTo(3.0);
            assertThat(o.detail()).contains("3 slices in one cycle in m: a, b, c");
        });
        assertThat(e.population()).containsEntry("edges", 4L).containsEntry("slices", 5L);
        Evaluation acyclic =
                module(dir, rule(dir, "cycles", "matching = \"p.(**)\"\n"), index(cls("p/a/A", "p/b/B"), cls("p/b/B")));
        assertThat(acyclic.outcome()).isEqualTo(Outcome.CLEAN);
        Evaluation unrelated =
                module(dir, rule(dir, "cycles", "matching = \"p.(**)\"\n"), index(cls("p/a/A"), cls("p/b/B")));
        assertThat(unrelated.outcome())
                .as("two packages that never meet have no cycle")
                .isEqualTo(Outcome.CLEAN);
        Evaluation blind = module(dir, rule(dir, "cycles", "matching = \"p.(**)\"\n"), index(cls("q/z/Z")));
        assertThat(blind.outcome()).as("no slice matched the pattern").isEqualTo(Outcome.BLIND);
    }

    @Test
    void slices_collapse_subpackages_by_the_captured_segment(@TempDir Path dir) throws Exception {
        // features.x.api ↔ features.y.impl are two slices (x, y) in a cycle; x's own subpackages are one slice
        FactsIndex idx = index(
                cls("f/x/api/A", "f/y/impl/B"), cls("f/x/impl/A2", "f/x/api/A"), cls("f/y/impl/B", "f/x/impl/A2"));
        Evaluation e = module(dir, rule(dir, "cycles", "matching = \"f.(*)..\"\n"), idx);
        assertThat(e.observations()).singleElement().satisfies(o -> {
            assertThat(o.key()).isEqualTo("m:x");
            assertThat(o.value()).isEqualTo(2.0);
            assertThat(o.detail()).contains("x, y");
        });
        assertThat(CyclesEvaluator.sliceName(
                        CyclesEvaluator.slicePattern("com.acme.features.(*).."), "com.acme.features.pay.api"))
                .isEqualTo("pay");
        assertThat(CyclesEvaluator.sliceName(CyclesEvaluator.slicePattern("com.acme.features.(*).."), "com.acme.core"))
                .isNull();
        assertThat(CyclesEvaluator.sliceName(CyclesEvaluator.slicePattern("..(*).."), "a.b.c"))
                .isEqualTo("a");
    }

    @Test
    void module_cycles_read_the_manifests_and_split_packages_read_every_index(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("ws"));
        Files.writeString(
                root.resolve("jk.toml"),
                "group = \"t\"\nname = \"ws\"\nversion = \"0.0.1\"\njdk = 25\n\n[workspace]\nmodules = [\"a\", \"b\", \"c\"]\n");
        for (String[] m : new String[][] {{"a", "b"}, {"b", "a"}, {"c", "a"}}) {
            Files.createDirectories(root.resolve(m[0]));
            Files.writeString(
                    root.resolve(m[0]).resolve("jk.toml"),
                    "group = \"t\"\nname = \"" + m[0] + "\"\nversion = \"0.0.1\"\njdk = 25\n\n[dependencies]\n" + m[1]
                            + " = { group = \"t\", name = \"" + m[1] + "\", version = \"0.0.1\" }\n");
        }
        List<Path> modules = List.of(root.resolve("a"), root.resolve("b"), root.resolve("c"));
        Rule over = rule(root, "cycles", "over = \"modules\"\n");
        assertThat(Evaluators.laneOf(over)).isEqualTo(Lane.MODEL);
        EvalContext model =
                new EvalContext(Lane.MODEL, root, "", null, modules, () -> FactsIndex.EMPTY, () -> null, List::of);
        Evaluation cycles = Evaluators.forKind(over.kind()).evaluate(over, model);
        assertThat(cycles.observations()).singleElement().satisfies(o -> {
            assertThat(o.key()).isEqualTo("workspace:a");
            assertThat(o.value()).isEqualTo(2.0);
            assertThat(o.detail()).contains("a, b");
        });

        // a and b both compile p.shared; c owns p.c alone
        for (String[] m : new String[][] {{"a", "p/shared/A"}, {"b", "p/shared/B"}, {"c", "p/c/C"}}) {
            Path idx = FactsIndexing.indexPath(BuildLayout.moduleTargetDir(root, root.resolve(m[0])), "main");
            Files.createDirectories(idx.getParent());
            FactsFormat.write(idx, index(cls(m[1])));
        }
        Rule split = rule(root, "split-package", "");
        assertThat(Evaluators.laneOf(split)).isEqualTo(Lane.WORKSPACE);
        Supplier<FactsIndex> merged = EvalContext.lazy(() -> WorkspaceFacts.merged(root, modules));
        EvalContext ws = new EvalContext(Lane.WORKSPACE, root, "", null, modules, merged, () -> null, List::of);
        Evaluation e = Evaluators.forKind(split.kind()).evaluate(split, ws);
        assertThat(e.observations()).extracting(Observation::key).containsExactly("p.shared | a + b");
        assertThat(e.population()).containsEntry("packages", 2L);
        Evaluation allowed = Evaluators.forKind(split.kind())
                .evaluate(
                        rule(
                                root,
                                "split-package",
                                "[[guards.r.allow]]\nin = \"p.shared\"\nreason = \"two halves of one tier boundary\"\n"),
                        ws);
        assertThat(allowed.outcome()).isEqualTo(Outcome.CLEAN);
        Evaluation stale = Evaluators.forKind(split.kind())
                .evaluate(rule(root, "split-package", "[[guards.r.allow]]\nin = \"p.c\"\nreason = \"gone\"\n"), ws);
        assertThat(stale.outcome()).isEqualTo(Outcome.STALE_ALLOW);
    }
}
