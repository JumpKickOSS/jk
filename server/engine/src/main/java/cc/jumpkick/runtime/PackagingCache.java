// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.DiskGuard;
import cc.jumpkick.config.HeavyTtl;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ChangedInputs;
import cc.jumpkick.task.FileHashMemo;
import cc.jumpkick.task.HeavyActionPolicy;
import cc.jumpkick.task.InputGuard;
import cc.jumpkick.task.ModuleIdentity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * The packaging steps' action cache: restore a packaged artifact for its key, or record a freshly
 * packaged one, with what the step said as it ran.
 */
final class PackagingCache {

    private PackagingCache() {}

    /**
     * Packaging cache (mirrors the compile {@link ActionCache} path, for artifacts). Returns {@code
     * true} when a cached artifact for {@code key} was hard-linked back into {@code baseDir} — the
     * caller then skips the (re)packaging work.
     *
     * <p>Invariant: every packaging step that writes a jar under {@code target/} also
     * {@link #storePackaged stores} an action record. A jar without a record only happens when the
     * action cache was deleted out of band while {@code target/} was kept — recovery is to
     * re-package (this miss path), not to trust the on-disk jar as authoritative.
     *
     * <p>{@code --redo}/{@code --force} skip <em>restore</em> (always re-package) but still
     * {@link #storePackaged store} — same contract as {@link cc.jumpkick.task.JavaCompile}: the next
     * {@code jk explain} / incremental build must see a CACHE_HIT, not a phantom repackage.
     */
    static boolean restorePackaged(Path cacheRoot, String key, @Nullable Path baseDir) throws IOException {
        return restorePackaged(cacheRoot, key, baseDir, null);
    }

    /**
     * As {@link #restorePackaged(Path, String, Path)}, and on a restore {@code said}, when given,
     * says again what the run that stored the record said.
     */
    static boolean restorePackaged(Path cacheRoot, String key, @Nullable Path baseDir, @Nullable StepDiagnostics said)
            throws IOException {
        // rebuildOr already subsumes force (JkConfig: force implies rebuild).
        if (SessionContext.current().config().rebuildOr(false)) return false;
        ActionCache ac = packagingActionCache(cacheRoot);
        var hit = ac.lookup(key);
        if (baseDir == null || hit.isEmpty() || !ac.restoreArtifacts(hit.get(), baseDir)) return false;
        if (said != null) said.replay(hit.get());
        return true;
    }

    /**
     * Record a freshly-produced packaging artifact so a later build / explain can skip it. Writes
     * even under {@code --redo} — redo only means "do not restore/skip work", not "do not
     * teach the cache" (parity with compile). {@code persist=false} is {@code jk verify}'s scratch
     * rebuild: packaging DOES run there (the artifact is what verify diffs), its keys embed the
     * unique scratch path so they can never recur, and a store would be a permanent orphan record
     * plus CAS copies on every verify run. {@code said} is what the packaging told the user as it
     * ran ({@link StepDiagnostics}), kept for a restore to say again.
     */
    static void storePackaged(
            TaskContext ctx,
            InputGuard guard,
            Path cacheRoot,
            String taskId,
            String key,
            List<String> tokens,
            @Nullable Path baseDir,
            List<Path> artifacts,
            boolean persist,
            List<String> said)
            throws IOException {
        if (!persist) return;
        // An input that moved while the artifact was written may be in it, and is not what the
        // key names: the artifact stands for this build, but no record vouches for it.
        List<Path> moved = guard.moved();
        if (!moved.isEmpty()) {
            ctx.warn(InputGuard.MOVED, InputGuard.describe(taskId.split("@", 2)[0], moved));
            return;
        }
        if (!admitted(ctx, cacheRoot, artifacts, taskId)) return;
        storeRecord(cacheRoot, taskId, key, tokens, baseDir, artifacts, said);
    }

    /**
     * True when {@code artifacts} may go into the cache under {@code cacheRoot}: a heavy artifact
     * enters only while the machine keeps heavy outputs and the module has not opted out ({@link
     * #heavyRefused}), and jk never fills a disk, so on a nearly full volume ({@link DiskGuard}) a
     * heavy artifact stays in target/ only, the step says so, and the engine is woken to reclaim space.
     */
    static boolean admitted(TaskContext ctx, Path cacheRoot, List<Path> artifacts, @Nullable String taskId) {
        if (HeavyActionPolicy.classC(taskId) || largestOf(artifacts) >= DiskGuard.HEAVY_BYTES) {
            JkBuild project =
                    ctx.get(BuildPlanner.MODULE).map(ModuleIdentity::project).orElse(null);
            if (heavyRefused(project, HeavyTtl.resolve()) != null) return false;
        }
        if (DiskGuard.admits(cacheRoot, artifacts)) return true;
        ctx.warn(
                DISK_LOW,
                diskFull(cacheRoot) + "; the output stays where it was built and JumpKick is reclaiming space");
        return false;
    }

    /**
     * Why no heavy output of {@code project} can be served from the cache under {@code cacheRoot}, or
     * null when one can: what {@code jk explain} says beside a heavy step's miss.
     */
    static @Nullable String heavyNotCached(Path cacheRoot, @Nullable JkBuild project) {
        return heavyNotCached(cacheRoot, project, HeavyTtl.resolve());
    }

    /** As {@link #heavyNotCached(Path, JkBuild)} under the machine's heavy window {@code heavyTtl}. */
    static @Nullable String heavyNotCached(Path cacheRoot, @Nullable JkBuild project, Duration heavyTtl) {
        String refused = heavyRefused(project, heavyTtl);
        if (refused != null) return refused;
        return DiskGuard.critical(cacheRoot) ? diskFull(cacheRoot) : null;
    }

    /**
     * Why a heavy output is kept out of the cache whatever room the disk has, or null when it may
     * enter: the machine keeps none ({@code [cache] heavy-ttl}, 0 on CI), or the module opted out
     * ({@code [build] cache-heavy-outputs}).
     */
    private static @Nullable String heavyRefused(@Nullable JkBuild project, Duration heavyTtl) {
        if (!HeavyTtl.caches(heavyTtl)) return "not cached: [cache] heavy-ttl = 0";
        if (project != null && !project.build().cacheHeavyOutputs()) {
            return "not cached: [build] cache-heavy-outputs = false";
        }
        return null;
    }

    private static String diskFull(Path cacheRoot) {
        return "not cached: the disk is nearly full (" + DiskGuard.describe(DiskGuard.space(cacheRoot)) + ")";
    }

    /** The warning a packaging step carries when the disk guard kept its artifact out of the cache. */
    static final String DISK_LOW = "disk-low";

    private static long largestOf(List<Path> artifacts) {
        long largest = 0;
        for (Path a : artifacts) {
            try {
                if (Files.isRegularFile(a)) largest = Math.max(largest, Files.size(a));
            } catch (IOException unreadable) {
                // a file that cannot be sized decides nothing
            }
        }
        return largest;
    }

    private static void storeRecord(
            Path cacheRoot,
            String taskId,
            String key,
            List<String> tokens,
            @Nullable Path baseDir,
            List<Path> artifacts,
            List<String> said)
            throws IOException {
        packagingActionCache(cacheRoot)
                .storeArtifacts(
                        taskId,
                        key,
                        Map.of(ChangedInputs.JOINED_TOKENS, String.join(";", tokens)),
                        baseDir,
                        artifacts,
                        said);
    }

    /**
     * {@code target/<module>/.jk/bundled/<step>}: the workspace jars a packaging step built its
     * output from, as that step keyed them. One file per step, so two tails of one module never
     * write the same file.
     */
    private static Path bundledDir(Path target) {
        return target.resolve(".jk").resolve("bundled");
    }

    /**
     * Record, from a packaging step itself once its output is in place (packaged or restored), the
     * content of each file among {@code inputs} that lives in the workspace: a sibling's jar, or the
     * module's own. The store's jars are content-addressed and never move. Lines {@code <sha>
     * <path relative to the module's target dir>}. The record names the bytes the output beside it
     * was built from, which is not what a sibling holds once it has moved on, as a build of the
     * sibling alone leaves it. Best-effort: never fails a build.
     */
    static void recordBundled(BuildLayout layout, String step, List<Path> inputs) {
        try {
            Path target = layout.moduleTargetDir().toAbsolutePath().normalize();
            Path workspace = layout.workspaceRoot().toAbsolutePath().normalize();
            Map<String, String> digests = new TreeMap<>();
            for (Path input : inputs) {
                Path abs = input.toAbsolutePath().normalize();
                if (abs.startsWith(workspace) && Files.isRegularFile(abs)) {
                    digests.put(target.relativize(abs).toString().replace('\\', '/'), FileHashMemo.contentHash(abs));
                }
            }
            StringBuilder sb = new StringBuilder();
            digests.forEach((rel, sha) -> sb.append(sha).append(' ').append(rel).append('\n'));
            Path dir = bundledDir(target);
            Files.createDirectories(dir);
            AtomicWrites.replaceIfChanged(dir.resolve(step), sb.toString());
        } catch (IOException | RuntimeException unwritable) {
            // No record is no evidence: bundledMoved reads a missing file as nothing moved, and the
            // step's own key still decides whether it runs once the module is scheduled.
            Log.debug("packaging: bundled inputs not recorded", "module", layout.moduleRoot(), "cause", unwritable);
        }
    }

    /**
     * True when a workspace file a packaging step of the module under {@code target} built its
     * output from now holds other bytes than the step recorded. The memo and the forecast judge a
     * module by its own inputs and by what its siblings' steps say they will do; neither sees a
     * sibling jar that moved while the module stayed out of the run. The record is the step's own,
     * so a moved jar is positive evidence that the output is stale. No record, or a file that is
     * gone (a restore's to bring back), is no evidence; a record that will not read vouches for
     * nothing, and scheduling the module costs a cache hit.
     */
    static boolean bundledMoved(Path target) {
        boolean[] moved = {false};
        try {
            PathUtil.forEachChild(bundledDir(target), (file, attrs) -> {
                if (!attrs.isRegularFile()) return true;
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    String[] p = line.split(" ", 2);
                    Path input = p.length == 2 ? target.resolve(p[1]).normalize() : target;
                    moved[0] = p.length != 2
                            || !FileHashMemo.isSha256(p[0])
                            || (Files.isRegularFile(input) && !p[0].equals(FileHashMemo.contentHash(input)));
                    if (moved[0]) return false;
                }
                return true;
            });
            return moved[0];
        } catch (IOException | RuntimeException unreadable) {
            return true;
        }
    }

    /** Action cache with store-CAS fallback for Class-C blobs promoted by release. */
    static ActionCache packagingActionCache(Path cacheRoot) {
        return new ActionCache(JkStores.cacheCas(cacheRoot), CacheTree.ACTIONS.under(cacheRoot), JkStores.storeCas());
    }

    /** Test hook: {@link #storePackaged} under a rebuild session must still persist; {@code said} as there. */
    static void storePackagedForTest(
            Path cacheRoot,
            String taskId,
            String key,
            List<String> tokens,
            Path baseDir,
            List<Path> artifacts,
            boolean persist,
            List<String> said)
            throws IOException {
        if (persist) storeRecord(cacheRoot, taskId, key, tokens, baseDir, artifacts, said);
    }
}
