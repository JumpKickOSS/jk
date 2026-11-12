// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.config.ModuleOrder;
import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.Descriptors;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.facts.FieldFacts;
import cc.jumpkick.guard.facts.FieldRef;
import cc.jumpkick.guard.facts.MethodFacts;
import cc.jumpkick.guard.rules.Allow;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.model.Scope;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Opcodes;

/**
 * {@code statics}: a static field that holds mutable state — a collection or map, an atomic
 * reference, a reference array, a {@code holders} type, or a non-final field a method fills in
 * later — lives as long as the class does. In a long-lived process that is the process, so each
 * one must be produced by a {@code managed} initializer or carry an allow entry naming the field
 * ({@code pkg.Class#FIELD}) and saying why its lifetime is safe.
 *
 * <p>A field typed as an interface ({@code Map}, {@code List}) whose static initializer ends in an
 * unmodifiable factory ({@code List.of}, {@code Map.copyOf}, {@code Collections.unmodifiableMap},
 * {@code Stream.toList}) is a constant, not state. {@code reasons}, when set, are the prefixes an
 * allow entry's reason must start with, so the reasons stay a vocabulary a reader can sort by.
 * {@code reachable-from} limits the rule to the named modules and every module they depend on.
 */
final class StaticsEvaluator implements Evaluator {

    @Override
    public Evaluation evaluate(Rule rule, EvalContext ctx) {
        var t = rule.table();
        List<String> roots = ForbidEvaluator.strings(t, "reachable-from");
        if (!roots.isEmpty() && !reachable(ctx, roots).contains(ctx.module())) {
            return Evaluation.notHere("not reachable from " + String.join(", ", roots));
        }
        List<String> managed = ForbidEvaluator.strings(t, "managed");
        List<String> holders = ForbidEvaluator.strings(t, "holders");
        List<String> ignored = ForbidEvaluator.strings(t, "ignore-types");
        List<String> reasons = ForbidEvaluator.strings(t, "reasons");
        if (!reasons.isEmpty()) {
            for (Allow a : rule.allow()) {
                if (reasons.stream().noneMatch(r -> a.reason().startsWith(r))) {
                    return Evaluation.failed("allow `" + a.in() + "`: the reason must start with one of "
                            + String.join(
                                    ", ",
                                    reasons.stream().map(r -> "`" + r + "`").toList()));
                }
            }
        }
        FactsIndex facts = ctx.facts();
        Set<String> laterWrites = laterWrites(facts);
        Map<Allow, Boolean> allowUsed = new LinkedHashMap<>();
        for (Allow a : rule.allow()) allowUsed.put(a, false);
        List<Observation> sites = new ArrayList<>();
        long classes = 0;
        for (ClassFacts c : facts.classList()) {
            if (c.isPackageInfo() || c.hasFlag(Opcodes.ACC_SYNTHETIC)) continue;
            classes++;
            for (FieldFacts f : c.fields()) {
                if ((f.access() & Opcodes.ACC_STATIC) == 0 || (f.access() & Opcodes.ACC_SYNTHETIC) != 0) continue;
                if ((f.access() & Opcodes.ACC_ENUM) != 0) continue;
                String why = state(c, f, holders, ignored, laterWrites);
                if (why == null || managed(f.initializer(), managed)) continue;
                String member = c.binaryName() + "#" + f.name();
                Allow a = allowing(rule.allow(), member, c, ctx.module());
                if (a != null) {
                    allowUsed.put(a, true);
                    continue;
                }
                sites.add(Observation.site(
                        member,
                        ForbidEvaluator.source(ctx, c),
                        0,
                        member + " holds " + why + " for the process's life"));
            }
        }
        List<String> stale = new ArrayList<>();
        for (var e : allowUsed.entrySet()) {
            if (!e.getValue() && appliesHere(e.getKey(), facts, ctx))
                stale.add(e.getKey().in());
        }
        Map<String, Long> population = Map.of("classes", classes);
        if (!stale.isEmpty()) {
            return new Evaluation(
                    Outcome.STALE_ALLOW,
                    population,
                    sites,
                    "allow entries matched nothing: " + String.join(", ", stale));
        }
        return Evaluation.of(population, sites);
    }

    /** What mutable state {@code f} holds, or {@code null} when it holds none. */
    static @Nullable String state(
            ClassFacts c, FieldFacts f, List<String> holders, List<String> ignored, Set<String> laterWrites) {
        String desc = f.desc();
        if (desc.length() == 1) return null;
        if (desc.startsWith("[")) {
            return desc.charAt(1) == 'L' || desc.charAt(1) == '[' ? "a reference array" : null;
        }
        String type = desc.substring(1, desc.length() - 1);
        String binary = Descriptors.binaryName(type);
        if (matchesAny(ignored, binary) || IMMUTABLE_TYPES.contains(type) || type.equals(c.name())) return null;
        if (matchesAny(holders, binary)) return "a " + simple(binary);
        if (MUTABLE_CONCRETE.contains(type) || type.startsWith("java/util/concurrent/") && concurrentHolder(type)) {
            return "a " + simple(binary);
        }
        if (COLLECTION_INTERFACES.contains(type)) {
            String init = f.initializer();
            if ((f.access() & Opcodes.ACC_FINAL) != 0 && init != null && unmodifiable(init)) return null;
            return "a " + simple(binary);
        }
        boolean lazy = (f.access() & Opcodes.ACC_FINAL) == 0 && laterWrites.contains(c.name() + "#" + f.name());
        return lazy ? "a value filled in after class init" : null;
    }

    /**
     * {@code owner#name} of every static field a method other than a static initializer writes:
     * a non-final static that some method fills in later.
     */
    private static Set<String> laterWrites(FactsIndex facts) {
        Set<String> out = new HashSet<>();
        for (ClassFacts c : facts.classList()) {
            for (MethodFacts m : c.methods()) {
                if (m.name().equals("<clinit>")) continue;
                for (FieldRef r : m.fieldRefs()) if (r.write()) out.add(r.owner() + "#" + r.name());
            }
        }
        return out;
    }

    private static boolean managed(@Nullable String initializer, List<String> managed) {
        if (initializer == null) return false;
        int hash = initializer.indexOf('#');
        String spelled = Descriptors.binaryName(initializer.substring(0, hash)) + initializer.substring(hash);
        return matchesAny(managed, spelled);
    }

    /** Whether {@code init}, an {@code owner#name} in internal form, returns an unmodifiable collection. */
    static boolean unmodifiable(String init) {
        int hash = init.indexOf('#');
        String owner = init.substring(0, hash);
        String name = init.substring(hash + 1);
        return switch (owner) {
            case "java/util/List", "java/util/Set", "java/util/Map", "java/util/SortedSet", "java/util/SortedMap" ->
                name.equals("of") || name.equals("copyOf") || name.equals("ofEntries");
            case "java/util/Collections" ->
                name.startsWith("unmodifiable")
                        || name.startsWith("empty")
                        || name.startsWith("singleton")
                        || name.equals("nCopies");
            case "java/util/stream/Stream" -> name.equals("toList");
            default -> false;
        };
    }

    private static boolean concurrentHolder(String type) {
        String simple = type.substring(type.lastIndexOf('/') + 1);
        return simple.contains("Map")
                || simple.contains("Set")
                || simple.contains("List")
                || simple.contains("Queue")
                || simple.contains("Deque")
                || simple.equals("AtomicReference")
                || simple.equals("AtomicReferenceArray");
    }

    private static final Set<String> IMMUTABLE_TYPES =
            Set.of("java/lang/String", "java/lang/Class", "java/util/regex/Pattern");

    private static final Set<String> MUTABLE_CONCRETE = Set.of(
            "java/util/HashMap",
            "java/util/LinkedHashMap",
            "java/util/TreeMap",
            "java/util/EnumMap",
            "java/util/IdentityHashMap",
            "java/util/WeakHashMap",
            "java/util/Hashtable",
            "java/util/ArrayList",
            "java/util/LinkedList",
            "java/util/HashSet",
            "java/util/LinkedHashSet",
            "java/util/TreeSet",
            "java/util/EnumSet",
            "java/util/ArrayDeque",
            "java/util/PriorityQueue",
            "java/util/BitSet",
            "java/util/Vector",
            "java/util/Stack");

    private static final Set<String> COLLECTION_INTERFACES = Set.of(
            "java/util/Map",
            "java/util/SortedMap",
            "java/util/NavigableMap",
            "java/util/List",
            "java/util/Set",
            "java/util/SortedSet",
            "java/util/NavigableSet",
            "java/util/Collection",
            "java/util/Queue",
            "java/util/Deque");

    private static boolean matchesAny(List<String> globs, String value) {
        for (String g : globs) if (g.equals(value) || Rule.globMatches(g, value)) return true;
        return false;
    }

    private static String simple(String binary) {
        return binary.substring(Math.max(binary.lastIndexOf('.'), binary.lastIndexOf('$')) + 1);
    }

    /** The allow entry covering {@code member}: one naming the field, or its class, package or module. */
    private static @Nullable Allow allowing(List<Allow> allow, String member, ClassFacts c, String module) {
        for (Allow a : allow) if (a.in().equals(member)) return a;
        List<Allow> coarse = new ArrayList<>();
        for (Allow a : allow) if (a.in().indexOf('#') < 0) coarse.add(a);
        return ForbidEvaluator.allowing(coarse, c, module);
    }

    /** Whether {@code a} names something this lane judges: a field of one of its classes, or as {@code forbid} reads it. */
    private static boolean appliesHere(Allow a, FactsIndex facts, EvalContext ctx) {
        int hash = a.in().indexOf('#');
        if (hash < 0) return ForbidEvaluator.appliesHere(a, facts, ctx);
        String owner = a.in().substring(0, hash);
        for (ClassFacts c : facts.classList()) if (c.binaryName().equals(owner)) return true;
        return false;
    }

    /** {@code roots} and every module they depend on, as workspace-relative paths. */
    private static Set<String> reachable(EvalContext ctx, List<String> roots) {
        return ctx.memo("statics-reachable:" + roots, context -> {
            try {
                List<Path> modules = WorkspaceModules.of(context.root());
                WorkspaceModel model = WorkspaceModel.of(context.root(), modules);
                Set<String> seen = new TreeSet<>();
                ArrayDeque<String> todo = new ArrayDeque<>(roots);
                List<Scope> scopes = ModuleOrder.PRODUCTION_SCOPES;
                while (!todo.isEmpty()) {
                    String m = todo.pop();
                    if (seen.add(m)) todo.addAll(model.edgesFrom(m, scopes));
                }
                return seen;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }
}
