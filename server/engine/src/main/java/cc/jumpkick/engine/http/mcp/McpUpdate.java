// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Errors;
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
import cc.jumpkick.runtime.workspace.ManifestUpdates;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code jk_update}'s body: the pin rewrites {@link ManifestUpdates} plans for {@code dir}, and with
 * {@code apply} the written manifests plus the {@link LockMode.Update} relock and every lock package
 * it changed — the same phases the hosted {@code jk update} runs, answered synchronously.
 */
public final class McpUpdate {

    private McpUpdate() {}

    public static Map<String, Object> run(String dir, List<String> deps, boolean major, boolean apply) {
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
                    if (!apply) {
                        out.put("applied", false);
                        return null;
                    }
                    Lockfile before = LockDiff.current(lockDir);
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
                    lock.put(
                            "packages",
                            written == null ? -1 : written.artifacts().size());
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
                    return null;
                }
            });
        } catch (Exception e) {
            out.put("error", Errors.text(e));
        }
        return out;
    }

    private static List<Map<String, Object>> rewrites(ManifestUpdates.Plan plan) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ManifestUpdates.Rewrite r : plan.rewrites()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("manifest", r.manifest().toString());
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
