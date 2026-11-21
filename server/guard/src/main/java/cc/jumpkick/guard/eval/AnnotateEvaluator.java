// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.ManifestNullness;
import cc.jumpkick.config.UserConfigParseException;
import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.facts.AnnotationFacts;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.Descriptors;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.facts.FieldFacts;
import cc.jumpkick.guard.facts.MethodFacts;
import cc.jumpkick.guard.facts.TestClassFacts;
import cc.jumpkick.guard.rules.Allow;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.NullnessConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    /** The memo key of a lane run's test-class judgement over its test facts. */
    private static final Object TEST_CLASSES = new Object();

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
        // on the classpath: a library pack's Java-only rule passes a module of Scala classes alone.
        // So does a package scope of generated code alone: its sources are a generator's, not the user's.
        Map<String, @Nullable ClassFacts> scope =
                on.equals("package") ? packageScope(facts, matching, ctx, annotation) : Map.of();
        if ((matching.predicate() != null && facts.classList().stream().noneMatch(matching::test))
                || (on.equals("package") && scope.isEmpty())) {
            return Evaluation.of(
                    Map.of("elements", 0L, "classes", (long) facts.classes().size()), List.of());
        }

        String internal = Descriptors.internalName(annotation);
        var retention = types.retention(internal);
        // A package every allow covers needs no annotation to resolve: a required one is absent
        // from all of them alike, and each is allowed.
        if (retention.isEmpty()
                && !(on.equals("package") && require && allAllowed(rule, scope.keySet(), ctx.module()))) {
            return Evaluation.failed(unresolved(annotation));
        }
        if (retention.isPresent() && retention.get().equals("SOURCE")) {
            return Evaluation.failed("annotation " + annotation
                    + " has retention SOURCE: this fact is not in the class file, so no bytecode rule can see it");
        }

        Scan scan = new Scan(rule, ctx, require, annotation, withValue);
        if (on.equals("package")) {
            scan.packages(scope);
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

        void packages(Map<String, @Nullable ClassFacts> infos) {
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
                if (on.equals("test-class") && !testClasses(ctx, facts).isTestClass(c)) continue;
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

    /** The annotation the library pack's {@code null-marked-packages} requires, which {@code [nullness]} brings. */
    static final String NULL_MARKED = "org.jspecify.annotations.NullMarked";

    private static final Object NULLNESS_EXCLUDE = new Object();

    /**
     * The packages an {@code on = "package"} rule judges, each with its package-info (null when it
     * has none): a package is in scope when a class of it matches, unless its directory is under
     * the module's generated-source trees and none of its source roots, or, for {@link
     * #NULL_MARKED}, {@code [nullness] exclude} names it. Avro, JAXB, jOOQ, DGS and protoc write
     * whole packages the user does not own; JAXB writes their package-info too.
     */
    static Map<String, @Nullable ClassFacts> packageScope(
            FactsIndex facts, ClassPredicates.Compiled matching, EvalContext ctx, String annotation) {
        Map<String, @Nullable ClassFacts> infos = new TreeMap<>();
        Map<String, ClassFacts> byPackage = new TreeMap<>();
        for (ClassFacts c : facts.classList()) {
            if (c.isPackageInfo()) byPackage.put(c.packageName(), c);
            else if (matching.test(c)) infos.putIfAbsent(c.packageName(), null);
        }
        Path moduleDir = ctx.moduleDir();
        List<String> excluded = annotation.equals(NULL_MARKED) ? nullnessExclude(ctx) : List.of();
        Map<String, @Nullable ClassFacts> out = new TreeMap<>();
        for (String pkg : infos.keySet()) {
            if (moduleDir != null && SplitPackageEvaluator.generatedOnly(moduleDir, pkg)) continue;
            if (underAny(pkg, excluded)) continue;
            out.put(pkg, byPackage.get(pkg));
        }
        return out;
    }

    /** The module's {@code [nullness] exclude}, read once per lane run; empty without one or when the manifest does not parse. */
    private static List<String> nullnessExclude(EvalContext ctx) {
        return ctx.memo(NULLNESS_EXCLUDE, c -> {
            Path dir = c.moduleDir();
            if (dir == null) return List.<String>of();
            Path manifest = ManifestPaths.manifestIn(dir);
            if (!Files.isRegularFile(manifest)) return List.<String>of();
            try {
                NullnessConfig nullness = JkBuildParser.parse(manifest).build().nullness();
                return nullness == null ? List.<String>of() : nullness.exclude();
            } catch (UserConfigParseException unparsed) {
                throw unparsed; // config.toml's own error, with its position — never this site's fallback
            } catch (IOException | RuntimeException unreadable) {
                // The manifest's own check reports it; this rule then judges every package.
                return List.<String>of();
            }
        });
    }

    /** {@code pkg} is one of {@code roots} or a subpackage of one, as NullAway reads {@code exclude}. */
    static boolean underAny(String pkg, List<String> roots) {
        for (String root : roots) {
            if (pkg.equals(root) || pkg.startsWith(root + ".")) return true;
        }
        return false;
    }

    private static boolean allAllowed(Rule rule, Iterable<String> packages, String module) {
        for (String pkg : packages) {
            if (allowingPackage(rule.allow(), pkg, module) == null) return false;
        }
        return true;
    }

    /** The failure for an annotation no classpath entry declares, naming what puts it there. */
    static String unresolved(String annotation) {
        String fix = annotation.startsWith("org.jspecify.")
                ? "[nullness] in jk.toml brings it (" + ManifestNullness.JSPECIFY
                        + "), or add org.jspecify:jspecify to [dependencies]"
                : "add the library that declares it to [dependencies]";
        return "annotation " + annotation + " does not resolve on this module's classpath or JDK; " + fix;
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

    /**
     * Which classes of the test facts are test classes: supertypes and annotations read from the
     * test facts, the main facts, then the classpath.
     */
    static TestClassFacts testClasses(EvalContext ctx, FactsIndex test) {
        return ctx.memo(TEST_CLASSES, c -> {
            TypeHierarchy types = c.hierarchy();
            return new TestClassFacts(List.of(test, c.facts()), types::classFacts);
        });
    }
}
