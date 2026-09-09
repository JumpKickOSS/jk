// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.facts.AnnotationFacts;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.facts.MethodFacts;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.layout.ModuleLayout;
import cc.jumpkick.layout.TestSuites;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlTable;

/**
 * {@code tiers}: suites are scope, tags are cost. A test class that {@code uses} one of the named
 * types (any type reference, so a field, a parameter or a call all count) or is {@code tagged} with
 * one of the named tags must live in {@code suite} or carry {@code tag}. The class's suite is the
 * suite whose source root holds its source file, across every suite the module declares — the
 * module's test index carries all compiled suites together. Fingerprint: the class. A module with
 * no test classes is clean with no bite: the rule has nothing to say there, and a workspace has
 * modules like that.
 */
final class TiersEvaluator implements Evaluator {

    static final String TAG = "org.junit.jupiter.api.Tag";

    @Override
    public Evaluation evaluate(Rule rule, EvalContext ctx) {
        TomlTable t = rule.table();
        List<String> uses = ForbidEvaluator.strings(t, "uses");
        List<String> tagged = ForbidEvaluator.strings(t, "tagged");
        String suite = t.getString("suite");
        String tag = t.getString("tag");
        Path moduleDir = ctx.moduleDir();
        if (moduleDir == null) return Evaluation.failed("tiers runs in the module lane");
        FactsIndex test = ctx.testFacts();
        if (test == null) return Evaluation.noTestClasses();
        List<Pattern> typeGlobs = new ArrayList<>();
        for (String g : uses) typeGlobs.add(typeGlob(g));
        boolean compact = ModuleLayout.isCompact(moduleDir);
        List<String> suites = TestSuites.discover(moduleDir, compact);

        List<Observation> sites = new ArrayList<>();
        long examined = 0;
        for (ClassFacts c : test.classList()) {
            if (c.isPackageInfo()) continue;
            examined++;
            String cause = cause(c, typeGlobs, tagged);
            if (cause == null) continue;
            String own = suiteOf(c, moduleDir, compact, suites);
            String where = own == null ? "an unknown suite" : "src/" + own;
            if (suite != null && !suite.equals(own)) {
                sites.add(Observation.site(
                        c.binaryName(),
                        source(ctx, c, own),
                        0,
                        c.binaryName() + " " + cause + " but lives in " + where + ", not src/" + suite));
            } else if (tag != null && !tags(c).contains(tag)) {
                sites.add(Observation.site(
                        c.binaryName(),
                        source(ctx, c, own),
                        0,
                        c.binaryName() + " " + cause + " but carries no @Tag(\"" + tag + "\")"));
            }
        }
        return Evaluation.of(Map.of("test-classes", examined), sites);
    }

    private static @Nullable String cause(ClassFacts c, List<Pattern> typeGlobs, List<String> tagged) {
        for (String ref : c.typeRefs()) {
            String binary = ref.replace('/', '.');
            for (Pattern p : typeGlobs) if (p.matcher(binary).matches()) return "uses " + binary;
        }
        if (!tagged.isEmpty()) {
            Set<String> own = tags(c);
            for (String want : tagged) if (own.contains(want)) return "is tagged \"" + want + "\"";
        }
        return null;
    }

    /** Class-level tags plus any method's — a class with one slow test is in part a slow class. */
    static Set<String> tags(ClassFacts c) {
        Set<String> out = new LinkedHashSet<>();
        for (AnnotationFacts a : c.annotations()) if (a.typeName().equals(TAG)) out.addAll(a.value());
        for (MethodFacts m : c.methods())
            for (AnnotationFacts a : m.annotations()) if (a.typeName().equals(TAG)) out.addAll(a.value());
        return out;
    }

    /** The suite whose source root holds this class's source file; {@code null} when none does. */
    static @Nullable String suiteOf(ClassFacts c, Path moduleDir, boolean compact, List<String> suites) {
        String file = c.sourceFile();
        if (file == null) return null;
        String rel = c.packageName().replace('.', '/') + (c.packageName().isEmpty() ? "" : "/") + file;
        for (String s : suites) {
            List<Path> roots = new ArrayList<>(TestSuites.javaRoots(moduleDir, compact, s));
            roots.addAll(TestSuites.kotlinRoots(moduleDir, compact, s));
            roots.addAll(TestSuites.groovyRoots(moduleDir, compact, s));
            roots.addAll(TestSuites.scalaRoots(moduleDir, compact, s));
            for (Path r : roots) if (Files.isRegularFile(r.resolve(rel))) return s;
        }
        return null;
    }

    private static @Nullable String source(EvalContext ctx, ClassFacts c, @Nullable String suite) {
        String file = c.sourceFile();
        if (file == null || suite == null) return null;
        String pkgPath = c.packageName().replace('.', '/');
        String rel = (pkgPath.isEmpty() ? "" : pkgPath + "/") + file;
        return (ctx.module().isEmpty() ? "" : ctx.module() + "/") + "src/" + suite + "/java/" + rel;
    }

    /** {@code org.testcontainers.**} → any type under that package; {@code *} stays within one segment. */
    static Pattern typeGlob(String glob) {
        StringBuilder re = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char ch = glob.charAt(i);
            if (ch == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    re.append(".*");
                    i++;
                } else {
                    re.append("[^.]*");
                }
            } else if (ch == '.') {
                re.append("\\.");
            } else {
                re.append(Pattern.quote(String.valueOf(ch)));
            }
        }
        return Pattern.compile(re.toString());
    }
}
