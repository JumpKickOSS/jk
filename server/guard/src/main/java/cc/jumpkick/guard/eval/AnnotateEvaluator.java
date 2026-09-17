// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.facts.AnnotationFacts;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.Descriptors;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.facts.FieldFacts;
import cc.jumpkick.guard.facts.MethodFacts;
import cc.jumpkick.guard.rules.Allow;
import cc.jumpkick.guard.rules.Rule;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.tomlj.TomlTable;

/**
 * {@code annotate}: an annotation must ({@code require}) or must not ({@code forbid}) be present on
 * every element {@code on} names — package, class, method, field, parameter or test class —
 * optionally narrowed by a {@code matching} class predicate and an attribute {@code with-value}.
 *
 * <p>Presence is read from the class file, so a {@code SOURCE}-retention annotation is not a fact
 * here: naming one is {@code scanner-failed} with the retention spelled out, never a clean pass. A
 * package is judged by its {@code package-info.class}; a package without one fails a {@code
 * require}. The population is the elements examined, so an {@code on} that selects nothing is
 * {@code blind}.
 */
final class AnnotateEvaluator implements Evaluator {

    private static final List<String> TEST_METHOD_ANNOTATIONS = List.of(
            "org.junit.jupiter.api.Test",
            "org.junit.jupiter.params.ParameterizedTest",
            "org.junit.jupiter.api.RepeatedTest",
            "org.junit.jupiter.api.TestFactory",
            "org.junit.jupiter.api.TestTemplate",
            "org.junit.Test",
            "org.testng.annotations.Test",
            "kotlin.test.Test");

    @Override
    public Evaluation evaluate(Rule rule, EvalContext ctx) {
        TomlTable t = rule.table();
        boolean require = t.isString("require");
        String annotation = String.valueOf(require ? t.getString("require") : t.getString("forbid"));
        String on = String.valueOf(t.getString("on"));
        String withValue = t.isString("with-value") ? t.getString("with-value") : null;
        TypeHierarchy types = ctx.hierarchy();

        ClassPredicates.Compiled matching =
                ClassPredicates.compile(t.isTable("matching") ? t.getTable("matching") : null, types);
        if (matching.error() != null) return Evaluation.failed("matching: " + matching.error());

        FactsIndex facts = ctx.facts();
        if (on.equals("test-class")) {
            FactsIndex test = ctx.testFacts();
            if (test == null) return Evaluation.noTestClasses();
            facts = test;
        }
        // Nothing the predicate selects leaves nothing to judge, whether or not the annotation is
        // on the classpath: a library pack's Java-only rule passes a module of Kotlin classes alone.
        if (matching.predicate() != null && facts.classList().stream().noneMatch(matching::test)) {
            return Evaluation.of(
                    Map.of("elements", 0L, "classes", (long) facts.classes().size()), List.of());
        }

        String internal = Descriptors.internalName(annotation);
        var retention = types.retention(internal);
        if (retention.isEmpty()) {
            return Evaluation.failed(
                    "annotation " + annotation + " does not resolve on this module's classpath or JDK");
        }
        if (retention.get().equals("SOURCE")) {
            return Evaluation.failed("annotation " + annotation
                    + " has retention SOURCE: this fact is not in the class file, so no bytecode rule can see it");
        }

        Scan scan = new Scan(rule, ctx, require, annotation, withValue);
        if (on.equals("package")) {
            scan.packages(facts, matching);
        } else {
            Evaluation unknownOn = scan.elements(facts, matching, on);
            if (unknownOn != null) return unknownOn;
        }
        return scan.finish(facts);
    }

    /** One evaluation's walk: what every element check reads, and the sites and count it leaves. */
    private static final class Scan {
        private final Rule rule;
        private final EvalContext ctx;
        private final boolean require;
        private final String annotation;
        private final @Nullable String withValue;
        private final Map<Allow, Boolean> allowUsed = new LinkedHashMap<>();
        private final List<Observation> sites = new ArrayList<>();
        private long examined = 0;
        private final String module;
        private final String verb;
        private final String simple;

        Scan(Rule rule, EvalContext ctx, boolean require, String annotation, @Nullable String withValue) {
            this.rule = rule;
            this.ctx = ctx;
            this.require = require;
            this.annotation = annotation;
            this.withValue = withValue;
            for (Allow a : rule.allow()) allowUsed.put(a, false);
            this.module = ctx.module();
            this.verb = require ? "lacks @" : "carries @";
            this.simple = annotation.substring(Math.max(annotation.lastIndexOf('.'), annotation.lastIndexOf('$')) + 1);
        }

        void packages(FactsIndex facts, ClassPredicates.Compiled matching) {
            // A package is in scope when a class of it matches; its package-info carries the fact.
            Map<String, @Nullable ClassFacts> infos = new TreeMap<>();
            Map<String, ClassFacts> byPackage = new TreeMap<>();
            for (ClassFacts c : facts.classList()) {
                if (c.isPackageInfo()) byPackage.put(c.packageName(), c);
                else if (matching.test(c)) infos.putIfAbsent(c.packageName(), null);
            }
            for (String pkg : infos.keySet()) infos.put(pkg, byPackage.get(pkg));
            for (var e : infos.entrySet()) {
                String pkg = e.getKey();
                ClassFacts info = e.getValue();
                examined++;
                boolean present = info != null && present(info.annotations(), annotation, withValue);
                if (present == require) continue;
                Allow a = allowingPackage(rule.allow(), pkg, module);
                if (a != null) {
                    allowUsed.put(a, true);
                    continue;
                }
                String detail = info == null
                        ? "package " + pkg + " has no package-info.java to carry @" + simple
                        : "package " + pkg + " " + verb + simple + valueNote(withValue);
                sites.add(Observation.site(pkg, info == null ? null : ForbidEvaluator.source(ctx, info), 0, detail));
            }
        }

        /** Classes, methods, fields or parameters; the failure for an unknown {@code on}, else null. */
        @Nullable
        Evaluation elements(FactsIndex facts, ClassPredicates.Compiled matching, String on) {
            for (ClassFacts c : facts.classList()) {
                if (c.isPackageInfo() || c.hasFlag(Opcodes.ACC_SYNTHETIC)) continue;
                if (!matching.test(c)) continue;
                if (on.equals("test-class") && !isTestClass(c)) continue;
                String src = ForbidEvaluator.source(ctx, c);
                switch (on) {
                    case "class", "test-class" -> {
                        examined++;
                        if (present(c.annotations(), annotation, withValue) != require) {
                            add(c, c.binaryName(), src, 0, c.binaryName() + " " + verb + simple + valueNote(withValue));
                        }
                    }
                    case "method" -> methods(c, src);
                    case "field" -> fields(c, src);
                    case "parameter" -> parameters(c, src);
                    default -> {
                        return Evaluation.failed("unknown `on` " + on);
                    }
                }
            }
            return null;
        }

        private void methods(ClassFacts c, @Nullable String src) {
            for (MethodFacts m : c.methods()) {
                if (m.name().startsWith("<") || (m.access() & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0)
                    continue;
                examined++;
                if (present(m.annotations(), annotation, withValue) != require) {
                    add(
                            c,
                            c.binaryName() + "#" + m.member(),
                            src,
                            m.firstLine(),
                            c.binaryName() + "#" + m.name() + " " + verb + simple + valueNote(withValue));
                }
            }
        }

        private void fields(ClassFacts c, @Nullable String src) {
            for (FieldFacts f : c.fields()) {
                if ((f.access() & Opcodes.ACC_SYNTHETIC) != 0) continue;
                examined++;
                if (present(f.annotations(), annotation, withValue) != require) {
                    add(
                            c,
                            c.binaryName() + "." + f.name(),
                            src,
                            0,
                            c.binaryName() + "." + f.name() + " " + verb + simple + valueNote(withValue));
                }
            }
        }

        private void parameters(ClassFacts c, @Nullable String src) {
            for (MethodFacts m : c.methods()) {
                if ((m.access() & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0) continue;
                int n = m.parameterCount();
                for (int i = 0; i < n; i++) {
                    examined++;
                    List<AnnotationFacts> pa = i < m.parameterAnnotations().size()
                            ? m.parameterAnnotations().get(i)
                            : List.of();
                    if (present(pa, annotation, withValue) != require) {
                        add(
                                c,
                                c.binaryName() + "#" + m.member() + "[" + i + "]",
                                src,
                                m.firstLine(),
                                "parameter " + i + " of " + c.binaryName() + "#" + m.name() + " " + verb + simple
                                        + valueNote(withValue));
                    }
                }
            }
        }

        private void add(ClassFacts c, String fingerprint, @Nullable String src, int line, String detail) {
            Allow a = ForbidEvaluator.allowing(rule.allow(), c, ctx.module());
            if (a != null) {
                allowUsed.put(a, true);
                return;
            }
            sites.add(Observation.site(fingerprint, src, line, detail));
        }

        Evaluation finish(FactsIndex facts) {
            Map<String, Long> population = Map.of(
                    "elements", examined, "classes", (long) facts.classes().size());
            List<String> stale = new ArrayList<>();
            for (var e : allowUsed.entrySet()) {
                if (!e.getValue() && ForbidEvaluator.appliesHere(e.getKey(), facts, ctx))
                    stale.add(e.getKey().in());
            }
            if (!stale.isEmpty() && examined > 0) {
                return new Evaluation(
                        Outcome.STALE_ALLOW,
                        population,
                        sites,
                        "allow entries matched nothing: " + String.join(", ", stale));
            }
            return Evaluation.of(population, sites);
        }
    }

    private static @Nullable Allow allowingPackage(List<Allow> allow, String pkg, String module) {
        for (Allow a : allow) {
            String in = a.in();
            if (in.equals(module) || in.equals(pkg) || Rule.globMatches(in, module)) return a;
            if (in.endsWith(".**")) {
                String p = in.substring(0, in.length() - 3);
                if (pkg.equals(p) || pkg.startsWith(p + ".")) return a;
            }
            if (in.endsWith(".*") && pkg.equals(in.substring(0, in.length() - 2))) return a;
        }
        return null;
    }

    /** Present, and when {@code withValue} is given, carrying it as {@code value} or as {@code key=value}. */
    static boolean present(List<AnnotationFacts> annotations, String binaryName, @Nullable String withValue) {
        for (AnnotationFacts a : annotations) {
            if (!a.typeName().equals(binaryName)) continue;
            if (withValue == null) return true;
            int eq = withValue.indexOf('=');
            String key = eq < 0 ? "value" : withValue.substring(0, eq);
            String want = eq < 0 ? withValue : withValue.substring(eq + 1);
            if (a.values().getOrDefault(key, List.of()).contains(want)) return true;
        }
        return false;
    }

    private static String valueNote(@Nullable String withValue) {
        return withValue == null ? "" : " with " + withValue;
    }

    /** A class with at least one test-framework-annotated method, or nested inside one. */
    static boolean isTestClass(ClassFacts c) {
        for (MethodFacts m : c.methods()) {
            for (AnnotationFacts a : m.annotations()) if (TEST_METHOD_ANNOTATIONS.contains(a.typeName())) return true;
        }
        return false;
    }
}
