// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.house;

import cc.jumpkick.guard.api.CallSite;
import cc.jumpkick.guard.api.Facts;
import cc.jumpkick.guard.api.Fixture;
import cc.jumpkick.guard.api.Guard;
import cc.jumpkick.guard.api.GuardSuite;
import cc.jumpkick.guard.api.Origin;
import cc.jumpkick.guard.api.Scope;
import cc.jumpkick.guard.api.Sig;
import cc.jumpkick.guard.api.Violations;
import cc.jumpkick.guard.facts.AnnotationFacts;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.Descriptors;
import cc.jumpkick.guard.facts.MethodFacts;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A forecast key hashes what the build key it predicts hashes: every key is derived by one method
 * annotated {@code @KeyOwner}, and both the build and {@code jk explain} call that method. Read from
 * the module's bytecode, so a key built in a return statement, an argument or a lambda is a site
 * like any other, and the owner tables are the annotations themselves.
 *
 * <p>A caller is <b>forecast-side</b> when its outermost class's simple name contains {@code
 * Forecast}, its method's name contains {@code forecast} or {@code predict} (a lambda carries its
 * enclosing method's name), or its descriptor names {@code ForecastScope}, {@code StepForecast.Scope} or {@code
 * TaskForecast.Task} (a step's predictor). Every other caller is <b>build-side</b>. A call from the
 * owner's own class is followed to that method's callers, so an owner's forecast entry point in
 * the same class ({@code PackagingKeys.assemblyActionCached}) counts for the side that calls it;
 * so is a call from another {@code @KeyOwner}, so an owner a shared input owner calls ({@code
 * CompileInputs} over a request builder) counts for the sides that call the input owner.
 */
@GuardSuite(scope = Scope.MODULE)
final class ForecastKeyRules {

    static final String KEY_OWNER = "cc.jumpkick.task.KeyOwner";

    /** The calls that compose a key or the facts it hashes: each must sit in a {@code @KeyOwner}. */
    static final List<Sig> KEY_FAMILIES = List.of(
            Sig.of("cc.jumpkick.task.ActionKey#forArtifact"),
            Sig.of("cc.jumpkick.task.ActionKey#forJavac"),
            Sig.of("cc.jumpkick.task.ActionKey#forKotlinc"),
            Sig.of("cc.jumpkick.task.ActionKey#forGroovyc"),
            Sig.of("cc.jumpkick.compile.CompileRequest#builder"),
            Sig.of("cc.jumpkick.compile.KotlincRequest#builder"),
            Sig.of("cc.jumpkick.compile.GroovycRequest#builder"),
            Sig.of("cc.jumpkick.task.TestStamp#computeKey"),
            Sig.of("cc.jumpkick.task.FreshnessStamp#isFresh"),
            Sig.of("cc.jumpkick.task.FreshnessStamp#freshStamp"),
            Sig.of("cc.jumpkick.task.FreshnessStamp#write"),
            Sig.of("cc.jumpkick.task.FreshnessStamp#optionsDigest"));

    /** The classes that declare the key functions: calls among their own overloads are not sites. */
    private static final Set<String> FAMILY_CLASSES = Set.of(
            "cc/jumpkick/task/ActionKey",
            "cc/jumpkick/compile/CompileRequest",
            "cc/jumpkick/compile/KotlincRequest",
            "cc/jumpkick/compile/GroovycRequest",
            "cc/jumpkick/task/TestStamp",
            "cc/jumpkick/task/FreshnessStamp");

    private static final String TASK = "cc/jumpkick/run/Task";
    private static final String STEP_FORECAST = "cc/jumpkick/run/StepForecast";

    enum Side {
        BUILD,
        FORECAST
    }

    /** One method of the module, by {@code internalName#name+desc}. */
    record Node(ClassFacts cls, MethodFacts method) {
        String key() {
            return cls.name() + "#" + method.member();
        }

        String outer() {
            return Descriptors.outermost(cls.name());
        }

        String label() {
            String outer = outer();
            return outer.substring(outer.lastIndexOf('/') + 1) + cls.name().substring(outer.length()) + "#"
                    + method.name();
        }

        /** The call that names this method, as the site a violation about the method points at. */
        CallSite site() {
            return new CallSite(
                    new Origin(cls, method),
                    new cc.jumpkick.guard.facts.CallSite(
                            cls.name(), method.name(), method.desc(), method.firstLine(), null, 1));
        }

        @Nullable
        AnnotationFacts keyOwner() {
            for (AnnotationFacts a : method.annotations()) if (a.typeName().equals(KEY_OWNER)) return a;
            return null;
        }
    }

    /** A {@code @KeyOwner} method and what it declares. */
    record Owner(Node node, List<String> steps, String unpaired, boolean forecastsStep) {
        boolean paired() {
            return unpaired.isBlank();
        }
    }

    /** The module's methods, who calls each, and its {@code @KeyOwner}s. */
    static final class Graph {
        final Map<String, Node> nodes = new HashMap<>();
        final Map<String, List<Node>> byName = new HashMap<>(); // internalName#name → overloads
        final Map<String, List<Node>> callers = new HashMap<>();
        final List<Owner> owners = new ArrayList<>();

        Graph(Facts facts) {
            for (ClassFacts c : facts.classes()) {
                for (MethodFacts m : c.methods()) {
                    Node n = new Node(c, m);
                    nodes.put(n.key(), n);
                    byName.computeIfAbsent(c.name() + "#" + m.name(), k -> new ArrayList<>())
                            .add(n);
                    AnnotationFacts a = n.keyOwner();
                    if (a != null) {
                        List<String> unpaired = a.values().getOrDefault("unpaired", List.of());
                        List<String> claims = a.values().getOrDefault("forecastsStep", List.of());
                        owners.add(new Owner(
                                n,
                                a.values().getOrDefault("step", List.of()),
                                unpaired.isEmpty() ? "" : unpaired.getFirst(),
                                !claims.isEmpty() && Boolean.parseBoolean(claims.getFirst())));
                    }
                }
            }
            for (Node n : nodes.values())
                for (cc.jumpkick.guard.facts.CallSite call : n.method().calls())
                    callers.computeIfAbsent(call.owner() + "#" + call.name() + call.desc(), k -> new ArrayList<>())
                            .add(n);
        }

        @Nullable
        Node node(ClassFacts cls, MethodFacts m) {
            return nodes.get(cls.name() + "#" + m.member());
        }

        List<Node> callersOf(Node n) {
            return callers.getOrDefault(n.key(), List.of());
        }

        /** {@code lambda$name$N}'s enclosing method {@code name}: every overload of it in the class. */
        List<Node> enclosing(Node n) {
            String name = n.method().name();
            if (!name.startsWith("lambda$")) return List.of();
            int end = name.lastIndexOf('$');
            if (end <= "lambda$".length()) return List.of();
            return byName.getOrDefault(n.cls().name() + "#" + name.substring("lambda$".length(), end), List.of());
        }

        /**
         * The owners a key site in {@code n} belongs to: {@code n} itself when it is one; the
         * enclosing method of a lambda; and for a private helper every caller in its own class,
         * provided each of them is owned. Empty when the site is unowned.
         */
        Set<Node> owners(Node n) {
            return owners(n, new LinkedHashSet<>());
        }

        private Set<Node> owners(Node n, Set<String> path) {
            if (n.keyOwner() != null) return Set.of(n);
            if (!path.add(n.key())) return Set.of(); // a cycle of helpers reaches no owner
            try {
                Set<Node> out = new LinkedHashSet<>();
                List<Node> enclosing = enclosing(n);
                if (!enclosing.isEmpty()) {
                    for (Node e : enclosing) out.addAll(owners(e, path));
                    return out;
                }
                if (!Modifier.isPrivate(n.method().access())) return Set.of();
                List<Node> in = callersOf(n);
                if (in.isEmpty()) return Set.of();
                for (Node c : in) {
                    if (!c.outer().equals(n.outer())) return Set.of();
                    Set<Node> up = owners(c, path);
                    if (up.isEmpty()) return Set.of();
                    out.addAll(up);
                }
                return out;
            } finally {
                path.remove(n.key());
            }
        }

        /** The sides {@code owner} is reached from, following calls inside its own class to their callers. */
        Set<Side> sides(Node owner) {
            Set<Side> out = EnumSet.noneOf(Side.class);
            Set<String> seen = new LinkedHashSet<>();
            seen.add(owner.key());
            for (Node c : callersOf(owner)) side(c, owner.outer(), seen, out);
            return out;
        }

        private void side(Node caller, String ownerClass, Set<String> seen, Set<Side> out) {
            if (!seen.add(caller.key())) return;
            if (forecastSide(caller)) {
                out.add(Side.FORECAST);
                return;
            }
            List<Node> enclosing = enclosing(caller);
            if (!enclosing.isEmpty()) {
                for (Node e : enclosing) side(e, ownerClass, seen, out);
                return;
            }
            if (!caller.outer().equals(ownerClass)) {
                if (caller.keyOwner() != null) {
                    for (Node up : callersOf(caller)) side(up, caller.outer(), seen, out);
                    return;
                }
                out.add(Side.BUILD);
                return;
            }
            for (Node up : callersOf(caller)) side(up, ownerClass, seen, out);
        }

        /** Whether {@code n} calls {@code owner#name}. */
        static boolean calls(Node n, String owner, String name) {
            for (cc.jumpkick.guard.facts.CallSite c : n.method().calls())
                if (c.owner().equals(owner) && c.name().equals(name)) return true;
            return false;
        }

        /** The task names {@code n} hands {@code Task.builder} as literals. */
        static List<String> builtSteps(Node n) {
            for (cc.jumpkick.guard.facts.CallSite c : n.method().calls())
                if (c.owner().equals(TASK) && c.name().equals("builder")) return c.literals();
            return List.of();
        }
    }

    /** See the class comment: the forecast's own classes and methods, and a step's predictors. */
    static boolean forecastSide(Node n) {
        String outer = n.outer();
        if (outer.substring(outer.lastIndexOf('/') + 1).contains("Forecast")) return true;
        String name = n.method().name().toLowerCase(Locale.ROOT);
        if (name.contains("forecast") || name.contains("predict")) return true;
        String desc = n.method().desc();
        return desc.contains("/ForecastScope;")
                || desc.contains("/StepForecast$Scope;")
                || desc.contains("/TaskForecast$Task;");
    }

    static boolean stepMatches(String declared, String built) {
        return declared.endsWith("*")
                ? built.startsWith(declared.substring(0, declared.length() - 1))
                : declared.equals(built);
    }

    /** The factories that hand {@code Task.builder} a name {@code step} matches, only {@code outer}'s when given. */
    private static List<Node> buildersOf(List<Node> factories, String step, @Nullable String outer) {
        List<Node> out = new ArrayList<>();
        for (Node f : factories) {
            if (outer != null && !f.outer().equals(outer)) continue;
            for (String name : Graph.builtSteps(f)) if (stepMatches(step, name) && !out.contains(f)) out.add(f);
        }
        return out;
    }

    @Guard(
            id = "forecast-key-parity",
            why =
                    "a key composed outside its owner is a second body the forecast does not share: it reports a phantom rebuild or blesses a stale artifact",
            instead =
                    "derive the key in a method annotated @KeyOwner(step = ...) that the build and the forecast both call, or declare it unpaired and why")
    @Fixture("server/engine/guard-fixtures/forecast-key-parity")
    void forecastKeyParity(Facts facts, Violations v) {
        Graph g = new Graph(facts);
        int sites = 0;
        for (Sig family : KEY_FAMILIES) {
            for (CallSite site : facts.calls(family)) {
                // The key functions' own bodies (an overload delegating to another) are not sites.
                if (FAMILY_CLASSES.contains(
                        Descriptors.outermost(site.origin().cls().name()))) continue;
                sites++;
                MethodFacts m = site.origin().member();
                Node n = m == null ? null : g.node(site.origin().cls(), m);
                if (n == null || g.owners(n).isEmpty())
                    v.add(
                            site,
                            site.target() + " outside any @KeyOwner method: a key nobody has decided how to forecast");
            }
        }
        v.population(sites);
    }

    @Guard(
            id = "forecast-key-owner-callers",
            why =
                    "a key owner only one side calls is not shared: the other side derives the key from a body of its own",
            instead =
                    "call the @KeyOwner from both the build step and its forecast, or declare it unpaired with why the forecast does not")
    @Fixture("server/engine/guard-fixtures/forecast-key-owner-callers")
    void forecastKeyOwnerCallers(Facts facts, Violations v) {
        Graph g = new Graph(facts);
        for (Owner o : g.owners) {
            if (o.steps().isEmpty() && o.paired()) {
                v.add(o.node().site(), "@KeyOwner " + o.node().label() + " names no step");
                continue;
            }
            Set<Side> sides = g.sides(o.node());
            if (o.paired()) {
                if (!sides.contains(Side.BUILD))
                    v.add(
                            o.node().site(),
                            "@KeyOwner " + o.node().label() + " " + o.steps()
                                    + " has no build-side caller: the build derives this key elsewhere");
                if (!sides.contains(Side.FORECAST))
                    v.add(
                            o.node().site(),
                            "@KeyOwner " + o.node().label() + " " + o.steps()
                                    + " has no forecast-side caller: the forecast derives this key elsewhere, or not at all");
            } else if (sides.containsAll(EnumSet.allOf(Side.class))) {
                v.add(
                        o.node().site(),
                        "@KeyOwner " + o.node().label() + " is declared unpaired, but the build and the forecast both"
                                + " call it; drop unpaired (\"" + o.unpaired() + "\")");
            }
        }
        v.population(g.owners.size());
    }

    @Guard(
            id = "forecast-key-unpaired-claim",
            why =
                    "an unpaired key that misstates whether the forecast names its step hides a step explain prices by a body the build never runs, or one it never prices",
            instead =
                    "set @KeyOwner(forecastsStep = ...) to what the step's factory declares: priced or always names a verdict, RIDES or nothing does not")
    @Fixture("server/engine/guard-fixtures/forecast-key-unpaired-claim")
    void forecastKeyUnpairedClaim(Facts facts, Violations v) {
        Graph g = new Graph(facts);
        // Each member that builds a task: the names it hands Task.builder, and whether it declares a verdict.
        List<Node> factories = new ArrayList<>();
        for (Node n : g.nodes.values()) if (Graph.calls(n, TASK, "builder")) factories.add(n);
        int checked = 0;
        for (Owner o : g.owners) {
            // A shared owner's steps are what both sides key, and may be computed names (node-step-*).
            if (o.paired()) continue;
            for (String step : o.steps()) {
                checked++;
                List<Node> built = new ArrayList<>();
                // An owner that is its step's factory (the key computed in the step's body) is read directly.
                if (Graph.calls(o.node(), TASK, "builder")) built.add(o.node());
                // Step names are shared across plans (a script's compile-java, the module's): the
                // owner's own class's factories speak for it first.
                else built.addAll(buildersOf(factories, step, o.node().outer()));
                if (built.isEmpty()) built.addAll(buildersOf(factories, step, null));
                if (built.isEmpty()) {
                    v.add(
                            o.node().site(),
                            "@KeyOwner " + o.node().label() + " names step `" + step
                                    + "`, which no Task.builder in this module builds");
                    continue;
                }
                boolean named = false;
                for (Node f : built)
                    named |= Graph.calls(f, STEP_FORECAST, "priced") || Graph.calls(f, STEP_FORECAST, "always");
                if (named != o.forecastsStep())
                    v.add(
                            o.node().site(),
                            "@KeyOwner " + o.node().label() + " claims the forecast "
                                    + (o.forecastsStep() ? "names" : "never names")
                                    + " a `" + step + "` verdict, but "
                                    + built.getFirst().label()
                                    + (named ? " declares it priced or always" : " declares neither priced nor always")
                                    + "; stated reason: " + o.unpaired());
            }
        }
        v.population(checked);
    }
}
