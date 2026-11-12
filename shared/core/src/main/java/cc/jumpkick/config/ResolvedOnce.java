// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A module's resolved manifest ({@link JkBuildParser#parse(Path)}), once per request for each
 * manifest as read. A build asks for the same module's resolved manifest from every step that reads
 * it, and each resolve walks the ancestors for the root, re-applies the root and probes the module's
 * layout.
 *
 * <p>The key is the module, its {@link JkBuildParser#parseLocal(Path)} instance, the root's
 * {@code parseLocal} instance and whether the session qualifies snapshots. A parse instance is new
 * whenever its file's stamp or a user pin moves, and on every ask while it is provisional, so a
 * manifest written mid-request (a {@code jk add}, an edited root) is resolved again rather than
 * answered from the memo. A text not yet written ({@link JkBuildParser#parse(String, Path)}) never
 * comes here.
 */
final class ResolvedOnce {

    private ResolvedOnce() {}

    /** The resolve proper: {@code local} with the workspace and the user's pins applied. */
    @FunctionalInterface
    interface Resolve {
        JkBuild apply(Path moduleDir, JkBuild local);
    }

    /** The as-written parse of a manifest, as {@link JkBuildParser#parseLocal(Path)} reads it. */
    @FunctionalInterface
    interface ReadLocal {
        JkBuild read(Path manifest) throws IOException;
    }

    static JkBuild of(Path moduleDir, JkBuild local, ReadLocal readLocal, Resolve resolve) {
        RequestScope scope = RequestScope.current();
        if (!scope.remembers()) return resolve.apply(moduleDir, local);
        JkBuild root;
        try {
            Optional<Path> rootDir = WorkspaceLocator.findRoot(moduleDir);
            root = rootDir.isEmpty() ? null : readLocal.read(ManifestPaths.manifestIn(rootDir.get()));
        } catch (IOException | RuntimeException unreadable) {
            // The resolve reports or tolerates a broken root as it always has; only an answer is kept.
            return resolve.apply(moduleDir, local);
        }
        return scope.get(
                new Key(moduleDir, local, root, SnapshotQualifier.active()), k -> resolve.apply(moduleDir, local));
    }

    /** The module directory by value, both parses by identity. */
    private static final class Key {
        private final Path moduleDir;
        private final JkBuild local;
        private final @Nullable JkBuild root;
        private final boolean snapshot;

        Key(Path moduleDir, JkBuild local, @Nullable JkBuild root, boolean snapshot) {
            this.moduleDir = moduleDir;
            this.local = local;
            this.root = root;
            this.snapshot = snapshot;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k
                    && k.local == local
                    && k.root == root
                    && k.snapshot == snapshot
                    && k.moduleDir.equals(moduleDir);
        }

        @Override
        public int hashCode() {
            return (moduleDir.hashCode() * 31 + System.identityHashCode(local)) * 31 + System.identityHashCode(root);
        }
    }
}
