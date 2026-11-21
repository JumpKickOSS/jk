// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import cc.jumpkick.host.Log;
import cc.jumpkick.lock.LockManifestDigest;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * Writes a project's {@code jk-lock.toml} through {@link LockfileWriter} with a durable {@code
 * project-id}, then records the checkout under jk's builds directory so {@code project=<id>} finds it
 * without a prior build.
 */
public final class ProjectLocks {

    private ProjectLocks() {}

    /** Write {@code lockfile} to {@code file}, stamping the digest of the manifests on disk now. */
    public static Lockfile write(Lockfile lockfile, Path file) throws IOException {
        return write(lockfile, file, LockManifestDigest.compute(ownerOf(file)));
    }

    /**
     * Write {@code lockfile} to {@code file} with a caller-captured {@code manifestsSha256}, as
     * {@link LockfileWriter#write(Lockfile, Path, String)} takes it. Returns the lock with the {@code
     * project-id} the file records.
     */
    public static Lockfile write(Lockfile lockfile, Path file, String manifestsSha256) throws IOException {
        Path owner = ownerOf(file);
        Lockfile withId = withProjectId(lockfile, file, owner);
        LockfileWriter.write(withId, file, manifestsSha256);
        record(owner, withId);
        return withId;
    }

    /**
     * {@code lockfile} with a {@code project-id}: its own, else the one the lock at {@code file}
     * records (scanned from its head, so a torn lock with an intact head keeps its id), else a
     * recovered or freshly minted one. A rewrite never drops the id.
     */
    private static Lockfile withProjectId(Lockfile lockfile, Path file, Path owner) {
        if (lockfile.projectId() != null && !lockfile.projectId().isBlank()) return lockfile;
        Optional<String> existing = ProjectIdentity.recordedId(file);
        return existing.isPresent()
                ? lockfile.withProjectId(existing.get())
                : ProjectIdentity.ensureProjectId(lockfile, owner);
    }

    /**
     * Record the checkout in its project's {@code identity.toml}, re-memoize its id and count its
     * declared dependencies for the New wizard's library picker. Best-effort: the lock is already
     * durable. The identity comes from the lock in memory, since reading a megabyte lock back through
     * the TOML parser to learn the id just written is what pushed the engine past its heap.
     */
    private static void record(Path owner, Lockfile written) {
        try {
            refreshMemoizedId(owner);
            ProjectIdentity identity = ProjectIdentity.resolve(owner, written);
            Path home = ProjectBuilds.projectHome(ProjectBuilds.buildsRoot(), identity);
            ProjectIdentity.IdentityFile.write(home, identity);
            try {
                Set<String> deps = DeclaredDeps.collect(owner);
                DepFrequency.load().observe(identity.id(), deps).save();
            } catch (Exception frequency) {
                Log.debug("record: dependency frequency not counted", frequency);
            }
        } catch (Exception e) {
            Log.debug("record: checkout not recorded", e);
        }
    }

    /**
     * Re-memoize {@code owner}'s project id now that the lock on disk carries one. A first build
     * memoizes the path or git id at admission and then mints the lock id in its lock step; without
     * this, every action key and task pointer the rest of that build writes is tagged with an id no
     * later build resolves to. Both spellings of the directory, as given and real, are refreshed,
     * since the memo is keyed by the string a caller passed.
     */
    private static void refreshMemoizedId(Path owner) {
        ProjectIdCache.refresh(owner.toString());
        try {
            Path real = owner.toRealPath();
            if (!real.equals(owner)) ProjectIdCache.refresh(real.toString());
        } catch (IOException ignored) {
            // The directory was just written into; a real path that cannot be taken keeps the memo it has.
        }
    }

    /** The directory that owns {@code file}. A lockfile at a filesystem root has no owner. */
    private static Path ownerOf(Path file) {
        Path owner = file.toAbsolutePath().normalize().getParent();
        if (owner == null) throw new IllegalArgumentException("jk-lock.toml must live in a directory: " + file);
        return owner;
    }
}
