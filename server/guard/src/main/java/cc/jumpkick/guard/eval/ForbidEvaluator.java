// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.facts.AnnotationFacts;
import cc.jumpkick.guard.facts.CallSite;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.Descriptors;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.facts.FieldRef;
import cc.jumpkick.guard.facts.Fingerprints;
import cc.jumpkick.guard.facts.MethodFacts;
import cc.jumpkick.guard.rules.Allow;
import cc.jumpkick.guard.rules.Rule;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

/**
 * {@code forbid}: a type, member, package or call shape is banned outside an owner.
 *
 * <p>Evidence is the facts index: call sites and field refs (with the literal loaded right before an
 * invoke, for {@code args}), and type references for type and package signatures. Names resolve
 * against the module, its classpath and the JDK; a signature naming a class none of them knows is
 * red as {@code scanner-failed} — the typo an author made, not a clean tree. The owner must exist
 * and itself exhibit the banned shape, or the rule is {@code owner-missing}: an owner that no
 * longer uses the primitive is a rule pointing callers at nothing.
 */
final class ForbidEvaluator implements Evaluator {

    @Override
    public Evaluation evaluate(Rule rule, EvalContext ctx) {
        FactsIndex facts = ctx.facts();
        TypeHierarchy types = ctx.hierarchy();
        TomlTable t = rule.table();

        Set<String> bundled = new LinkedHashSet<>();
        List<Signature> signatures = new ArrayList<>();
        for (String raw : strings(t, "signatures")) {
            if (BundledSets.isSetReference(raw)) {
                var set = BundledSets.expand(raw);
                if (set.isEmpty())
                    return Evaluation.failed("unknown bundled set " + raw + "; sets are "
                            + String.join(", ", new TreeSet<>(BundledSets.NAMES)));
                for (String s : set.get()) {
                    signatures.add(Signature.parse(s));
                    bundled.add(s);
                }
            } else {
                signatures.add(Signature.parse(raw));
            }
        }
        List<String> unresolved = new ArrayList<>();
        List<Signature> live = new ArrayList<>();
        for (Signature s : signatures) {
            String owner = s.ownerToResolve();
            if (owner != null && !types.exists(owner)) {
                // A bundled set names frameworks the module may not use; a hand-typed one is a typo.
                if (!bundled.contains(s.raw())) unresolved.add(s.raw());
                continue;
            }
            live.add(s);
        }
        if (!unresolved.isEmpty()) {
            return Evaluation.failed(
                    "signature does not resolve on this module's classpath or JDK: " + String.join(", ", unresolved));
        }
        if (live.isEmpty())
            return Evaluation.notEvaluated(
                    "none of the bundled signatures resolve here (the framework is not on this module's classpath)");

        List<String> owners = strings(t, "owner");
        List<String> args = strings(t, "args");
        List<String> exceptAnnotated = strings(t, "except-annotated");
        Map<Allow, Boolean> allowUsed = new LinkedHashMap<>();
        for (Allow a : rule.allow()) allowUsed.put(a, false);

        long examined = 0;
        boolean ownerSeen = false;
        boolean ownerHasSite = false;
        List<Observation> sites = new ArrayList<>();
        for (ClassFacts c : facts.classList()) {
            boolean inOwner = inOwner(c, owners);
            if (inOwner) ownerSeen = true;
            boolean classExempt = annotated(c.annotations(), exceptAnnotated);
            for (MethodFacts m : c.methods()) {
                boolean exempt = classExempt || annotated(m.annotations(), exceptAnnotated);
                for (CallSite s : m.calls()) {
                    examined++;
                    if (matchCall(live, s, types) == null) continue;
                    if (!args.isEmpty() && !argMatches(args, s.literalBefore())) continue;
                    if (inOwner) {
                        ownerHasSite = true;
                        continue;
                    }
                    if (exempt) continue;
                    Allow a = allowing(rule.allow(), c, ctx.module());
                    if (a != null) {
                        allowUsed.put(a, true);
                        continue;
                    }
                    String fp = Fingerprints.normalise(c.binaryName() + "#" + m.member()) + " -> " + s.target();
                    sites.add(Observation.site(
                            fp,
                            source(ctx, c),
                            s.line(),
                            display(s) + " called outside "
                                    + (owners.isEmpty() ? "any owner" : String.join(", ", owners)) + " (from "
                                    + c.binaryName() + "#" + m.name() + ")"));
                }
                for (FieldRef r : m.fieldRefs()) {
                    examined++;
                    if (matchField(live, r, types) == null) continue;
                    if (inOwner) {
                        ownerHasSite = true;
                        continue;
                    }
                    if (exempt) continue;
                    Allow a = allowing(rule.allow(), c, ctx.module());
                    if (a != null) {
                        allowUsed.put(a, true);
                        continue;
                    }
                    String fp = Fingerprints.normalise(c.binaryName() + "#" + m.member()) + " -> " + r.target();
                    sites.add(Observation.site(
                            fp,
                            source(ctx, c),
                            r.line(),
                            Descriptors.binaryName(r.owner()) + "." + r.name() + " referenced outside the owner (from "
                                    + c.binaryName() + "#" + m.name() + ")"));
                }
            }
            if (args.isEmpty()) {
                for (String ref : c.typeRefs()) {
                    examined++;
                    if (matchType(live, ref, types) == null) continue;
                    if (inOwner) {
                        ownerHasSite = true;
                        continue;
                    }
                    if (classExempt) continue;
                    Allow a = allowing(rule.allow(), c, ctx.module());
                    if (a != null) {
                        allowUsed.put(a, true);
                        continue;
                    }
                    sites.add(Observation.site(
                            c.binaryName() + " -> " + Descriptors.binaryName(ref),
                            source(ctx, c),
                            0,
                            Descriptors.binaryName(ref) + " referenced from " + c.binaryName()));
                }
            }
        }
        Map<String, Long> population = Map.of("classes", (long) facts.classes().size(), "sites", examined);
        if (!owners.isEmpty() && !facts.classes().isEmpty()) {
            if (!ownerSeen) {
                // The owner may live in another module; only a module that should hold it is judged.
                if (ownerPackageIsHere(owners, facts))
                    return Evaluation.ownerMissing("owner " + String.join(", ", owners) + " is not in this module");
            } else if (!ownerHasSite) {
                return Evaluation.ownerMissing("owner " + String.join(", ", owners) + " no longer uses " + summary(live)
                        + " itself; the rule points callers at nothing");
            }
        }
        List<String> stale = new ArrayList<>();
        for (var e : allowUsed.entrySet())
            if (!e.getValue()) stale.add(e.getKey().in());
        if (!stale.isEmpty() && !facts.classes().isEmpty()) {
            return new Evaluation(
                    Outcome.STALE_ALLOW,
                    population,
                    sites,
                    "allow entries matched nothing: " + String.join(", ", stale));
        }
        return Evaluation.of(population, sites);
    }

    // ---- matching -----------------------------------------------------------------------------

    private static @Nullable Signature matchCall(List<Signature> sigs, CallSite s, TypeHierarchy types) {
        Set<String> ancestors = null;
        for (Signature sig : sigs) {
            if (sig.kind() == Signature.Kind.FIELD) continue;
            if (ancestors == null) ancestors = types.ancestors(s.owner());
            if (!sig.ownerMatches(s.owner(), ancestors)) continue;
            boolean memberKind = sig.kind() == Signature.Kind.METHOD || sig.kind() == Signature.Kind.MEMBER;
            if (!memberKind || sig.methodMatches(s.name(), s.desc())) return sig;
        }
        return null;
    }

    private static @Nullable Signature matchField(List<Signature> sigs, FieldRef r, TypeHierarchy types) {
        Set<String> ancestors = null;
        for (Signature sig : sigs) {
            if (sig.kind() == Signature.Kind.METHOD) continue;
            if (ancestors == null) ancestors = types.ancestors(r.owner());
            if (!sig.ownerMatches(r.owner(), ancestors)) continue;
            boolean memberKind = sig.kind() == Signature.Kind.FIELD || sig.kind() == Signature.Kind.MEMBER;
            if (!memberKind || sig.fieldMatches(r.name())) return sig;
        }
        return null;
    }

    private static @Nullable Signature matchType(List<Signature> sigs, String ref, TypeHierarchy types) {
        Set<String> ancestors = null;
        for (Signature sig : sigs) {
            switch (sig.kind()) {
                case TYPE -> {
                    // A type ban matches the type and its subtypes, never a supertype.
                    if (ancestors == null) ancestors = types.ancestors(ref);
                    if (sig.ownerMatches(ref, ancestors)) return sig;
                }
                case PACKAGE, PACKAGE_TREE -> {
                    if (sig.ownerMatches(ref, List.of())) return sig;
                }
                default -> {}
            }
        }
        return null;
    }

    private static boolean argMatches(List<String> args, @Nullable String literal) {
        if (literal == null) return false;
        for (String a : args) {
            if (a.equals(literal)) return true;
            if (a.contains("*") && Rule.globMatches(a, literal)) return true;
        }
        return false;
    }

    private static boolean annotated(List<AnnotationFacts> annotations, List<String> names) {
        if (names.isEmpty()) return false;
        for (AnnotationFacts a : annotations) if (names.contains(a.typeName())) return true;
        return false;
    }

    /** Owner globs: {@code a.b.Owner}, {@code a.b.Owner$Inner}, {@code a.b.*}, {@code a.b.**}. */
    static boolean inOwner(ClassFacts c, List<String> owners) {
        String name = c.binaryName();
        String outer = Descriptors.binaryName(Descriptors.outermost(c.name()));
        for (String o : owners) {
            if (o.endsWith(".**")) {
                String p = o.substring(0, o.length() - 3);
                if (c.packageName().equals(p) || c.packageName().startsWith(p + ".")) return true;
            } else if (o.endsWith(".*")) {
                if (c.packageName().equals(o.substring(0, o.length() - 2))) return true;
            } else if (name.equals(o) || outer.equals(o) || name.startsWith(o + "$")) {
                return true;
            }
        }
        return false;
    }

    /** Whether an owner's package is declared by this module — so its absence is this module's fault. */
    private static boolean ownerPackageIsHere(List<String> owners, FactsIndex facts) {
        Set<String> pkgs = facts.packages();
        for (String o : owners) {
            String stripped = o.endsWith(".**")
                    ? o.substring(0, o.length() - 3)
                    : o.endsWith(".*") ? o.substring(0, o.length() - 2) : o;
            int dot = stripped.lastIndexOf('.');
            String pkg = o.endsWith("*") ? stripped : dot < 0 ? "" : stripped.substring(0, dot);
            if (pkgs.contains(pkg)) return true;
        }
        return false;
    }

    private static @Nullable Allow allowing(List<Allow> allow, ClassFacts c, String module) {
        for (Allow a : allow) {
            String in = a.in();
            if (in.equals(module) || Rule.globMatches(in, module)) return a;
            if (in.equals(c.binaryName()) || Rule.globMatches(in, c.binaryName())) return a;
            if (in.endsWith(".**")) {
                String p = in.substring(0, in.length() - 3);
                if (c.packageName().equals(p) || c.packageName().startsWith(p + ".")) return a;
            }
            if (in.endsWith(".*") && c.packageName().equals(in.substring(0, in.length() - 2))) return a;
        }
        return null;
    }

    private static @Nullable String source(EvalContext ctx, ClassFacts c) {
        String file = c.sourceFile();
        if (file == null) return null;
        String pkgPath = c.packageName().replace('.', '/');
        String rel = (pkgPath.isEmpty() ? "" : pkgPath + "/") + file;
        return ctx.module().isEmpty() ? rel : ctx.module() + "/src/main/java/" + rel;
    }

    private static String display(CallSite s) {
        String literal = s.literalBefore();
        return Descriptors.binaryName(s.owner()) + "." + (s.name().equals("<init>") ? "new" : s.name()) + "("
                + (literal != null ? "\"" + literal + "\"" : "…") + ")";
    }

    private static String summary(List<Signature> live) {
        Set<String> out = new LinkedHashSet<>();
        for (Signature s : live) out.add(s.raw());
        return String.join(", ", out);
    }

    static List<String> strings(TomlTable t, String key) {
        List<String> out = new ArrayList<>();
        if (t.isString(key)) {
            out.add(String.valueOf(t.getString(key)));
        } else if (t.isArray(key)) {
            TomlArray a = t.getArray(key);
            if (a != null) for (int i = 0; i < a.size(); i++) out.add(String.valueOf(a.get(i)));
        }
        return out;
    }
}
