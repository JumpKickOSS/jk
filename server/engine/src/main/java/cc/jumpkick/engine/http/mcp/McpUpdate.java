// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.lock.LockDiff;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.runtime.LockPlans;
import cc.jumpkick.runtime.base.LockGate;
import cc.jumpkick.runtime.base.LockMode;
import cc.jumpkick.runtime.workspace.AddedPackages;
import cc.jumpkick.runtime.workspace.ManifestUpdates;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * {@code update}'s body: the pin rewrites {@link ManifestUpdates} plans for {@code dir}, and with
 * {@code apply} the written manifests plus the {@link LockMode.Update} relock and every lock package
 * it changed — the same phases the hosted {@code jk update} runs, answered synchronously.
 */
public final class McpUpdate {

    private McpUpdate() {}

    /**
     * The plan for {@code dir}, written and relocked when {@code apply}. Unless {@code approved}, an
     * apply that moves a pin across a minor or a major, or whose relock adds a package, writes
     * nothing; either answers {@code held}, one line per move or added package, for
     * the caller to put to a person before it applies again with {@code approved}.
     */
    public static Map<String, Object> run(
            String dir, List<String> deps, boolean major, boolean apply, boolean approved) {
        Path root = PathUtil.resolveUserPath(dir);
        Map<String, Object> out = new LinkedHashMap<>();
        if (McpManifest.refuseShadowed(root.resolve(ManifestPaths.MANIFEST), out)) return out;
        try {
            Path cache = JkDirs.cache();
            Session session = Session.defaults().withWorkingDir(root).withCacheDir(cache);
            SessionContext.where(session, () -> {
                LockPlans.LockScope scope = LockPlans.lockScope(root);
                Path lockDir = scope.lockDir();
                ManifestUpdates.Selection selection = new ManifestUpdates.Selection(deps, major);
                synchronized (LockGate.monitorFor(lockDir)) {
                    ManifestUpdates.Plan plan = ManifestUpdates.plan(lockDir, null, selection);
                    out.put("rewrites", rewrites(plan));
                    out.put("changed", !plan.isEmpty());
                    out.put("files", files(plan));
                    String entryManifest = plan.contents().get(root.resolve(ManifestPaths.MANIFEST));
                    out.put("preview", entryManifest == null ? "" : entryManifest);
                    List<String> crossing = crossing(plan);
                    if (!apply || (!approved && !crossing.isEmpty())) {
                        out.put("applied", false);
                        if (apply) out.put("held", crossing);
                        return null;
                    }
                    apply(root, scope, lockDir, cache, plan, approved, out);
                    return null;
                }
            });
        } catch (Exception e) {
            out.put("error", Errors.text(e));
        }
        return out;
    }

    /**
     * Writes {@code plan} and relocks. Unapproved, a relock that would add a package to an existing
     * lock is found first by a resolve that reads the plan's manifests from memory, and then nothing
     * is written.
     */
    private static void apply(
            Path root,
            LockPlans.LockScope scope,
            Path lockDir,
            Path cache,
            ManifestUpdates.Plan plan,
            boolean approved,
            Map<String, Object> out)
            throws Exception {
        Lockfile before = LockDiff.current(lockDir);
        // A first lock adds every package; only a relock that grows an existing lock asks.
        if (!approved && before != null) {
            Lockfile probe = probe(lockDir, cache, plan);
            if (probe != null) {
                List<LockDiff.Change> changes = LockDiff.between(before, probe);
                List<String> added = added(changes);
                if (!added.isEmpty()) {
                    out.put("applied", false);
                    Map<String, Object> lock = new LinkedHashMap<>();
                    lock.put("success", true);
                    lock.put("exitCode", 0);
                    lock.put("packages", probe.artifacts().size());
                    lock.put("updated", changes.size());
                    lock.put("changes", changes(changes));
                    out.put("lock", lock);
                    out.put("held", added);
                    return;
                }
            }
        }
        ManifestUpdates.apply(plan);
        out.put("applied", true);
        LockPlans.LockScope relockScope = plan.isEmpty() ? scope : LockPlans.lockScope(root);
        BuildPlan relock = LockPlans.plan(
                lockDir,
                relockScope.effective(),
                cache,
                null,
                List.of(),
                true,
                new LockMode.Update(null),
                ResolveObserver.NOOP,
                null);
        BuildPlanResult result = relock.run();
        Map<String, Object> lock = new LinkedHashMap<>();
        lock.put("success", result.success());
        lock.put("exitCode", result.success() ? 0 : LockPlans.failureExitCode(result));
        Lockfile written = relock.get(LockPlans.LOCKFILE).orElse(null);
        lock.put("packages", written == null ? -1 : written.artifacts().size());
        if (written != null) {
            List<LockDiff.Change> changes = LockDiff.between(before, written);
            lock.put("updated", changes.size());
            lock.put("changes", changes(changes));
        }
        List<String> errors = new ArrayList<>();
        for (BuildPlanResult.Diagnostic d : result.errors()) {
            if (d.message() != null) errors.add(d.message());
        }
        if (!errors.isEmpty()) lock.put("errors", errors);
        out.put("lock", lock);
        if (!result.success()) out.put("error", "relock failed");
    }

    /**
     * The lock {@code plan}'s manifests resolve to, read from memory and written nowhere; {@code
     * null} when that resolve fails, which the relock then meets and reports.
     */
    private static @Nullable Lockfile probe(Path lockDir, Path cache, ManifestUpdates.Plan plan) {
        try {
            return AddedPackages.probe(
                    lockDir, plan.contents(), cache, null, List.of(), true, new LockMode.Update(null));
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            Log.debug("update: no probe of the packages the relock adds", e);
            return null;
        }
    }

    /** Each package {@code changes} adds, as {@code coordinate version (new)}. */
    private static List<String> added(List<LockDiff.Change> changes) {
        List<String> added = new ArrayList<>();
        for (LockDiff.Change c : changes) {
            if (c.from() == null && c.to() != null) added.add(c.coordinate() + " " + c.to() + " (new)");
        }
        return added;
    }

    /** Each move across a minor or a major, as {@code handle from → to}. */
    private static List<String> crossing(ManifestUpdates.Plan plan) {
        List<String> out = new ArrayList<>();
        for (ManifestUpdates.Rewrite r : plan.rewrites()) {
            if (r.crossesMinor()) out.add(r.handle() + " " + r.from() + " → " + r.to());
        }
        return out;
    }

    private static List<Map<String, Object>> rewrites(ManifestUpdates.Plan plan) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ManifestUpdates.Rewrite r : plan.rewrites()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("manifest", r.file().toString());
            row.put("module", r.moduleLabel());
            row.put("table", r.table());
            row.put("handle", r.handle());
            row.put("coordinate", r.module());
            row.put("from", r.from());
            row.put("to", r.to());
            rows.add(row);
        }
        return rows;
    }

    /** One row per changed lock package; {@code from}/{@code to} null when added/removed. */
    private static List<Map<String, Object>> changes(List<LockDiff.Change> changes) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (LockDiff.Change c : changes) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("coordinate", c.coordinate());
            row.put("from", c.from());
            row.put("to", c.to());
            if (!c.members().isEmpty()) row.put("members", c.members());
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> files(ManifestUpdates.Plan plan) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<Path, String> e : plan.contents().entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("path", e.getKey().toString());
            row.put("content", e.getValue());
            rows.add(row);
        }
        return rows;
    }
}
