// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.baseline.Baseline;
import cc.jumpkick.guard.baseline.Entry;
import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.baseline.Reconciliation;
import cc.jumpkick.guard.baseline.RuleBaseline;
import cc.jumpkick.guard.extract.FactsExtractor;
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
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** The facts measures: {@code methods}, {@code public-members} per class; {@code params}, {@code cyclomatic} per method. */
class FactsMetricTest {

    /**
     * {@code a.Many}: five methods (two public, three private), two public fields and one private —
     * so methods = 5, public-members = 4; {@code wide} takes four parameters; {@code branchy} has
     * three conditional jumps (cyclomatic 4). {@code a.Small}: one method, no branches.
     */
    private static FactsIndex index() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "a/Many", null, "java/lang/Object", null);
        cw.visitSource("Many.java", null);
        cw.visitField(Opcodes.ACC_PUBLIC, "f1", "I", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC, "f2", "I", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE, "f3", "I", null, null).visitEnd();
        simple(cw, Opcodes.ACC_PUBLIC, "wide", "(IIII)V");
        simple(cw, Opcodes.ACC_PUBLIC, "narrow", "(I)V");
        simple(cw, Opcodes.ACC_PRIVATE, "p1", "()V");
        simple(cw, Opcodes.ACC_PRIVATE, "p2", "()V");
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "branchy", "(I)I", null, null);
        mv.visitCode();
        Label end = new Label();
        for (int i = 0; i < 3; i++) {
            mv.visitVarInsn(Opcodes.ILOAD, 1);
            mv.visitJumpInsn(Opcodes.IFEQ, end);
        }
        mv.visitLabel(end);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
        cw.visitEnd();
        ClassWriter small = new ClassWriter(0);
        small.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "a/Small", null, "java/lang/Object", null);
        small.visitSource("Small.java", null);
        simple(small, Opcodes.ACC_PUBLIC, "one", "()V");
        small.visitEnd();
        Map<String, ClassFacts> m = new LinkedHashMap<>();
        for (byte[] b : new byte[][] {cw.toByteArray(), small.toByteArray()}) {
            ClassFacts c = FactsExtractor.extract(b);
            m.put(c.name(), c);
        }
        return new FactsIndex(m, Map.of(), "t");
    }

    private static void simple(ClassWriter cw, int access, String name, String desc) {
        MethodVisitor mv = cw.visitMethod(access, name, desc, null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 8);
        mv.visitEnd();
    }

    private static LoadResult load(Path root, String body) throws IOException {
        int allow = body.indexOf("[[guards.m.allow]]");
        String keys = allow < 0 ? body : body.substring(0, allow);
        String tail = allow < 0 ? "" : body.substring(allow);
        Files.writeString(
                root.resolve(GuardsPresence.RULES_FILE),
                "[guards.m]\nkind = \"metric\"\n" + keys + "why = \"w\"\n" + tail);
        LoadResult load = GuardRules.load(root, GuardsConfig.ABSENT);
        assertThat(load.hasErrors()).as(load.problems().toString()).isFalse();
        return load;
    }

    private static EvalContext ctx(Path root, LoadResult load) {
        FactsIndex idx = index();
        return new EvalContext(
                        Lane.MODULE,
                        root,
                        "m",
                        root.resolve("m"),
                        List.of(root.resolve("m")),
                        () -> idx,
                        () -> null,
                        List::of)
                .withRules(load.rules());
    }

    private static Evaluation one(Path root, String body) throws IOException {
        LoadResult load = load(root, body);
        Map<String, Evaluation> r = LaneRun.evaluate(LaneRun.rulesFor(Lane.MODULE, load.rules(), "m"), ctx(root, load));
        return Objects.requireNonNull(r.get("m"), r.toString());
    }

    @Test
    void methods_and_public_members_measure_a_class(@TempDir Path root) throws Exception {
        Evaluation methods = one(root, "measure = \"methods\"\ncap = 3\n");
        assertThat(methods.outcome()).as(methods.note()).isEqualTo(Outcome.VIOLATIONS);
        assertThat(methods.observations()).singleElement().satisfies(o -> {
            assertThat(o.isMetric()).isTrue();
            assertThat(o.key()).isEqualTo("a.Many");
            assertThat(o.value()).isEqualTo(5.0);
            assertThat(o.file()).isEqualTo("m/src/main/java/a/Many.java");
            assertThat(o.detail()).isEqualTo("methods = 5 (cap 3)");
        });
        assertThat(methods.population()).containsEntry("units", 2L);
        Evaluation pub = one(root, "measure = \"public-members\"\ncap = 3\n");
        assertThat(pub.observations()).singleElement().satisfies(o -> {
            assertThat(o.key()).isEqualTo("a.Many");
            assertThat(o.value()).isEqualTo(4.0);
        });
        assertThat(one(root, "measure = \"public-members\"\ncap = 4\n").outcome())
                .isEqualTo(Outcome.CLEAN);
        Evaluation floor = one(root, "measure = \"methods\"\nmin = 2\n");
        assertThat(floor.observations()).extracting(Observation::key).containsExactly("a.Small");
    }

    @Test
    void params_and_cyclomatic_measure_each_method(@TempDir Path root) throws Exception {
        Evaluation params = one(root, "measure = \"params\"\ncap = 3\n");
        assertThat(params.observations()).singleElement().satisfies(o -> {
            assertThat(o.key()).isEqualTo("a.Many#wide(IIII)V");
            assertThat(o.value()).isEqualTo(4.0);
            assertThat(o.detail()).isEqualTo("params = 4 in a.Many#wide (cap 3)");
        });
        assertThat(params.population()).containsEntry("units", 6L);
        Evaluation cyclo = one(root, "measure = \"cyclomatic\"\ncap = 2\n");
        assertThat(cyclo.observations()).singleElement().satisfies(o -> {
            assertThat(o.key()).isEqualTo("a.Many#branchy(I)I");
            assertThat(o.value()).isEqualTo(4.0);
        });
        assertThat(one(root, "measure = \"cyclomatic\"\ncap = 4\n").outcome()).isEqualTo(Outcome.CLEAN);
        Evaluation perLanguage = one(root, "measure = \"params\"\ncap = { java = 3 }\n");
        assertThat(perLanguage.outcome()).isEqualTo(Outcome.SCANNER_FAILED);
        assertThat(perLanguage.note()).contains("scalar");
    }

    @Test
    void a_facts_metric_baseline_ratchets_down_and_fires_on_growth(@TempDir Path root) throws Exception {
        LoadResult load = load(root, "measure = \"methods\"\ncap = 3\nbaseline = true\n");
        List<Rule> rules = LaneRun.rulesFor(Lane.MODULE, load.rules(), "m");
        // the module lane keeps its own slice of the rule's baseline
        Baseline loose = Baseline.EMPTY.with(
                "m",
                RuleBaseline.EMPTY.withLane(
                        "m", Map.of("units", 2L), List.of(new Entry.Metric("a.Many", 9, "legacy"))));
        LaneRun.Result r = LaneRun.run(Lane.MODULE, rules, ctx(root, load), loose);
        assertThat(r.reports().get(0).outcome()).isEqualTo(Outcome.VIOLATIONS);
        Reconciliation rec = Objects.requireNonNull(r.reports().get(0).reconciliation());
        assertThat(rec.fresh()).isEmpty();
        assertThat(rec.baselined()).extracting(Observation::key).containsExactly("a.Many");
        assertThat(r.tightened()).isEqualTo(1);
        Entry.Metric after = (Entry.Metric) r.baseline().of("m").entries("m").get(0);
        assertThat(after.value()).isEqualTo(5.0);
        assertThat(r.red()).isFalse();

        Baseline tight = Baseline.EMPTY.with(
                "m",
                RuleBaseline.EMPTY.withLane(
                        "m", Map.of("units", 2L), List.of(new Entry.Metric("a.Many", 4, "legacy"))));
        LaneRun.Result grown = LaneRun.run(Lane.MODULE, rules, ctx(root, load), tight);
        assertThat(Objects.requireNonNull(grown.reports().get(0).reconciliation())
                        .fresh())
                .extracting(Observation::key)
                .containsExactly("a.Many");
        assertThat(grown.tightened()).isZero();
        assertThat(grown.red()).isTrue();
    }

    @Test
    void allow_exempts_a_class_and_another_modules_class_is_not_stale_here(@TempDir Path root) throws Exception {
        Evaluation allowed =
                one(root, "measure = \"methods\"\ncap = 3\n[[guards.m.allow]]\nin = \"a.Many\"\nreason = \"facade\"\n");
        assertThat(allowed.outcome()).isEqualTo(Outcome.CLEAN);
        Evaluation elsewhere = one(
                root, "measure = \"methods\"\ncap = 3\n[[guards.m.allow]]\nin = \"b.Other\"\nreason = \"facade\"\n");
        assertThat(elsewhere.outcome()).isEqualTo(Outcome.VIOLATIONS);
    }
}
