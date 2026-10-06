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
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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

        Signatures sigs = resolveSignatures(t, types);
        if (sigs.unknownSet() != null) {
            return Evaluation.failed("unknown bundled set " + sigs.unknownSet() + "; sets are "
                    + String.join(", ", new TreeSet<>(BundledSets.NAMES)));
        }
        if (sigs.live().isEmpty()) {
            if (sigs.unresolved().isEmpty()) {
                return Evaluation.notEvaluated(
                        "none of the bundled signatures resolve here (the framework is not on this module's classpath)");
            }
            return new Evaluation(
                            Outcome.CLEAN,
                            Map.of("classes", (long) facts.classes().size(), "sites", 0L),
                            List.of(),
                            "does not resolve on this module's classpath: " + String.join(", ", sigs.unresolved()))
                    .withBite(false);
        }

        Scan scan = new Scan(
                rule, ctx, sigs.live(), strings(t, "owner"), strings(t, "args"), strings(t, "except-annotated"));
        scan.indexed(facts, ctx.memo(SiteIndex.class, c -> SiteIndex.of(facts)));
        return scan.finish(facts);
    }

    /** The rule's signatures as this module resolves them; {@code unknownSet} names a set reference nothing bundles. */
    private record Signatures(
            List<Signature> live,
            List<String> unresolved,
            @Nullable String unknownSet) {}

    private static Signatures resolveSignatures(TomlTable t, TypeHierarchy types) {
        Set<String> bundled = new LinkedHashSet<>();
        List<Signature> signatures = new ArrayList<>();
        for (String raw : strings(t, "signatures")) {
            if (BundledSets.isSetReference(raw)) {
                var set = BundledSets.expand(raw);
                if (set.isEmpty()) return new Signatures(List.of(), List.of(), raw);
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
                // A type this module cannot see is a type no class here can reference: nothing to
                // judge in this lane. A signature no module resolves is a typo, and the tree lane
                // says so when no lane found bite evidence.
                if (!bundled.contains(s.raw())) unresolved.add(s.raw());
                continue;
            }
            live.add(s);
        }
        return new Signatures(live, unresolved, null);
    }

    /** One evaluation's walk over the classes: the rule's resolved shape, and the counts and sites it leaves. */
    private static final class Scan {
        private final Rule rule;
        private final EvalContext ctx;
        private final TypeHierarchy types;
        private final List<Signature> live;
        private final List<String> owners;
        private final List<String> args;
        private final List<String> exceptAnnotated;
        private final Map<Allow, Boolean> allowUsed = new LinkedHashMap<>();
        private long examined = 0;
        private long matched = 0;
        private boolean ownerSeen = false;
        private boolean ownerHasSite = false;
        private final List<Observation> sites = new ArrayList<>();

        Scan(
                Rule rule,
                EvalContext ctx,
                List<Signature> live,
                List<String> owners,
                List<String> args,
                List<String> exceptAnnotated) {
            this.rule = rule;
            this.ctx = ctx;
            this.types = ctx.hierarchy();
            this.live = live;
            this.owners = owners;
            this.args = args;
            this.exceptAnnotated = exceptAnnotated;
            for (Allow a : rule.allow()) allowUsed.put(a, false);
        }

        /**
         * Every site any signature could match, in the order a class-by-class walk meets them: a
         * member signature's name, a type or package signature's matching owners and referenced
         * types. Each candidate is then judged in full, so the index only narrows what is read.
         */
        void indexed(FactsIndex facts, SiteIndex index) {
            examined = index.callCount() + index.fieldCount() + (args.isEmpty() ? index.typeRefCount() : 0);
            if (!owners.isEmpty()) {
                for (ClassFacts c : facts.classList()) {
                    if (inOwner(c, owners)) {
                        ownerSeen = true;
                        break;
                    }
                }
            }
            boolean byOwner = false;
            Set<String> names = new LinkedHashSet<>();
            for (Signature sig : live) {
                boolean memberKind = sig.kind() == Signature.Kind.METHOD
                        || sig.kind() == Signature.Kind.MEMBER
                        || sig.kind() == Signature.Kind.FIELD;
                String member = sig.member();
                if (memberKind && member != null) names.add(member);
                else byOwner = true;
            }
            Map<Integer, SiteIndex.At> candidates = new TreeMap<>();
            for (String name : names) {
                for (SiteIndex.At at : index.callsNamed(name)) candidates.put(at.order(), at);
                for (SiteIndex.At at : index.fieldsNamed(name)) candidates.put(at.order(), at);
            }
            if (byOwner) {
                for (var e : index.callsByOwner().entrySet())
                    if (ownerCandidate(e.getKey())) for (SiteIndex.At at : e.getValue()) candidates.put(at.order(), at);
                for (var e : index.fieldsByOwner().entrySet())
                    if (ownerCandidate(e.getKey())) for (SiteIndex.At at : e.getValue()) candidates.put(at.order(), at);
                if (args.isEmpty()) {
                    for (var e : index.typeRefsByType().entrySet())
                        if (matchType(live, e.getKey(), types) != null)
                            for (SiteIndex.At at : e.getValue()) candidates.put(at.order(), at);
                }
            }
            for (SiteIndex.At at : candidates.values()) {
                ClassFacts c = at.clazz();
                boolean inOwner = inOwner(c, owners);
                boolean classExempt = annotated(c.annotations(), exceptAnnotated);
                String self = Descriptors.outermost(c.name());
                MethodFacts m = at.method();
                boolean exempt = classExempt || (m != null && annotated(m.annotations(), exceptAnnotated));
                if (at.call() != null && m != null) call(c, m, at.call(), inOwner, exempt, self);
                else if (at.field() != null && m != null) field(c, m, at.field(), inOwner, exempt, self);
                else if (at.typeRef() != null) typeRef(c, at.typeRef(), inOwner, classExempt, self);
            }
        }

        /** Whether a type or package signature could match a site on {@code owner}. */
        private boolean ownerCandidate(String owner) {
            for (Signature sig : live) {
                switch (sig.kind()) {
                    case TYPE, PACKAGE, PACKAGE_TREE -> {
                        Iterable<String> ancestors = sig.ownerToResolve() == null ? List.of() : types.ancestors(owner);
                        if (sig.ownerMatches(owner, ancestors)) return true;
                    }
                    default -> {}
                }
            }
            return false;
        }

        private void call(ClassFacts c, MethodFacts m, CallSite s, boolean inOwner, boolean exempt, String self) {
            if (matchCall(live, s, types) == null) return;
            if (!args.isEmpty() && !argMatches(args, s)) return;
            matched++;
            if (inOwner) {
                ownerHasSite = true;
                return;
            }
            if (Descriptors.outermost(s.owner()).equals(self)) return; // a class is never outside itself
            if (exempt || allowed(c)) return;
            String fp = Fingerprints.normalise(c.binaryName() + "#" + m.member()) + " -> " + s.target();
            sites.add(Observation.site(
                    fp,
                    source(ctx, c),
                    s.line(),
                    display(s) + " called outside " + (owners.isEmpty() ? "any owner" : String.join(", ", owners))
                            + " (from " + c.binaryName() + "#" + m.name() + ")"));
        }

        private void field(ClassFacts c, MethodFacts m, FieldRef r, boolean inOwner, boolean exempt, String self) {
            if (matchField(live, r, types) == null) return;
            matched++;
            if (inOwner) {
                ownerHasSite = true;
                return;
            }
            if (Descriptors.outermost(r.owner()).equals(self)) return;
            if (exempt || allowed(c)) return;
            String fp = Fingerprints.normalise(c.binaryName() + "#" + m.member()) + " -> " + r.target();
            sites.add(Observation.site(
                    fp,
                    source(ctx, c),
                    r.line(),
                    Descriptors.binaryName(r.owner()) + "." + r.name() + " referenced outside the owner (from "
                            + c.binaryName() + "#" + m.name() + ")"));
        }

        private void typeRef(ClassFacts c, String ref, boolean inOwner, boolean classExempt, String self) {
            if (matchType(live, ref, types) == null) return;
            matched++;
            if (inOwner) {
                ownerHasSite = true;
                return;
            }
            if (Descriptors.outermost(ref).equals(self) || classExempt || allowed(c)) return;
            sites.add(Observation.site(
                    c.binaryName() + " -> " + Descriptors.binaryName(ref),
                    source(ctx, c),
                    0,
                    Descriptors.binaryName(ref) + " referenced from " + c.binaryName()));
        }

        /** An allow entry covering {@code c} is marked used and the site is not recorded. */
        private boolean allowed(ClassFacts c) {
            Allow a = allowing(rule.allow(), c, ctx.module());
            if (a == null) return false;
            allowUsed.put(a, true);
            return true;
        }

        Evaluation finish(FactsIndex facts) {
            Map<String, Long> population =
                    Map.of("classes", (long) facts.classes().size(), "sites", examined);
            if (!owners.isEmpty() && !facts.classes().isEmpty()) {
                if (!ownerSeen) {
                    // The owner may live in another module; only a module that should hold it is judged.
                    if (ownerPackageIsHere(owners, facts))
                        return Evaluation.ownerMissing("owner " + String.join(", ", owners) + " is not in this module");
                } else if (!ownerHasSite) {
                    return Evaluation.ownerMissing("owner " + String.join(", ", owners) + " no longer uses "
                            + summary(live) + " itself; the rule points callers at nothing");
                }
            }
            List<String> stale = new ArrayList<>();
            for (var e : allowUsed.entrySet())
                if (!e.getValue() && appliesHere(e.getKey(), facts, ctx))
                    stale.add(e.getKey().in());
            if (!stale.isEmpty() && !facts.classes().isEmpty()) {
                return new Evaluation(
                        Outcome.STALE_ALLOW,
                        population,
                        sites,
                        "allow entries matched nothing: " + String.join(", ", stale));
            }
            // An allowed or exempt match is still the rule seeing its shape: evidence it can bite.
            return Evaluation.of(population, sites).withBite(ownerHasSite || matched > 0);
        }
    }

    /**
     * One facts index's sites filed for lookup: calls and field references by member name and by
     * owner, type references by type. Each carries its position in a class-by-class walk (a class's
     * methods' calls then field references, then its type references), so a lookup visits sites in
     * that order. Built once per lane run and shared by its forbid rules.
     */
    record SiteIndex(
            Map<String, List<At>> callsByName,
            Map<String, List<At>> fieldsByName,
            Map<String, List<At>> callsByOwner,
            Map<String, List<At>> fieldsByOwner,
            Map<String, List<At>> typeRefsByType,
            long callCount,
            long fieldCount,
            long typeRefCount) {

        /** One site: exactly one of {@code call}, {@code field}, {@code typeRef}; {@code method} is null for a type reference. */
        record At(
                int order,
                ClassFacts clazz,
                @Nullable MethodFacts method,
                @Nullable CallSite call,
                @Nullable FieldRef field,
                @Nullable String typeRef) {}

        static SiteIndex of(FactsIndex facts) {
            Map<String, List<At>> callsByName = new HashMap<>();
            Map<String, List<At>> fieldsByName = new HashMap<>();
            Map<String, List<At>> callsByOwner = new HashMap<>();
            Map<String, List<At>> fieldsByOwner = new HashMap<>();
            Map<String, List<At>> typeRefsByType = new HashMap<>();
            long calls = 0;
            long fields = 0;
            long typeRefs = 0;
            int order = 0;
            for (ClassFacts c : facts.classList()) {
                for (MethodFacts m : c.methods()) {
                    for (CallSite s : m.calls()) {
                        At at = new At(order++, c, m, s, null, null);
                        callsByName
                                .computeIfAbsent(s.name(), k -> new ArrayList<>())
                                .add(at);
                        callsByOwner
                                .computeIfAbsent(s.owner(), k -> new ArrayList<>())
                                .add(at);
                        calls++;
                    }
                    for (FieldRef r : m.fieldRefs()) {
                        At at = new At(order++, c, m, null, r, null);
                        fieldsByName
                                .computeIfAbsent(r.name(), k -> new ArrayList<>())
                                .add(at);
                        fieldsByOwner
                                .computeIfAbsent(r.owner(), k -> new ArrayList<>())
                                .add(at);
                        fields++;
                    }
                }
                for (String ref : c.typeRefs()) {
                    typeRefsByType
                            .computeIfAbsent(ref, k -> new ArrayList<>())
                            .add(new At(order++, c, null, null, null, ref));
                    typeRefs++;
                }
            }
            return new SiteIndex(
                    callsByName, fieldsByName, callsByOwner, fieldsByOwner, typeRefsByType, calls, fields, typeRefs);
        }

        List<At> callsNamed(String name) {
            return callsByName.getOrDefault(name, List.of());
        }

        List<At> fieldsNamed(String name) {
            return fieldsByName.getOrDefault(name, List.of());
        }
    }

    // ---- matching -----------------------------------------------------------------------------

    // The member is matched before the owner: a name comparison is cheap and rejects nearly every
    // site, while the owner side walks the hierarchy.
    private static @Nullable Signature matchCall(List<Signature> sigs, CallSite s, TypeHierarchy types) {
        Set<String> ancestors = Set.of();
        for (Signature sig : sigs) {
            if (sig.kind() == Signature.Kind.FIELD) continue;
            boolean memberKind = sig.kind() == Signature.Kind.METHOD || sig.kind() == Signature.Kind.MEMBER;
            if (memberKind && !sig.methodMatches(s.name(), s.desc())) continue;
            if (sig.ownerToResolve() != null && ancestors.isEmpty()) ancestors = types.ancestors(s.owner());
            if (sig.ownerMatches(s.owner(), ancestors)) return sig;
        }
        return null;
    }

    private static @Nullable Signature matchField(List<Signature> sigs, FieldRef r, TypeHierarchy types) {
        Set<String> ancestors = Set.of();
        for (Signature sig : sigs) {
            if (sig.kind() == Signature.Kind.METHOD) continue;
            boolean memberKind = sig.kind() == Signature.Kind.FIELD || sig.kind() == Signature.Kind.MEMBER;
            if (memberKind && !sig.fieldMatches(r.name())) continue;
            if (sig.ownerToResolve() != null && ancestors.isEmpty()) ancestors = types.ancestors(r.owner());
            if (sig.ownerMatches(r.owner(), ancestors)) return sig;
        }
        return null;
    }

    private static @Nullable Signature matchType(List<Signature> sigs, String ref, TypeHierarchy types) {
        Set<String> ancestors = Set.of();
        for (Signature sig : sigs) {
            switch (sig.kind()) {
                case TYPE -> {
                    // A type ban matches the type and its subtypes, never a supertype.
                    if (ancestors.isEmpty()) ancestors = types.ancestors(ref);
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

    /** Any literal in the invoke's argument window matches an {@code args} entry (exact or glob). */
    private static boolean argMatches(List<String> args, CallSite s) {
        for (String literal : s.literals()) {
            for (String a : args) {
                if (a.equals(literal)) return true;
                if (a.contains("*") && Rule.globMatches(a, literal)) return true;
            }
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

    /**
     * Whether an allow entry could have matched in this module at all: it names this module, a
     * class the facts hold, or a package the facts hold. One that names another module's class is
     * not stale here — stale is judged where the exemption lives.
     */
    /**
     * Whether this module's verdict answers for {@code a} being unused. An allow that names the
     * module does; a class or package glob does only when every class it names lives here — a
     * glob that also reaches into another module may be earning its keep there, and a module
     * lane sees one module at a time.
     */
    static boolean appliesHere(Allow a, FactsIndex facts, EvalContext ctx) {
        String in = a.in();
        String module = ctx.module();
        if (in.equals(module) || Rule.globMatches(in, module)) return true;
        boolean here = false;
        for (ClassFacts c : facts.classList()) {
            if (namesClass(in, c.binaryName(), c.packageName())) {
                here = true;
                break;
            }
        }
        return here && !namesAClassElsewhere(in, ctx);
    }

    private static boolean namesClass(String in, String binaryName, String packageName) {
        if (in.equals(binaryName) || Rule.globMatches(in, binaryName)) return true;
        if (in.endsWith(".**")) {
            String p = in.substring(0, in.length() - 3);
            return packageName.equals(p) || packageName.startsWith(p + ".");
        }
        return in.endsWith(".*") && packageName.equals(in.substring(0, in.length() - 2));
    }

    private static boolean namesAClassElsewhere(String in, EvalContext ctx) {
        List<Path> modules;
        try {
            modules = WorkspaceModules.of(ctx.root());
        } catch (IOException unreadable) {
            return false;
        }
        for (var e : WorkspaceModel.classModules(ctx.root(), modules).entrySet()) {
            if (e.getValue().equals(ctx.module())) continue;
            String binary = e.getKey().replace('/', '.');
            int dot = binary.lastIndexOf('.');
            String pkg = dot < 0 ? "" : binary.substring(0, dot);
            if (namesClass(in, binary, pkg)) return true;
        }
        return false;
    }

    /** The allow entry covering {@code c}: by module, class glob, or {@code pkg.*} / {@code pkg.**}. */
    static @Nullable Allow allowing(List<Allow> allow, ClassFacts c, String module) {
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

    /** The source path a class compiled from, when its {@code SourceFile} attribute survived. */
    static @Nullable String source(EvalContext ctx, ClassFacts c) {
        return SourcePaths.of(ctx, c);
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
