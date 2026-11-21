// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.UserConfigParseException;
import cc.jumpkick.engine.graph.ApiBreaks;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.lock.LockDiff;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.runtime.LockPlans;
import cc.jumpkick.runtime.ReleaseSession;
import cc.jumpkick.runtime.RepoGroupBuilder;
import cc.jumpkick.runtime.base.LockGate;
import cc.jumpkick.runtime.base.LockMode;
import cc.jumpkick.runtime.workspace.AddedPackages;
import cc.jumpkick.runtime.workspace.ManifestUpdates;
import cc.jumpkick.wire.protocol.UpdateRequest;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The preview of {@code jk update}, one operation for every surface: the pin moves a selection
 * takes, the moves past a major it leaves {@code available}, and the lock packages each move's
 * relock would add. {@code jk update --preview} renders it as {@code update-rewrite} events, MCP
 * {@code update} and REST {@code GET /api/project/update} as {@link #structured}. Applying is the
 * {@code update} job ({@link LockCascade}), on every surface.
 */
public final class Updates {

    /** Rows a structured preview lists per list; the rest are counted. */
    static final int MAX_ROWS = 100;

    /**
     * The moves a selection takes and leaves, the packages each taken move adds, and for a move
     * whose two jars could be compared how many places in the project use API the move takes away.
     */
    public record Preview(
            List<ManifestUpdates.Rewrite> rewrites,
            List<ManifestUpdates.Rewrite> available,
            Map<ManifestUpdates.Rewrite, List<String>> adding,
            Map<ManifestUpdates.Rewrite, Integer> breaks) {

        public Preview(
                List<ManifestUpdates.Rewrite> rewrites,
                List<ManifestUpdates.Rewrite> available,
                Map<ManifestUpdates.Rewrite, List<String>> adding) {
            this(rewrites, available, adding, Map.of());
        }
    }

    private Updates() {}

    /**
     * The preview a REST or MCP caller asks for: {@code deps} (empty: every pin) and {@code major},
     * under the session a hosted {@code update} job of {@code dir} gets.
     */
    public static Preview preview(String dir, List<String> deps, boolean major) throws Exception {
        Path root = PathUtil.resolveUserPath(dir);
        if (ManifestPaths.isShadowed(root)) {
            throw new IllegalArgumentException(ManifestPaths.noManifestToEdit(root.toString()));
        }
        String line = new UpdateRequest(
                        root.toString(),
                        JkDirs.cache().toString(),
                        List.of(),
                        false,
                        null,
                        false,
                        null,
                        false,
                        false,
                        false,
                        "",
                        deps,
                        major,
                        true)
                .encode();
        Session session = ReleaseSession.sessionOf(line, Session.CancelToken.NONE);
        return SessionContext.where(session, () -> {
            Preview p = preview(
                    root,
                    session.cacheDir(),
                    null,
                    List.of(),
                    true,
                    new LockMode.Update(null),
                    new ManifestUpdates.Selection(deps, major));
            return new Preview(p.rewrites(), p.available(), p.adding(), breaks(root, null, p));
        });
    }

    /**
     * For each move of {@code preview}, how many places in the project under {@code entryDir} use
     * API the move takes away, read from the classes its last compile wrote. A move whose locked or
     * target jar cannot be had gets no count.
     */
    static Map<ManifestUpdates.Rewrite, Integer> breaks(Path entryDir, @Nullable URI repoUrl, Preview preview) {
        List<ManifestUpdates.Rewrite> moves = new ArrayList<>(preview.rewrites());
        moves.addAll(preview.available());
        if (moves.isEmpty()) return Map.of();
        Cas cas = JkStores.storeCas();
        Path lockDir;
        RepoGroup repos;
        try {
            lockDir = LockPlans.lockScope(entryDir).lockDir();
            repos = RepoGroupBuilder.buildFor(JkBuildParser.parse(ManifestPaths.manifestIn(lockDir)), repoUrl, cas);
        } catch (UserConfigParseException unparsed) {
            throw unparsed;
        } catch (IOException | RuntimeException unreadable) {
            Log.debug("update: no repositories to fetch the moved jars from", unreadable);
            return Map.of();
        }
        List<ApiBreaks.Classes> project = ApiBreaks.workspace(lockDir);
        Map<String, Integer> byTarget = new HashMap<>();
        Map<ManifestUpdates.Rewrite, Integer> out = new LinkedHashMap<>();
        for (ManifestUpdates.Rewrite r : moves) {
            String target = r.module() + ":" + r.to();
            Integer n = byTarget.get(target);
            if (n == null && !byTarget.containsKey(target)) {
                try {
                    ApiBreaks.Report report = ApiBreaks.of(lockDir, r.module(), r.to(), repos, cas, project);
                    n = report == null ? null : report.breaks();
                } catch (IOException | RuntimeException e) {
                    Log.debug("update: no API comparison for " + target, e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                byTarget.put(target, n);
            }
            if (n != null) out.put(r, n);
        }
        return out;
    }

    /**
     * Plan the moves {@code selection} takes under the lock dir's gate and probe, by a resolve that
     * reads the moved manifests from memory, the packages each would add to the existing lock.
     * Nothing is written or relocked. A failed probe leaves the moves without their packages: the
     * update itself meets the same failure and reports it.
     */
    static Preview preview(
            Path entryDir,
            Path cache,
            @Nullable URI repoUrl,
            List<String> features,
            boolean withDefaults,
            LockMode mode,
            ManifestUpdates.Selection selection)
            throws IOException {
        Path lockDir = LockPlans.lockScope(entryDir).lockDir();
        synchronized (LockGate.monitorFor(lockDir)) {
            ManifestUpdates.Plan plan = ManifestUpdates.plan(lockDir, repoUrl, selection);
            Map<ManifestUpdates.Rewrite, List<String>> adding = Map.of();
            Lockfile before = LockDiff.current(lockDir);
            if (before != null && !plan.isEmpty()) {
                try {
                    adding = AddedPackages.byMove(
                            before,
                            AddedPackages.probe(lockDir, plan.contents(), cache, repoUrl, features, withDefaults, mode),
                            plan.rewrites());
                } catch (Exception e) {
                    if (e instanceof InterruptedException)
                        Thread.currentThread().interrupt();
                    Log.debug("update: no probe of the packages the moves add", e);
                }
            }
            return new Preview(plan.rewrites(), plan.available(), adding);
        }
    }

    /**
     * {@code preview} as the bounded JSON object REST answers and MCP returns: each move as {@code
     * {manifest, module, table, handle, coordinate, from, to, adds?, breaks?}}, at most {@link #MAX_ROWS} per
     * list with {@code moreRewrites}/{@code moreAvailable} counting the rest.
     */
    public static Map<String, Object> structured(Preview preview) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rewrites", rows(preview.rewrites(), preview.adding(), preview.breaks()));
        if (preview.rewrites().size() > MAX_ROWS)
            m.put("moreRewrites", preview.rewrites().size() - MAX_ROWS);
        m.put("available", rows(preview.available(), Map.of(), preview.breaks()));
        if (preview.available().size() > MAX_ROWS)
            m.put("moreAvailable", preview.available().size() - MAX_ROWS);
        return m;
    }

    private static List<Map<String, Object>> rows(
            List<ManifestUpdates.Rewrite> moves,
            Map<ManifestUpdates.Rewrite, List<String>> adding,
            Map<ManifestUpdates.Rewrite, Integer> breaks) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ManifestUpdates.Rewrite r : moves) {
            if (rows.size() >= MAX_ROWS) break;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("manifest", r.file().toString());
            row.put("module", r.moduleLabel());
            row.put("table", r.table());
            row.put("handle", r.handle());
            row.put("coordinate", r.module());
            row.put("from", r.from());
            row.put("to", r.to());
            List<String> adds = adding.getOrDefault(r, List.of());
            if (!adds.isEmpty()) row.put("adds", adds);
            Integer broken = breaks.get(r);
            if (broken != null) row.put("breaks", broken);
            rows.add(row);
        }
        return rows;
    }
}
