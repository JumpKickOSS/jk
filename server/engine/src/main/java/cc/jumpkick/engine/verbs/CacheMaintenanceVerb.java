// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.plugin.protocol.Jsonl;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class CacheMaintenanceVerb implements HostedVerb {

    private final VerbHost host;

    public CacheMaintenanceVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.CACHE_PRUNE_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.maintenance("cache");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.CacheMaint();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-cache-";
    }

    @Override
    public void run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            try {
                String op = String.valueOf(Jsonl.str(requestLine, "op"));
                Path cache = Path.of(Jsonl.str(requestLine, "cache"));
                boolean dryRun = Jsonl.bool(requestLine, "dryRun", false);

                if (!host.cacheGate().writeLock().tryLock()) {
                    host.sendQuiet(writer, EngineProtocol.pruneWait(host.activePlanCount(), false));
                    host.cacheGate().writeLock().lock();
                }
                try {
                    Files.createDirectories(cache);
                    try (FileChannel lockChan = FileChannel.open(
                            cache.resolve(".prune.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                        FileLock pruneLock = lockChan.tryLock();
                        if (pruneLock == null) {
                            // Another process's prune holds the cross-process lock — wait for it too.
                            host.sendQuiet(writer, EngineProtocol.pruneWait(0, true));
                            pruneLock = lockChan.lock();
                        }
                        try {
                            cc.jumpkick.run.BuildPlan plan =
                                    switch (op) {
                                        case "purge" -> cc.jumpkick.runtime.CachePlans.purgeBuildPlan(cache);
                                        case "sweep" -> cc.jumpkick.runtime.CachePlans.sweepBuildPlan(cache, dryRun);
                                        case "gc" -> cc.jumpkick.runtime.CachePlans.gcBuildPlan(cache);
                                        case "clear" ->
                                            cc.jumpkick.runtime.CachePlans.clearBuildPlan(
                                                    cache, Path.of(Jsonl.str(requestLine, "dir")), dryRun);
                                        default ->
                                            cc.jumpkick.runtime.CachePlans.pruneBuildPlan(
                                                    cache,
                                                    Jsonl.intValue(requestLine, "olderThanDays", 30),
                                                    dryRun,
                                                    Jsonl.bool(requestLine, "sweep", false),
                                                    Jsonl.bool(requestLine, "includeJkTmp", false),
                                                    Jsonl.bool(requestLine, "dropAllClassC", false));
                                    };
                            Session session =
                                    Session.defaults().withCacheDir(cache).withCancel(cancelToken);
                            String dir = EngineProtocol.SINGLE_PLAN_DIR;
                            host.streamSinglePlan(plan, session, writer, result -> {
                                // An explicit clean IS a prune — stamp it, or `usage` keeps warning
                                // "Last cleaned: never" right after a successful clean and the idle
                                // scheduler re-runs work the user just did (JK-1771). Same file for
                                // the store tier: its usage footer reads from its own root.
                                if (result.success() && !dryRun && ("prune".equals(op) || "sweep".equals(op))) {
                                    try {
                                        Files.writeString(
                                                cache.resolve(cc.jumpkick.task.CachePruneScheduler.LAST_PRUNED_FILE),
                                                Long.toString(host.nowMillis()),
                                                StandardCharsets.UTF_8);
                                    } catch (IOException ignored) {
                                        // best-effort stamp; the clean itself succeeded
                                    }
                                }
                                return EngineProtocol.planFinishCache(
                                        dir,
                                        result.success(),
                                        plan.get(cc.jumpkick.runtime.CachePlans.FILES)
                                                .orElse(-1L),
                                        plan.get(cc.jumpkick.runtime.CachePlans.BYTES)
                                                .orElse(-1L),
                                        plan.get(cc.jumpkick.runtime.CachePlans.REACHABLE_EVICTED)
                                                .orElse(-1L),
                                        plan.get(cc.jumpkick.runtime.CachePlans.REPO_LINKS)
                                                .orElse(-1L));
                            });
                        } finally {
                            pruneLock.release();
                        }
                    }
                } finally {
                    host.cacheGate().writeLock().unlock();
                }
            } catch (Exception e) {
                host.sendQuiet(writer, host.requestFailedLine(null, e));
            }

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
    }
}
