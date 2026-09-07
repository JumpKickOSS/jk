// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.extract.FactsExtractor;
import cc.jumpkick.guard.extract.FactsIndexing;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.FactsFormat;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.rules.GuardRules;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.guard.rules.RuleSet;
import cc.jumpkick.guard.schema.Kind;
import cc.jumpkick.guard.schema.Lane;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.model.GuardsConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** A compiled suite declares its guards through class-file annotations; a report line becomes an evaluation. */
class GuardSuitesTest {

    /** A {@code @GuardSuite(scope)} class with the given {@code @Guard} methods: {@code id|why|instead|desc[|allow=in;reason][|fixture=path]}. */
    private static ClassFacts suite(String internal, String scope, String... guards) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_FINAL, internal, null, "java/lang/Object", null);
        cw.visitSource(internal.substring(internal.lastIndexOf('/') + 1) + ".java", null);
        AnnotationVisitor sa = cw.visitAnnotation("Lcc/jumpkick/guard/api/GuardSuite;", true);
        sa.visitEnum("scope", "Lcc/jumpkick/guard/api/Scope;", scope);
        sa.visitEnd();
        int line = 10;
        for (String g : guards) {
            String[] p = g.split("\\|");
            MethodVisitor mv = cw.visitMethod(0, p[0].replace('-', '_'), p[3], null, null);
            AnnotationVisitor ga = mv.visitAnnotation("Lcc/jumpkick/guard/api/Guard;", true);
            ga.visit("id", p[0]);
            ga.visit("why", p[1]);
            if (!p[2].isEmpty()) ga.visit("instead", p[2]);
            ga.visitEnd();
            for (int i = 4; i < p.length; i++) {
                if (p[i].startsWith("allow=")) {
                    String[] ar = p[i].substring(6).split(";");
                    AnnotationVisitor aa = mv.visitAnnotation("Lcc/jumpkick/guard/api/Allow;", true);
                    aa.visit("in", ar[0]);
                    aa.visit("reason", ar[1]);
                    aa.visitEnd();
                } else if (p[i].startsWith("fixture=")) {
                    AnnotationVisitor fa = mv.visitAnnotation("Lcc/jumpkick/guard/api/Fixture;", true);
                    fa.visit("value", p[i].substring(8));
                    fa.visitEnd();
                }
            }
            mv.visitCode();
            Label l = new Label();
            mv.visitLabel(l);
            mv.visitLineNumber(line += 5, l);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 8);
            mv.visitEnd();
        }
        cw.visitEnd();
        return FactsExtractor.extract(cw.toByteArray());
    }

    private static FactsIndex index(ClassFacts... classes) {
        Map<String, ClassFacts> m = new LinkedHashMap<>();
        for (ClassFacts c : classes) m.put(c.name(), c);
        return new FactsIndex(m, Map.of(), "s");
    }

    private static final String FACTS_V = "(Lcc/jumpkick/guard/api/Facts;Lcc/jumpkick/guard/api/Violations;)V";
    private static final String TEXT_V =
            "(Lcc/jumpkick/guard/api/Facts;Lcc/jumpkick/guard/api/Text;Lcc/jumpkick/guard/api/Violations;)V";

    @Test
    void declared_guards_carry_their_annotation_fields_and_become_rules(@TempDir Path root) throws Exception {
        FactsIndex idx = index(suite(
                "rules/HouseRules",
                "WORKSPACE",
                "one-json-codec|two escapers|Jsonl.quote|" + TEXT_V
                        + "|allow=cc.jumpkick.jsonl.*;the codec itself|fixture=guard-fixtures/one-json-codec",
                "tier-partition|a tag no tier runs||" + FACTS_V));
        List<GuardSuites.Declared> declared = GuardSuites.declared(idx);
        assertThat(declared).hasSize(2);
        GuardSuites.Declared first = declared.get(0);
        assertThat(first.id()).isEqualTo("one-json-codec");
        assertThat(first.why()).isEqualTo("two escapers");
        assertThat(first.instead()).isEqualTo("Jsonl.quote");
        assertThat(first.source()).isEqualTo("rules.HouseRules#one_json_codec");
        assertThat(first.workspace()).isTrue();
        assertThat(first.readsText()).isTrue();
        assertThat(first.line()).isEqualTo(15);
        assertThat(first.allows()).singleElement().satisfies(a -> {
            assertThat(a.in()).isEqualTo("cc.jumpkick.jsonl.*");
            assertThat(a.reason()).isEqualTo("the codec itself");
        });
        assertThat(first.fixture()).isEqualTo("guard-fixtures/one-json-codec");
        assertThat(declared.get(1).readsText()).isFalse();
        assertThat(declared.get(1).instead()).isEmpty();
        Rule r = GuardSuites.rule(first, root, "lib");
        assertThat(r.kind()).isEqualTo(Kind.TEST);
        assertThat(r.id()).isEqualTo("one-json-codec");
        assertThat(r.instead()).isEqualTo("Jsonl.quote");
        assertThat(r.baseline()).isTrue();
        assertThat(r.source().file()).isEqualTo(root.resolve("lib/src/guard/java/rules/HouseRules.java"));
        assertThat(r.source().line()).isEqualTo(15);
        assertThat(Evaluators.laneOf(r)).isEqualTo(Lane.WORKSPACE);
        Rule second = GuardSuites.rule(declared.get(1), root, "lib");
        assertThat(Evaluators.laneOf(second)).isEqualTo(Lane.WORKSPACE);
        assertThat(second.instead()).isNull();
        assertThat(GuardSuites.anyWorkspace(declared)).isTrue();
        assertThat(GuardSuites.ids(declared)).containsExactly("one-json-codec", "tier-partition");
    }

    @Test
    void load_errors_name_duplicate_ids_bad_ids_and_text_in_a_module_suite(@TempDir Path root) throws Exception {
        Files.writeString(
                root.resolve(GuardsPresence.RULES_FILE),
                "[guards.taken]\nkind = \"text\"\npattern = \"x\"\ninstead = \"y\"\nwhy = \"w\"\n");
        RuleSet toml = GuardRules.load(root, GuardsConfig.ABSENT).rules();
        FactsIndex idx = index(
                suite(
                        "a/A",
                        "MODULE",
                        "taken|w||" + FACTS_V,
                        "reads-text|w||" + TEXT_V,
                        "Bad_Id|w||" + FACTS_V,
                        "twice|w||" + FACTS_V),
                suite("b/B", "MODULE", "twice|w||" + FACTS_V, "no-why|||" + FACTS_V));
        List<String> errors = GuardSuites.loadErrors(GuardSuites.declared(idx), toml);
        assertThat(errors).hasSize(4);
        assertThat(errors.get(0)).contains("`taken` is already [guards.taken]");
        assertThat(errors.get(1)).contains("`Bad_Id`").contains("lower-case");
        assertThat(errors.get(2)).contains("`twice` is also declared by a.A#twice");
        assertThat(errors.get(3)).contains("no-why").contains("needs why");
        // Text in a MODULE suite is allowed: the lane keys on the tree's inputs when a guard reads it
        List<GuardSuites.Declared> textReaders =
                GuardSuites.declared(index(suite("c/C", "MODULE", "fine|w||" + TEXT_V)));
        assertThat(GuardSuites.loadErrors(textReaders, toml)).isEmpty();
        assertThat(GuardSuites.anyReadsText(textReaders)).isTrue();
        assertThat(GuardSuites.anyReadsText(GuardSuites.declared(index(suite("d/D", "MODULE", "fine|w||" + FACTS_V)))))
                .isFalse();
    }

    @Test
    void a_report_line_is_an_evaluation_with_allows_applied(@TempDir Path root) throws Exception {
        FactsIndex idx = index(suite(
                "rules/R",
                "MODULE",
                "esc|w|codec|" + FACTS_V + "|allow=a.Legacy;grandfathered",
                "stale|w||" + FACTS_V + "|allow=nothing.Here;typo",
                "boom|w||" + FACTS_V,
                "owner|w||" + FACTS_V,
                "quiet|w||" + FACTS_V + "|fixture=guard-fixtures/quiet",
                "silent|w||" + FACTS_V));
        List<GuardSuites.Declared> declared = GuardSuites.declared(idx);
        Map<String, Rule> rules = new LinkedHashMap<>();
        for (GuardSuites.Declared d : declared) rules.put(d.id(), GuardSuites.rule(d, root, "m"));
        Path report = GuardSuites.report(root.resolve("target/m"));
        Files.createDirectories(report.getParent());
        Files.writeString(report, """
                {"id":"esc","outcome":"ok","population":12,"violations":[{"fingerprint":"a.Esc#write()V -> java.lang.String#replace(CC)Ljava/lang/String;","file":"a/Esc.java","line":7,"detail":"an escaper"},{"fingerprint":"a.Legacy#old()V -> java.lang.String#replace(CC)Ljava/lang/String;","file":"a/Legacy.java","line":3,"detail":"old escaper"},{"fingerprint":"a/Big.java","value":900,"detail":"lines = 900"}]}
                {"id":"stale","outcome":"ok","violations":[]}
                {"id":"boom","outcome":"threw","error":"java.lang.IllegalStateException: kaboom","violations":[]}
                {"id":"owner","outcome":"owner-missing","error":"owner a.Missing is not in the facts in scope","violations":[]}
                {"id":"quiet","outcome":"ok","violations":[]}
                {"id":"silent","outcome":"ok","violations":[]}
                """);
        Map<String, Object> lines = GuardSuites.readReport(report);
        assertThat(lines).containsKeys("esc", "stale", "boom", "owner", "quiet", "silent");
        assertThat(MiniJson.str(lines.get("esc"), "outcome")).isEqualTo("ok");

        Evaluation esc = GuardSuites.evaluate(Objects.requireNonNull(rules.get("esc")), lines.get("esc"), "m");
        assertThat(esc.outcome()).isEqualTo(Outcome.VIOLATIONS);
        assertThat(esc.observations())
                .extracting(Observation::key)
                .containsExactly("a.Esc#write()V -> java.lang.String#replace(CC)Ljava/lang/String;", "a/Big.java");
        assertThat(esc.observations().get(0).file()).isEqualTo("m/src/main/java/a/Esc.java");
        assertThat(esc.observations().get(0).line()).isEqualTo(7);
        assertThat(esc.observations().get(1).isMetric()).isTrue();
        assertThat(esc.observations().get(1).value()).isEqualTo(900.0);
        assertThat(esc.population()).containsEntry("examined", 12L);
        assertThat(esc.bites()).isTrue();

        Evaluation stale = GuardSuites.evaluate(Objects.requireNonNull(rules.get("stale")), lines.get("stale"), "m");
        assertThat(stale.outcome()).isEqualTo(Outcome.STALE_ALLOW);
        assertThat(stale.note()).contains("nothing.Here");
        Evaluation boom = GuardSuites.evaluate(Objects.requireNonNull(rules.get("boom")), lines.get("boom"), "m");
        assertThat(boom.outcome()).isEqualTo(Outcome.SCANNER_FAILED);
        assertThat(boom.note()).contains("kaboom");
        assertThat(GuardSuites.evaluate(Objects.requireNonNull(rules.get("owner")), lines.get("owner"), "m")
                        .outcome())
                .isEqualTo(Outcome.OWNER_MISSING);
        // a clean guard bites through its fixture; without one and with nothing examined it does not
        assertThat(GuardSuites.evaluate(Objects.requireNonNull(rules.get("quiet")), lines.get("quiet"), "m")
                        .bites())
                .isTrue();
        assertThat(GuardSuites.evaluate(Objects.requireNonNull(rules.get("silent")), lines.get("silent"), "m")
                        .bites())
                .isFalse();
        assertThat(GuardSuites.evaluate(Objects.requireNonNull(rules.get("silent")), null, "m")
                        .outcome())
                .isEqualTo(Outcome.SCANNER_FAILED);

        // the test kind's evaluator reads the same report through the module lane context
        EvalContext ctx = new EvalContext(
                Lane.MODULE,
                root,
                "m",
                root.resolve("m"),
                List.of(root.resolve("m")),
                () -> FactsIndex.EMPTY,
                () -> null,
                List::of);
        assertThat(Evaluators.forKind(Kind.TEST)
                        .evaluate(Objects.requireNonNull(rules.get("esc")), ctx)
                        .observations())
                .hasSize(2);
    }

    @Test
    void kind_test_is_not_written_in_toml(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), "[guards.x]\nkind = \"test\"\nwhy = \"w\"\n");
        var load = GuardRules.load(root, GuardsConfig.ABSENT);
        assertThat(load.hasErrors()).isTrue();
        assertThat(load.problems().get(0).render()).contains("@Guard method").contains("--schema guard-test");
    }

    @Test
    void declared_guards_are_found_workspace_wide_and_freeze_reaches_them(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("jk.toml"), """
                group = "t"
                name = "ws"
                version = "0.0.1"
                jdk = 25

                [workspace]
                modules = ["m"]
                """);
        Files.createDirectories(root.resolve("m"));
        Files.writeString(root.resolve("m/jk.toml"), "group = \"t\"\nname = \"m\"\nversion = \"0.0.1\"\n");
        Files.writeString(
                root.resolve(GuardsPresence.RULES_FILE),
                "[guards.other]\nkind = \"text\"\npattern = \"x\"\ninstead = \"y\"\nwhy = \"w\"\n");
        // the module lane left a suite index and a report behind
        Path target = root.resolve("target/m");
        Path idx = FactsIndexing.indexPath(target, "guard");
        Files.createDirectories(idx.getParent());
        FactsFormat.write(idx, index(suite("rules/R", "MODULE", "esc|w|codec|" + FACTS_V)));
        Files.createDirectories(target.resolve("classes/main"));
        Path report = GuardSuites.report(target);
        Files.createDirectories(report.getParent());
        Files.writeString(
                report,
                "{\"id\":\"esc\",\"outcome\":\"ok\",\"population\":3,\"violations\":[{\"fingerprint\":\"a.Esc#write()V -> java.lang.String#replace(CC)Ljava/lang/String;\",\"file\":\"a/Esc.java\",\"line\":7,\"detail\":\"an escaper\"}]}\n");

        Map<String, GuardSuites.Located> declared = GuardSuites.declaredAcrossWorkspace(root);
        assertThat(declared).containsOnlyKeys("esc");
        GuardSuites.Located esc = Objects.requireNonNull(declared.get("esc"));
        assertThat(esc.module()).isEqualTo("m");
        Rule rule = GuardSuites.rule(esc.declared(), root, "m");
        assertThat(GuardSuites.moduleOf(rule)).isEqualTo("m");

        Freezer.Result frozen = Freezer.freeze(root, "esc", "grandfathered until the codec lands", false);
        assertThat(frozen.error()).isNull();
        assertThat(frozen.accepted()).isEqualTo(1);
        String baseline = Files.readString(GuardsPresence.baselineFile(root));
        assertThat(baseline)
                .contains("[esc]")
                .contains("a.Esc#write()V -> java.lang.String#replace(CC)Ljava/lang/String;")
                .contains("grandfathered");
        assertThat(Freezer.freeze(root, "nope", "r", false).error()).contains("no rule `nope`");
    }
}
