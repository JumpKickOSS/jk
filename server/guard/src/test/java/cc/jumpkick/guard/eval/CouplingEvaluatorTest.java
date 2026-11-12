// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.facts.CallSite;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.facts.FieldFacts;
import cc.jumpkick.guard.facts.FieldRef;
import cc.jumpkick.guard.facts.MethodFacts;
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
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Opcodes;

class CouplingEvaluatorTest {

    /** A class whose one method {@code run} names each target: {@code Owner#name(desc)} or {@code Owner.field}. */
    private static ClassFacts cls(String internal, String... targets) {
        return cls(internal, Opcodes.ACC_PUBLIC, List.of(), targets);
    }

    private static ClassFacts cls(String internal, int access, List<FieldFacts> fields, String... targets) {
        List<CallSite> calls = new ArrayList<>();
        List<FieldRef> refs = new ArrayList<>();
        for (String t : targets) {
            int hash = t.indexOf('#');
            if (hash >= 0) {
                String member = t.substring(hash + 1);
                int paren = member.indexOf('(');
                calls.add(new CallSite(
                        t.substring(0, hash), member.substring(0, paren), member.substring(paren), 1, null, 1));
            } else {
                int dot = t.lastIndexOf('.');
                refs.add(new FieldRef(t.substring(0, dot), t.substring(dot + 1), "I", 1, false, 1));
            }
        }
        MethodFacts run = new MethodFacts("run", "()V", Opcodes.ACC_PUBLIC, List.of(), List.of(), calls, refs, 0, 1);
        return new ClassFacts(
                internal, access, "java/lang/Object", List.of(), "A.java", List.of(), fields, List.of(run), Set.of());
    }

    private static FactsIndex index(ClassFacts... classes) {
        Map<String, ClassFacts> m = new LinkedHashMap<>();
        for (ClassFacts c : classes) m.put(c.name(), c);
        return new FactsIndex(m, Map.of(), "");
    }

    private static Rule rule(Path dir, String body) throws IOException {
        Files.writeString(
                dir.resolve(GuardsPresence.RULES_FILE), "[guards.r]\nkind = \"coupling\"\nwhy = \"w\"\n" + body);
        LoadResult load = GuardRules.load(dir, GuardsConfig.ABSENT);
        assertThat(load.hasErrors()).as(load.problems().toString()).isFalse();
        return load.rules().rule("r").orElseThrow();
    }

    private static Evaluation module(Path dir, Rule rule, FactsIndex idx) throws Exception {
        assertThat(Evaluators.laneOf(rule)).isEqualTo(Lane.MODULE);
        EvalContext ctx = new EvalContext(
                Lane.MODULE, dir, "m", dir.resolve("m"), List.of(dir.resolve("m")), () -> idx, () -> null, List::of);
        return Evaluators.forKind(rule.kind()).evaluate(rule, ctx);
    }

    @Test
    void a_pair_that_reaches_both_ways_is_one_metric_weighted_by_distinct_members(@TempDir Path dir) throws Exception {
        FactsIndex idx = index(
                // A names two members of B, one of them through B's nested class, and one twice
                cls("p/A", "p/B#go()V", "p/B$Inner#help()V", "p/B#go()V"),
                // A's lambda class folds into A: B.field is A's third member of B
                cls("p/A$1", "p/B.count"),
                cls("p/B", "p/A#back()V"),
                // C reaches A one way only, D is outside the packages
                cls("p/C", "p/A#back()V"),
                cls("q/D", "p/A#back()V"));
        Evaluation e = module(dir, rule(dir, "measure = \"mutual\"\npackages = [\"p\"]\n"), idx);
        assertThat(e.outcome()).isEqualTo(Outcome.VIOLATIONS);
        assertThat(e.observations()).singleElement().satisfies(o -> {
            assertThat(o.key()).isEqualTo("m:p.A~p.B");
            assertThat(o.value()).isEqualTo(4.0);
            assertThat(o.detail()).contains("3 members one way, 1 the other");
        });
        assertThat(e.population()).containsEntry("classes", 3L).containsEntry("edges", 3L);

        Evaluation oneWay = module(
                dir, rule(dir, "measure = \"mutual\"\n"), index(cls("p/A", "p/B#go()V"), cls("p/B", "p/B#self()V")));
        assertThat(oneWay.outcome()).isEqualTo(Outcome.CLEAN);
        Evaluation blind = module(dir, rule(dir, "measure = \"mutual\"\npackages = [\"z..\"]\n"), idx);
        assertThat(blind.outcome()).isEqualTo(Outcome.BLIND);
    }

    @Test
    void an_allowed_class_leaves_the_graph_and_a_stale_allow_is_reported(@TempDir Path dir) throws Exception {
        FactsIndex idx = index(cls("p/A", "p/B#go()V"), cls("p/B", "p/A#back()V"));
        Evaluation allowed =
                module(dir, rule(dir, "measure = \"mutual\"\nallow = [{ in = \"p.B\", reason = \"r\" }]\n"), idx);
        assertThat(allowed.outcome()).isEqualTo(Outcome.CLEAN);
        // An allow naming a class of this module that the packages leave out exempts nothing.
        FactsIndex wider = index(cls("p/A", "p/B#go()V"), cls("p/B", "p/A#back()V"), cls("q/C"));
        Evaluation stale = module(
                dir,
                rule(dir, "measure = \"mutual\"\npackages = [\"p\"]\nallow = [{ in = \"q.C\", reason = \"r\" }]\n"),
                wider);
        assertThat(stale.outcome()).isEqualTo(Outcome.STALE_ALLOW);
        Evaluation capped = module(dir, rule(dir, "measure = \"mutual\"\ncap = 3\n"), idx);
        assertThat(capped.outcome()).isEqualTo(Outcome.SCANNER_FAILED);
    }

    @Test
    void efferent_counts_the_classes_one_class_reaches_past_its_cap(@TempDir Path dir) throws Exception {
        FactsIndex idx = index(
                cls("p/A", "p/B#x()V", "p/C#x()V", "p/D.f", "p/D#x()V"),
                cls("p/B"),
                cls("p/C"),
                cls("p/D", "p/B#x()V"));
        Evaluation e = module(dir, rule(dir, "measure = \"efferent\"\ncap = 2\n"), idx);
        assertThat(e.observations()).singleElement().satisfies(o -> {
            assertThat(o.key()).isEqualTo("m:p.A");
            assertThat(o.value()).isEqualTo(3.0);
        });
        assertThat(module(dir, rule(dir, "measure = \"efferent\"\n"), idx).outcome())
                .as("the default cap is generous")
                .isEqualTo(Outcome.CLEAN);
    }

    @Test
    void static_fan_in_counts_the_classes_leaning_on_a_static_only_holder(@TempDir Path dir) throws Exception {
        MethodFacts privateCtor =
                new MethodFacts("<init>", "()V", Opcodes.ACC_PRIVATE, List.of(), List.of(), List.of(), List.of(), 0, 1);
        MethodFacts helper =
                new MethodFacts("help", "()V", Opcodes.ACC_STATIC, List.of(), List.of(), List.of(), List.of(), 0, 1);
        ClassFacts holder = new ClassFacts(
                "p/H",
                Opcodes.ACC_FINAL,
                "java/lang/Object",
                List.of(),
                "H.java",
                List.of(),
                List.of(),
                List.of(privateCtor, helper),
                Set.of());
        ClassFacts instance = new ClassFacts(
                "p/I",
                Opcodes.ACC_FINAL,
                "java/lang/Object",
                List.of(),
                "I.java",
                List.of(),
                List.of(new FieldFacts("x", "I", 0, null, List.of(), null)),
                List.of(privateCtor, helper),
                Set.of());
        assertThat(CouplingEvaluator.staticHolder(holder)).isTrue();
        assertThat(CouplingEvaluator.staticHolder(instance))
                .as("an instance field")
                .isFalse();
        FactsIndex idx = index(
                holder, instance, cls("p/A", "p/H#help()V", "p/I#help()V"), cls("p/B", "p/H#help()V", "p/I#help()V"));
        Evaluation e = module(dir, rule(dir, "measure = \"static-fan-in\"\ncap = 1\n"), idx);
        assertThat(e.observations()).singleElement().satisfies(o -> {
            assertThat(o.key()).isEqualTo("m:p.H");
            assertThat(o.value()).isEqualTo(2.0);
        });
    }
}
