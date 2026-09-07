// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.baseline.Baseline;
import cc.jumpkick.guard.baseline.Entry;
import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.baseline.RuleBaseline;
import cc.jumpkick.guard.extract.FactsExtractor;
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
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

class ApiEvaluatorTest {

    /** A class with the given access, plus methods and fields given as {@code access name desc}. */
    private static byte[] cls(String internal, int access, String[] methods, String[] fields) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, access, internal, null, "java/lang/Object", null);
        for (String m : methods) {
            String[] p = m.split(" ");
            int acc = Integer.parseInt(p[0]);
            var mv = cw.visitMethod(acc, p[1], p[2], null, null);
            if ((acc & Opcodes.ACC_ABSTRACT) == 0) {
                mv.visitCode();
                mv.visitInsn(p[2].endsWith("V") ? Opcodes.RETURN : Opcodes.ACONST_NULL);
                if (!p[2].endsWith("V")) mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(1, 1);
            }
            mv.visitEnd();
        }
        for (String f : fields) {
            String[] p = f.split(" ");
            cw.visitField(Integer.parseInt(p[0]), p[1], p[2], null, null).visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static final int PUB = Opcodes.ACC_PUBLIC;
    private static final int PUB_ABSTRACT_IFACE = PUB | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE;
    private static final String P = PUB + " ";
    private static final String PA = (PUB | Opcodes.ACC_ABSTRACT) + " ";

    private static byte[][] v1() {
        return new byte[][] {
            cls(
                    "api/Kept",
                    PUB,
                    new String[] {
                        P + "run ()V",
                        P + "name ()Ljava/lang/String;",
                        P + "gone ()V",
                        P + "toStatic ()V",
                        (PUB | Opcodes.ACC_STATIC) + " fromStatic ()V",
                        (PUB | Opcodes.ACC_STATIC) + " <clinit> ()V"
                    },
                    new String[] {
                        P + "count Ljava/lang/Object;",
                        P + "removed Ljava/lang/Object;",
                        P + "retyped Ljava/lang/Object;"
                    }),
            cls("api/Gone", PUB, new String[0], new String[0]),
            cls("api/Open", PUB, new String[0], new String[0]),
            cls("api/Iface", PUB_ABSTRACT_IFACE, new String[] {PA + "one ()V"}, new String[0]),
            cls("api/internal/Hidden", PUB, new String[] {P + "x ()V"}, new String[0]),
            cls("api/Pkg", 0, new String[] {P + "x ()V"}, new String[0]),
        };
    }

    private static byte[][] v2() {
        return new byte[][] {
            cls(
                    "api/Kept",
                    PUB,
                    new String[] {
                        P + "run ()V",
                        P + "name ()Ljava/lang/CharSequence;",
                        (PUB | Opcodes.ACC_FINAL) + " toStatic ()V",
                        P + "fromStatic ()V",
                        P + "added ()V"
                    },
                    new String[] {
                        (PUB | Opcodes.ACC_FINAL) + " count Ljava/lang/Object;", P + "retyped Ljava/lang/String;"
                    }),
            cls("api/Open", PUB | Opcodes.ACC_FINAL, new String[0], new String[0]),
            cls(
                    "api/Iface",
                    PUB_ABSTRACT_IFACE,
                    new String[] {PA + "one ()V", PA + "two ()V", (PUB | Opcodes.ACC_STATIC) + " helper ()V"},
                    new String[0]),
            cls("api/Pkg", PUB, new String[0], new String[0]),
        };
    }

    private static Path jar(Path root, String rel, byte[][] classes) throws IOException {
        Path jar = root.resolve(rel);
        Files.createDirectories(jar.getParent());
        try (OutputStream out = Files.newOutputStream(jar);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zip.write("Manifest-Version: 1.0\n".getBytes());
            zip.closeEntry();
            for (byte[] c : classes) {
                zip.putNextEntry(new ZipEntry(FactsExtractor.extract(c).name() + ".class"));
                zip.write(c);
                zip.closeEntry();
            }
        }
        return jar;
    }

    private static void index(Path root, String module, byte[][] classes) throws IOException {
        Map<String, ClassFacts> m = new LinkedHashMap<>();
        for (byte[] c : classes) {
            ClassFacts f = FactsExtractor.extract(c);
            m.put(f.name(), f);
        }
        Path idx = FactsIndexing.indexPath(BuildLayout.moduleTargetDir(root, root.resolve(module)), "main");
        Files.createDirectories(idx.getParent());
        FactsFormat.write(idx, new FactsIndex(m, Map.of(), "v2"));
    }

    private static Rule rule(Path dir, String body) throws IOException {
        Files.writeString(
                dir.resolve(GuardsPresence.RULES_FILE),
                "[guards.r]\nkind = \"api\"\nwhy = \"w\"\nscope = [\"m\"]\n" + body);
        LoadResult load = GuardRules.load(dir, GuardsConfig.ABSENT);
        assertThat(load.hasErrors()).as(load.problems().toString()).isFalse();
        return load.rules().rule("r").orElseThrow();
    }

    private static Evaluation eval(Path root, Rule rule) throws Exception {
        List<Path> modules = List.of(root.resolve("m"), root.resolve("other"));
        Supplier<FactsIndex> merged = EvalContext.lazy(() -> WorkspaceFacts.merged(root, modules));
        EvalContext ws = new EvalContext(Lane.WORKSPACE, root, "", null, modules, merged, () -> null, List::of);
        return Evaluators.forKind(rule.kind()).evaluate(rule, ws);
    }

    @Test
    void every_breaking_change_is_a_coded_site_and_additions_are_not(@TempDir Path root) throws Exception {
        jar(root, "releases/m-1.0.jar", v1());
        index(root, "m", v2());
        index(root, "other", new byte[][] {cls("api/Gone", PUB, new String[0], new String[0])});
        Rule r = rule(root, "against = \"releases/m-1.0.jar\"\n");
        assertThat(Evaluators.laneOf(r)).isEqualTo(Lane.WORKSPACE);
        Evaluation e = eval(root, r);
        assertThat(e.outcome()).isEqualTo(Outcome.VIOLATIONS);
        assertThat(e.observations())
                .extracting(Observation::key)
                .containsExactlyInAnyOrder(
                        "CLASS_REMOVED api.Gone",
                        "CLASS_REMOVED api.internal.Hidden",
                        "CLASS_NOW_FINAL api.Open",
                        "METHOD_REMOVED api.Kept#gone()",
                        "METHOD_RETURN_TYPE_CHANGED api.Kept#name()",
                        "METHOD_NOW_FINAL api.Kept#toStatic()",
                        "METHOD_NO_LONGER_STATIC api.Kept#fromStatic()",
                        "INTERFACE_ADDED_METHOD api.Iface#two()",
                        "FIELD_REMOVED api.Kept.removed",
                        "FIELD_TYPE_CHANGED api.Kept.retyped",
                        "FIELD_NOW_FINAL api.Kept.count");
        // the surface counts public classes on each side: Kept, Gone, Open, Iface, internal.Hidden before; Kept, Open,
        // Iface, Pkg after
        assertThat(e.population()).containsEntry("before", 5L).containsEntry("after", 4L);
        assertThat(e.observations()).allSatisfy(o -> assertThat(o.file()).isEqualTo("m/" + BuildLayout.TARGET));
    }

    @Test
    void packages_narrows_the_surface_and_codes_ignores_changes(@TempDir Path root) throws Exception {
        jar(root, "releases/m-1.0.jar", v1());
        index(root, "m", v2());
        Evaluation e = eval(root, rule(root, "against = \"releases/m-1.0.jar\"\npackages = [\"api.internal..\"]\n"));
        // Hidden is gone from v2 and it is the only class in scope
        assertThat(e.observations()).extracting(Observation::key).containsExactly("CLASS_REMOVED api.internal.Hidden");
        Evaluation ignored = eval(
                root,
                rule(
                        root,
                        "against = \"releases/m-1.0.jar\"\ncodes = [\"CLASS_REMOVED\", \"FIELD_REMOVED\", \"METHOD_REMOVED\"]\n"));
        assertThat(ignored.observations())
                .extracting(Observation::key)
                .noneMatch(k -> k.endsWith("_REMOVED") || k.startsWith("CLASS_REMOVED"));
        assertThat(ignored.observations()).extracting(Observation::key).contains("CLASS_NOW_FINAL api.Open");
    }

    @Test
    void unknown_code_is_a_rule_error_and_missing_before_side_is_not_evaluated(@TempDir Path root) throws Exception {
        index(root, "m", v2());
        Evaluation bad = eval(root, rule(root, "against = \"releases/m-1.0.jar\"\ncodes = [\"CLASS_EXPLODED\"]\n"));
        assertThat(bad.outcome()).isEqualTo(Outcome.SCANNER_FAILED);
        assertThat(bad.note()).contains("CLASS_EXPLODED");
        Evaluation missing = eval(root, rule(root, "against = \"releases/m-1.0.jar\"\n"));
        assertThat(missing.outcome()).isEqualTo(Outcome.NOT_EVALUATED);
        Evaluation coord = eval(root, rule(root, "against = \"cc.jumpkick:m:1.0\"\n"));
        assertThat(coord.outcome()).isEqualTo(Outcome.NOT_EVALUATED);
        assertThat(coord.note()).contains("lock");
        Evaluation previous = eval(root, rule(root, "against = \"previous-release\"\n"));
        assertThat(previous.outcome()).isEqualTo(Outcome.NOT_EVALUATED);
        assertThat(previous.note()).contains("resolver");
        Evaluation recorded = eval(root, rule(root, "against = \"baseline\"\n"));
        assertThat(recorded.outcome()).isEqualTo(Outcome.UNSUPPORTED);
    }

    @Test
    void the_same_jar_is_read_once_and_a_clean_compare_is_clean(@TempDir Path root) throws Exception {
        jar(root, "releases/m-1.0.jar", v1());
        index(root, "m", v1());
        Rule r = rule(root, "against = \"releases/m-1.0.jar\"\n");
        assertThat(eval(root, r).outcome()).isEqualTo(Outcome.CLEAN);
        Path cache = root.resolve(BuildLayout.TARGET).resolve("jk-guards").resolve("api");
        try (var files = Files.list(cache)) {
            assertThat(files.filter(p -> p.toString().endsWith(".idx")).count()).isEqualTo(1);
        }
        assertThat(eval(root, r).outcome()).isEqualTo(Outcome.CLEAN);
        try (var files = Files.list(cache)) {
            assertThat(files.count()).isEqualTo(1);
        }
    }

    @Test
    void a_coordinate_resolves_through_the_lock_to_the_store_and_the_baseline_gate_is_the_breaking_key(
            @TempDir Path root, @TempDir Path store) throws Exception {
        byte[] bytes = Files.readAllBytes(jar(root, "tmp/m-1.0.jar", v1()));
        String hex =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Path cas = store.resolve("sha256")
                .resolve(hex.substring(0, 2))
                .resolve(hex.substring(2, 4))
                .resolve(hex.substring(4));
        Files.createDirectories(cas.getParent());
        Files.write(cas, bytes);
        Files.writeString(root.resolve("jk-lock.toml"), """
                version = 1
                generated-by = "jk 0.13.0"
                resolution-algorithm = "pubgrub-v1"
                jk-min = "0.12.0"
                manifests-sha256 = "09e94877e51398d55aeb5a7f68653ab539aca1c32611be167f0de18de8c1c15c"
                project-id = "a66f86e5f1e675e17a67c9c36474f968"

                [[artifact]]
                name     = "cc.jumpkick:m:jar:"
                version  = "1.0"
                source   = "central+https://repo.maven.apache.org/maven2/"
                checksum = "sha256:%s"
                """.formatted(hex));
        assertThat(ApiEvaluator.locate("cc.jumpkick:m:1.0", root, store)).isEqualTo(cas);
        assertThat(ApiEvaluator.locate("cc.jumpkick:m:1.1", root, store)).isNull();
        assertThat(ApiEvaluator.locate("cc.jumpkick:other:1.0", root, store)).isNull();
        // the repos layout is the fallback when the object is not in the content store
        Files.delete(cas);
        assertThat(ApiEvaluator.locate("cc.jumpkick:m:1.0", root, store)).isNull();
        Path repo = store.resolve("repos/central/cc/jumpkick/m/1.0/m-1.0.jar");
        Files.createDirectories(repo.getParent());
        Files.write(repo, bytes);
        assertThat(ApiEvaluator.locate("cc.jumpkick:m:1.0", root, store)).isEqualTo(repo);
        // a jar that is not a coordinate must be on disk
        assertThat(ApiEvaluator.locate("tmp/m-1.0.jar", root, store)).isEqualTo(root.resolve("tmp/m-1.0.jar"));
        assertThat(ApiEvaluator.locate("tmp/missing.jar", root, store)).isNull();

        assertThat(Evaluators.acceptsBaseline(rule(root, "against = \"tmp/m-1.0.jar\"\n")))
                .isFalse();
        assertThat(Evaluators.acceptsBaseline(rule(root, "against = \"tmp/m-1.0.jar\"\nbreaking = \"forbid\"\n")))
                .isFalse();
        assertThat(Evaluators.acceptsBaseline(rule(root, "against = \"tmp/m-1.0.jar\"\nbreaking = \"baseline\"\n")))
                .isTrue();
    }

    @Test
    void forbid_ignores_the_baseline_and_baseline_mode_honours_it(@TempDir Path root) throws Exception {
        jar(root, "releases/m-1.0.jar", v1());
        index(root, "m", v2());
        Baseline before = Baseline.EMPTY.with(
                "r",
                RuleBaseline.of(Map.of(), List.of(new Entry.Site("CLASS_REMOVED api.Gone", "Gone was never used"))));
        List<Path> modules = List.of(root.resolve("m"));
        Supplier<FactsIndex> merged = EvalContext.lazy(() -> WorkspaceFacts.merged(root, modules));
        EvalContext ws = new EvalContext(Lane.WORKSPACE, root, "", null, modules, merged, () -> null, List::of);

        Rule forbid = rule(root, "against = \"releases/m-1.0.jar\"\n");
        RuleReport strict = LaneRun.run(Lane.WORKSPACE, List.of(forbid), ws, before)
                .reports()
                .get(0);
        assertThat(strict.outcome()).isEqualTo(Outcome.VIOLATIONS);
        assertThat(strict.baselined()).isEmpty();
        assertThat(strict.fresh()).extracting(Observation::key).contains("CLASS_REMOVED api.Gone");

        Rule lenient = rule(root, "against = \"releases/m-1.0.jar\"\nbreaking = \"baseline\"\n");
        RuleReport ratchet = LaneRun.run(Lane.WORKSPACE, List.of(lenient), ws, before)
                .reports()
                .get(0);
        assertThat(ratchet.baselined()).extracting(Observation::key).containsExactly("CLASS_REMOVED api.Gone");
        assertThat(ratchet.fresh()).extracting(Observation::key).doesNotContain("CLASS_REMOVED api.Gone");
    }

    @Test
    void a_rule_that_matches_no_module_or_several_is_a_rule_error(@TempDir Path root) throws Exception {
        jar(root, "releases/m-1.0.jar", v1());
        index(root, "m", v2());
        index(root, "other", v2());
        Files.writeString(
                root.resolve(GuardsPresence.RULES_FILE),
                "[guards.r]\nkind = \"api\"\nwhy = \"w\"\nagainst = \"releases/m-1.0.jar\"\n");
        Rule wide = GuardRules.load(root, GuardsConfig.ABSENT).rules().rule("r").orElseThrow();
        Evaluation e = eval(root, wide);
        assertThat(e.outcome()).isEqualTo(Outcome.SCANNER_FAILED);
        assertThat(e.note()).contains("one module");
    }
}
