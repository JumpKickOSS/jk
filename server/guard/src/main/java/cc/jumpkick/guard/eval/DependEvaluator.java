// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.rules.Allow;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlTable;

/**
 * {@code depend}: policy over what the manifests declare and what the lock resolved. Model
 * substrate, so it runs at plan time in microseconds — and it is the one kind that also runs at
 * mutation time: {@link #evaluateProposed} takes the manifest text {@code jk add} is about to write
 * and judges it before the write.
 *
 * <p>Arms: {@code ban} (declared or resolved coordinates that may not appear), {@code coordinate}
 * with {@code only-in} / {@code never-in} (scope placement), {@code require} (version floors over
 * the resolved lock), {@code convergence} (one version per artifact), {@code no-dynamic} (no floating
 * selector), {@code no-snapshot}. {@code licenses} needs POM metadata the lock does not carry yet
 * and reports {@code not-evaluated}, never clean.
 */
final class DependEvaluator implements Evaluator {

    /** One module's declared dependencies, by scope. */
    record Manifest(String module, Map<Scope, List<Dependency>> byScope) {}

    @Override
    public Evaluation evaluate(Rule rule, EvalContext ctx) throws IOException {
        List<Manifest> manifests = new ArrayList<>();
        Path rootManifest = ctx.root().resolve(ManifestPaths.MANIFEST);
        if (Files.isRegularFile(rootManifest)) manifests.add(manifest("", JkBuildParser.parse(rootManifest)));
        for (Path m : ctx.modules()) {
            Path f = m.resolve(ManifestPaths.MANIFEST);
            if (Files.isRegularFile(f))
                manifests.add(manifest(WorkspaceModel.rel(ctx.root(), m), JkBuildParser.parse(f)));
        }
        // A scoped rule reads the scoped manifests. The lock is workspace-wide and names no module,
        // so it is read only by an unscoped rule: a ban scoped to one module cannot judge it honestly.
        if (!rule.scope().isEmpty()) manifests.removeIf(m -> !rule.applies(m.module()));
        Path lockFile = ctx.root().resolve(ManifestPaths.LOCK);
        Lockfile lock = rule.scope().isEmpty() && Files.isRegularFile(lockFile) ? LockfileReader.read(lockFile) : null;
        return evaluate(rule, manifests, lock);
    }

    /** The mutation-time entry: {@code proposed} is the manifest text about to be written for {@code module}. */
    static Evaluation evaluateProposed(Rule rule, String module, String proposed, @Nullable Lockfile lock) {
        return evaluate(rule, List.of(manifest(module, JkBuildParser.parse(proposed))), lock);
    }

    static Manifest manifest(String module, JkBuild build) {
        return new Manifest(module, build.dependencies().byScope());
    }

    static Evaluation evaluate(Rule rule, List<Manifest> manifests, @Nullable Lockfile lock) {
        TomlTable t = rule.table();
        Policy policy = Policy.read(t);
        if (!policy.ban().isEmpty() && rule.instead() == null)
            return Evaluation.failed("`ban` needs `instead`: the sanctioned coordinate an agent switches to");
        if (policy.coordinate() != null
                && policy.onlyIn().isEmpty()
                && policy.neverIn().isEmpty()) return Evaluation.failed("`coordinate` needs `only-in` or `never-in`");
        if (t.contains("licenses")) {
            return Evaluation.notEvaluated(
                    "licenses: the lock carries no POM licence metadata yet, so nothing can be judged honestly");
        }
        if (policy.require() != null) {
            for (var e : policy.require().entrySet()) {
                if (!VersionRanges.valid(e.getValue()))
                    return Evaluation.failed("require: `" + e.getKey() + " = \"" + e.getValue()
                            + "\"` is not a version range (>=x, >x, <=x, <x, =x, comma-separated)");
            }
        }

        Scan scan = new Scan(rule, policy);
        scan.manifests(manifests);
        if (lock != null) {
            scan.lock(lock);
        } else if (policy.require() != null || policy.convergence()) {
            return Evaluation.notEvaluated(
                    rule.scope().isEmpty()
                            ? "no lockfile; run jk lock first"
                            : "require/convergence read the lock, which is workspace-wide; drop `scope` from this rule");
        }
        return scan.finish();
    }

    /** The rule's arms as written. */
    private record Policy(
            List<String> ban,
            @Nullable String coordinate,
            List<String> onlyIn,
            List<String> neverIn,
            @Nullable Map<String, String> require,
            boolean convergence,
            boolean noDynamic,
            boolean noSnapshot,
            Set<Scope> scopes) {

        static Policy read(TomlTable t) {
            TomlTable requireTable = t.getTable("require");
            // toMap, not getString(key): a key like "org.apache.logging.log4j:*" is not a dotted path.
            Map<String, String> require = null;
            if (requireTable != null) {
                require = new TreeMap<>();
                for (var e : requireTable.toMap().entrySet()) require.put(e.getKey(), String.valueOf(e.getValue()));
            }
            return new Policy(
                    ForbidEvaluator.strings(t, "ban"),
                    t.isString("coordinate") ? String.valueOf(t.getString("coordinate")) : null,
                    ForbidEvaluator.strings(t, "only-in"),
                    ForbidEvaluator.strings(t, "never-in"),
                    require,
                    Boolean.TRUE.equals(t.getBoolean("convergence")),
                    Boolean.TRUE.equals(t.getBoolean("no-dynamic")),
                    Boolean.TRUE.equals(t.getBoolean("no-snapshot")),
                    DependEvaluator.scopes(ForbidEvaluator.strings(t, "scopes")));
        }
    }

    /** One evaluation's walk over the manifests and the lock: the policy, and the sites and counts it leaves. */
    private static final class Scan {
        private final Rule rule;
        private final Policy p;
        private final Map<Allow, Boolean> allowUsed = new LinkedHashMap<>();
        private final List<Observation> out = new ArrayList<>();
        private long declared = 0;
        private long artifacts = 0;

        Scan(Rule rule, Policy p) {
            this.rule = rule;
            this.p = p;
            for (Allow a : rule.allow()) allowUsed.put(a, false);
        }

        void manifests(List<Manifest> manifests) {
            for (Manifest m : manifests) {
                for (var e : m.byScope().entrySet()) {
                    Scope scope = e.getKey();
                    if (!p.scopes().isEmpty() && !p.scopes().contains(scope)) continue;
                    for (Dependency d : e.getValue()) declared(m, scope, d);
                }
            }
        }

        private void declared(Manifest m, Scope scope, Dependency d) {
            declared++;
            String ga = d.module();
            Allow allow = allowing(rule.allow(), m.module(), ga);
            if (allow != null) allowUsed.put(allow, true);
            String where = (m.module().isEmpty() ? "root" : m.module()) + " [" + scope.tomlSection() + "]";
            String file = (m.module().isEmpty() ? "" : m.module() + "/") + ManifestPaths.MANIFEST;
            for (String b : p.ban()) {
                if (coordMatches(b, ga)) {
                    if (allow == null)
                        out.add(Observation.site(
                                where + " " + ga, file, 0, ga + " is banned (declared in " + where + ")"));
                }
            }
            if (p.coordinate() != null && coordMatches(p.coordinate(), ga)) {
                boolean bad = (!p.onlyIn().isEmpty()
                                && !p.onlyIn().contains(scope.tomlSection())
                                && !p.onlyIn().contains(scope.canonical()))
                        || p.neverIn().contains(scope.tomlSection())
                        || p.neverIn().contains(scope.canonical());
                if (bad && allow == null) {
                    out.add(Observation.site(
                            where + " " + ga,
                            file,
                            0,
                            ga + " in [" + scope.tomlSection() + "]"
                                    + (p.onlyIn().isEmpty()
                                            ? ""
                                            : "; allowed only in " + String.join(", ", p.onlyIn()))));
                }
            }
            if (p.noDynamic()
                    && !(d.version() instanceof VersionSelector.Exact)
                    && !d.isWorkspace()
                    && !d.isPath()
                    && !d.isGit()) {
                if (allow == null)
                    out.add(Observation.site(
                            "dynamic " + where + " " + ga,
                            file,
                            0,
                            ga + " = \"" + d.version().raw() + "\" floats; pin it"));
            }
            if (p.noSnapshot() && d.version() instanceof VersionSelector.Snapshot && allow == null) {
                out.add(Observation.site("snapshot " + where + " " + ga, file, 0, ga + " selects a snapshot"));
            }
        }

        void lock(Lockfile lock) {
            Map<String, Set<String>> versionsByGa = new TreeMap<>();
            for (Lockfile.Artifact a : lock.artifacts()) {
                if (!p.scopes().isEmpty() && a.scopes().stream().noneMatch(p.scopes()::contains)) continue;
                artifacts++;
                String ga = ga(a.name());
                versionsByGa.computeIfAbsent(ga, k -> new TreeSet<>()).add(a.version());
                resolved(a, ga);
            }
            if (p.convergence()) {
                for (var e : versionsByGa.entrySet()) {
                    if (e.getValue().size() > 1 && allowing(rule.allow(), "", e.getKey()) == null) {
                        out.add(Observation.site(
                                "convergence " + e.getKey(),
                                ManifestPaths.LOCK,
                                0,
                                e.getKey() + " resolves to " + e.getValue().size() + " versions: "
                                        + String.join(", ", e.getValue())));
                    }
                }
            }
        }

        private void resolved(Lockfile.Artifact a, String ga) {
            Allow allow = allowing(rule.allow(), "", ga);
            if (allow != null) allowUsed.put(allow, true);
            for (String b : p.ban()) {
                if (coordMatches(b, ga) && allow == null) {
                    out.add(Observation.site(
                            "lock " + ga,
                            ManifestPaths.LOCK,
                            0,
                            ga + "@" + a.version() + " is banned (resolved through the lock"
                                    + (a.pinnedBy() != null ? ", pinned by " + a.pinnedBy() : "") + ")"));
                }
            }
            if (p.require() != null) {
                for (var e : p.require().entrySet()) {
                    if (!coordMatches(e.getKey(), ga)) continue;
                    String spec = e.getValue();
                    if (!VersionRanges.satisfies(a.version(), spec) && allow == null) {
                        out.add(Observation.site(
                                "floor " + ga,
                                ManifestPaths.LOCK,
                                0,
                                ga + "@" + a.version() + " is below the floor " + spec));
                    }
                }
            }
            if (p.noSnapshot() && a.version().toUpperCase(Locale.ROOT).endsWith("-SNAPSHOT") && allow == null) {
                out.add(Observation.site(
                        "snapshot lock " + ga, ManifestPaths.LOCK, 0, ga + "@" + a.version() + " is a snapshot"));
            }
        }

        Evaluation finish() {
            Map<String, Long> population = Map.of("declared", declared, "artifacts", artifacts);
            List<String> stale = new ArrayList<>();
            for (var e : allowUsed.entrySet())
                if (!e.getValue()) stale.add(e.getKey().in());
            if (!stale.isEmpty() && declared + artifacts > 0) {
                return new Evaluation(
                        Outcome.STALE_ALLOW,
                        population,
                        out,
                        "allow entries matched nothing: " + String.join(", ", stale));
            }
            return Evaluation.of(population, out);
        }
    }

    /** {@code group:artifact} of a lock name {@code group:artifact:type:classifier}. */
    static String ga(String lockName) {
        String[] p = lockName.split(":");
        return p.length >= 2 ? p[0] + ":" + p[1] : lockName;
    }

    /** {@code g:a} against a spec with {@code *} in group or artifact. */
    static boolean coordMatches(String spec, String ga) {
        if (spec.equals(ga)) return true;
        if (!spec.contains("*")) return false;
        return Rule.globMatches(spec.replace(":", "/"), ga.replace(":", "/"));
    }

    private static Set<Scope> scopes(List<String> names) {
        Set<Scope> out = new TreeSet<>();
        for (String n : names) {
            for (Scope s : Scope.values())
                if (s.tomlSection().equals(n) || s.canonical().equals(n)) out.add(s);
        }
        return out;
    }

    private static @Nullable Allow allowing(List<Allow> allow, String module, String ga) {
        for (Allow a : allow) {
            if (a.in().equals(ga) || coordMatches(a.in(), ga)) return a;
            if (!module.isEmpty() && (a.in().equals(module) || Rule.globMatches(a.in(), module))) return a;
        }
        return null;
    }
}
