// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock.testing;

import cc.jumpkick.lock.LockManifestDigest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * The {@code jk-lock.toml} a committed project carries, for a test fixture that hand-writes one:
 * stamped with the digest of the manifests it was derived from ({@code manifests-sha256}) and naming
 * a project id of its own, so a build reads it as fresh and never re-locks it.
 *
 * <p>An unstamped lock is stale whatever its pins say ({@link cc.jumpkick.lock.LockFreshness}), so
 * a build over one re-locks first. In the fast test tier the network is walled off, so that re-lock
 * resolves from the store alone: it passes on a machine whose store already holds what the
 * fixture's default test dependencies name and fails the build where the store is empty (a fresh CI
 * runner), since a stale lock that cannot be updated stops the build. A fixture that means to
 * exercise a stale lock writes one by hand and says so; it must not need the developer's store.
 *
 * <p>Each lock names a new project id, as {@code jk lock} mints one: build history is kept per project
 * id (its build numbers included), so two fixtures sharing one would number each other's builds. A
 * copy of the fixture's directory keeps the id, so the copy is the same project, as a second
 * checkout is.
 *
 * <p>Write the lock after every {@code jk.toml} it covers: the digest is of the manifests on disk.
 */
public final class CommittedLocks {

    private CommittedLocks() {}

    /** A fresh lock with no pins for the project or workspace root {@code owner}; returns its path. */
    public static Path write(Path owner) throws IOException {
        return write(owner, "");
    }

    /**
     * A fresh lock for {@code owner} with {@code body} after its header: more top-level keys first
     * ({@code jk-min = "…"}), then tables ({@code [[artifact]]}, {@code [node]}, …).
     */
    public static Path write(Path owner, String body) throws IOException {
        String digest = LockManifestDigest.compute(owner);
        String projectId = UUID.randomUUID().toString().replace("-", "");
        String header = """
                version = 1
                generated-by = "jk test"
                resolution-algorithm = "pubgrub-v1"
                manifests-sha256 = "%s"
                project-id = "%s"
                """.formatted(digest, projectId);
        Path lock = owner.resolve("jk-lock.toml");
        Files.writeString(lock, header + body);
        return lock;
    }
}
