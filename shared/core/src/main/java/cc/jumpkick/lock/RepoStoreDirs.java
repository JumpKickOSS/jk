// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.model.RepositorySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Which directories under {@code <store>/repos/} are repository stores anyone may read. A store is
 * keyed by the origin that fills it; the tree says so in {@link ManifestPaths#REPO_ORIGIN}. Three
 * public origins keep reserved directories that predate the marker, and the first-party shelf has no
 * origin at all. Every other unmarked directory was keyed by a project's <em>name</em> for a
 * repository — nothing can say which origin filled it, so no lookup reads it.
 *
 * <p>Lives beside the lock vocabulary because the guard evaluators and the rule-pack loader need
 * the same answer as the resolver, and neither links the store implementation.
 */
public final class RepoStoreDirs {

    private RepoStoreDirs() {}

    /** True for {@code central}, {@code google} and {@code jumpkick}: public origins with reserved directories. */
    public static boolean isReservedName(String name) {
        return RepositorySpec.CENTRAL.equals(name)
                || RepositorySpec.GOOGLE.equals(name)
                || RepositorySpec.JUMPKICK_NAME.equals(name);
    }

    /** True when {@code dir} is a store some lookup may read: the shelf, a reserved origin, or a marked tree. */
    public static boolean isKnown(Path dir) {
        String name = String.valueOf(dir.getFileName());
        return RepositorySpec.JK_LOCAL.equals(name)
                || isReservedName(name)
                || Files.isRegularFile(dir.resolve(ManifestPaths.REPO_ORIGIN));
    }

    /** Every readable store directory under {@code <storeRoot>/repos/}, sorted; empty for a cold store. */
    public static List<Path> known(Path storeRoot) {
        List<Path> out = new ArrayList<>();
        try {
            PathUtil.forEachChild(storeRoot.resolve("repos"), (child, attrs) -> {
                if (attrs.isDirectory() && isKnown(child)) out.add(child);
                return true;
            });
        } catch (IOException e) {
            return List.of();
        }
        out.sort(null);
        return List.copyOf(out);
    }
}
