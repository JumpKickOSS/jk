// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.util.AccessLedger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Garbage collection of the artifacts the store itself holds under {@code <store>/repos/<id>}: those of
 * every repository the Maven local repository does not stand in for — a vendor's repository no
 * {@code settings.xml} names, such as JetBrains' or Google's — and of Central when {@code
 * [m2] integration = false} keeps it store-backed. These trees are jk's alone, so unlike {@code ~/.m2}
 * they are collected on jk's own account.
 *
 * <p>An artifact's countdown starts at its last use (the later of its memo's mtime, which {@code
 * RepoArtifactStore#verify} stamps, and the access ledger) and runs {@link #LARGE_TTL} for a file of
 * {@link #LARGE_BYTES} or more and {@link #TTL} for the rest, longer for one used on many days and
 * shorter on a full disk. A miss costs one download, checked against the lock like any other, so a
 * large file nobody has asked for in a week — an IDE distribution is hundreds of megabytes — has no
 * business staying. The first-party trees ({@code jk-local}, {@code jumpkick}) are what installs
 * shelve and are left to the install.
 */
public final class StoreRepoGc {

    private StoreRepoGc() {}

    /** Files at least this big are held for {@link #LARGE_TTL}. */
    public static final long LARGE_BYTES = 50L * 1024 * 1024;

    public static final Duration LARGE_TTL = Duration.ofDays(7);

    public static final Duration TTL = Duration.ofDays(30);

    private static final Duration MIN_AGE = Duration.ofHours(1);

    private static final Set<String> FIRST_PARTY = Set.of(RepoArtifactResolver.JK_LOCAL, RepositorySpec.JUMPKICK_NAME);

    /** What a pass found: artifacts removed (or that would be) and the bytes they held. */
    public record Report(int deleted, long freedBytes) {}

    public static Report run(Path storeRoot, double pressure, long nowMillis, boolean dryRun) throws IOException {
        Path repos = storeRoot.resolve("repos");
        List<Path> trees = new ArrayList<>();
        PathUtil.forEachChild(repos, (child, attrs) -> {
            if (attrs.isDirectory() && !FIRST_PARTY.contains(child.getFileName().toString())) trees.add(child);
            return true;
        });
        int deleted = 0;
        long freed = 0;
        for (Path tree : trees) {
            Map<String, AccessLedger.Use> uses =
                    AccessLedger.at(tree.resolve(RepoArtifactStore.ACCESS_DIR)).load();
            List<Path> memos = new ArrayList<>();
            PathUtil.forEachRegularFile(tree, RepoArtifactStore::isAccessDir, (file, attrs) -> {
                if (file.getFileName().toString().endsWith(".jk")) memos.add(file);
            });
            for (Path memo : memos) {
                Path artifact = M2Clean.artifactFor(tree, tree.relativize(memo));
                if (artifact == null) continue; // the artifact lives elsewhere (the local repository)
                long size = Files.size(artifact);
                AccessLedger.Use use = uses.get(RepoArtifactStore.useKey(tree, memo));
                long usedAt = Files.getLastModifiedTime(memo).toMillis();
                if (use != null) usedAt = Math.max(usedAt, use.lastMillis());
                double bonus = use == null || use.days() <= 1
                        ? 1.0
                        : Math.min(size >= LARGE_BYTES ? 2.0 : 3.0, 1.0 + 0.5 * Math.log(use.days()) / Math.log(2));
                Duration base = size >= LARGE_BYTES ? LARGE_TTL : TTL;
                long ttl = Math.max(MIN_AGE.toMillis(), (long) (base.toMillis() * bonus * pressure));
                if (nowMillis - usedAt < ttl) continue;
                if (!dryRun) M2Clean.delete(tree, artifact, memo);
                deleted++;
                freed += size;
            }
            if (!dryRun) {
                AccessLedger.at(tree.resolve(RepoArtifactStore.ACCESS_DIR))
                        .compact(key -> key.startsWith("m2:") && Files.exists(tree.resolve(key.substring(3))));
            }
        }
        return new Report(deleted, freed);
    }
}
