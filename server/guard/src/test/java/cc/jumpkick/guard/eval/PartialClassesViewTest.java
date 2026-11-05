// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.baseline.Baseline;
import cc.jumpkick.guard.baseline.Entry;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A module lane tightens its baseline only from a classes view that covers every main source file. */
class PartialClassesViewTest {

    private static final Entry BANKED = new Entry.Metric("m:a", 3, "a ↔ b ↔ c until the transport moves");

    /** {@code internal}, compiled from its simple name's {@code .java}, referring to {@code refs}. */
    private static ClassFacts cls(String internal, String... refs) {
        String simple = internal.substring(internal.lastIndexOf('/') + 1);
        return new ClassFacts(
                internal,
                1,
                "java/lang/Object",
                List.of(),
                simple + ".java",
                List.of(),
                List.of(),
                List.of(),
                Set.of(refs));
    }

    /** {@code classes} plus one class per acyclic package. */
    private static FactsIndex index(ClassFacts... classes) {
        Map<String, ClassFacts> m = new LinkedHashMap<>();
        for (ClassFacts c : classes) m.put(c.name(), c);
        for (String p : ACYCLIC)
            m.put("p/" + p + "/" + p.toUpperCase(Locale.ROOT), cls("p/" + p + "/" + p.toUpperCase(Locale.ROOT)));
        return new FactsIndex(m, Map.of(), "");
    }

    private static Rule cycles(Path root) throws IOException {
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), """
                [guards.r]
                kind     = "cycles"
                matching = "p.(**)"
                baseline = true
                why      = "w"
                """);
        LoadResult load = GuardRules.load(root, GuardsConfig.ABSENT);
        assertThat(load.hasErrors()).as(load.problems().toString()).isFalse();
        return load.rules().rule("r").orElseThrow();
    }

    /** Packages outside the cycle, so one missing class does not shrink the scope past its floor. */
    private static final List<String> ACYCLIC = List.of("d", "e", "f", "g", "h");

    /** Module {@code m} with one source file per class plus a package-info, which compiles to nothing here. */
    private static Path module(Path root) throws IOException {
        Path m = root.resolve("m");
        List<String> files = new ArrayList<>(List.of("p/a/A.java", "p/b/B.java", "p/c/C.java"));
        for (String p : ACYCLIC) files.add("p/" + p + "/" + p.toUpperCase(Locale.ROOT) + ".java");
        files.add("p/a/package-info.java");
        for (String file : files) {
            Path f = m.resolve("src/main/java").resolve(file);
            Files.createDirectories(f.getParent());
            Files.writeString(f, "");
        }
        return m;
    }

    private static LaneRun.Result run(Path root, Path module, FactsIndex facts) throws IOException {
        Rule rule = cycles(root);
        Baseline banked = Baseline.EMPTY.with(
                "r", RuleBaseline.EMPTY.withLane("m", Map.of("slices", 8L), List.of(BANKED.in("m"))));
        EvalContext ctx =
                new EvalContext(Lane.MODULE, root, "m", module, List.of(module), () -> facts, () -> null, List::of);
        return LaneRun.run(Lane.MODULE, List.of(rule), ctx, banked);
    }

    @Test
    void a_view_missing_one_class_reports_without_dropping_the_banked_cycle(@TempDir Path root) throws IOException {
        Path module = module(root);
        // C is not in the view: the a → b → c → a cycle reads as a → b, and the banked entry as stale
        FactsIndex partial = index(cls("p/a/A", "p/b/B"), cls("p/b/B", "p/c/C"));

        LaneRun.Result result = run(root, module, partial);

        assertThat(result.reports()).singleElement().satisfies(r -> assertThat(r.red())
                .isFalse());
        assertThat(result.tightened()).isZero();
        assertThat(result.baseline().of("r").entries("m"))
                .as("the banked cycle survives a view that lacks one of its classes")
                .extracting(Entry::key)
                .containsExactly("m:a");
        assertThat(result.unseen())
                .as("the source file the view has no class for, and not the package-info that compiles to nothing")
                .containsExactly("m/src/main/java/p/c/C.java");
        assertThat(GuardMessages.withheld(Lane.MODULE, result.unseen()))
                .isEqualTo("baseline not tightened: 1 source file has no class in this lane's view"
                        + " (m/src/main/java/p/c/C.java); a build that compiles them tightens it and judges its scope");
        assertThat(result.unseenModules()).isEqualTo(1);
        String notice = GuardMessages.withheldNotice(Lane.MODULE, result.unseen(), result.unseenModules());
        assertThat(notice)
                .startsWith(
                        "guard baseline not tightened: 1 module unseen by this run (not built yet);"
                                + " a build of every module tightens it and judges its scope\n  baseline not tightened: 1 source file");
        assertThat(GuardMessages.withheldModules(notice)).isEqualTo(1);
    }

    @Test
    void a_view_that_covers_every_source_file_tightens_the_cycle_away(@TempDir Path root) throws IOException {
        Path module = module(root);
        FactsIndex full = index(cls("p/a/A", "p/b/B"), cls("p/b/B", "p/c/C"), cls("p/c/C"));

        LaneRun.Result result = run(root, module, full);

        assertThat(result.tightened()).isPositive();
        assertThat(result.baseline().of("r").entries("m")).isEmpty();
        assertThat(result.unseen()).isEmpty();
        assertThat(result.unseenModules()).isZero();
    }

    @Test
    void a_workspace_view_counts_each_unseen_file_once_under_its_innermost_module(@TempDir Path root) {
        EvalContext ctx = new EvalContext(
                Lane.WORKSPACE,
                root,
                "",
                null,
                List.of(root, root.resolve("a"), root.resolve("a/b"), root.resolve("c")),
                () -> {
                    throw new AssertionError("no facts are read to count modules");
                },
                () -> null,
                List::of);

        int modules = LaneRun.modulesOf(
                ctx,
                List.of(
                        "a/src/main/java/A.java",
                        "a/src/main/java/A2.java",
                        "a/b/src/main/java/B.java",
                        "src/main/java/Root.java"));

        assertThat(modules).as("a, a/b and the root; c has everything").isEqualTo(3);
    }
}
