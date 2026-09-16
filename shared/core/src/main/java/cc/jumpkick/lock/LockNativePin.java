// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.VersionSelector;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The one {@code [native] metadata-repository} selector a {@code jk-lock.toml} locks.
 *
 * <p>A lock has one reachability-metadata pin because it has one extracted tree, and a workspace
 * has one lock. Members may each declare {@code [native]} — jk's own workspace has it on the CLI
 * module and nowhere else — so the selector is gathered across every manifest the lock owns rather
 * than read off the root, which for jk declares no {@code [native]} at all and would have pinned
 * nothing.
 *
 * <p>Disagreement is an error, not a silent winner. Two members asking for different repository
 * releases is a question only the author can answer, and picking one would make the image depend on
 * which manifest the loader happened to visit first.
 */
public final class LockNativePin {

    private LockNativePin() {}

    /**
     * The selector to resolve for the project or workspace rooted at {@code lockOwnerDir}, or empty
     * when no manifest under it builds a native image. Manifests that enable one without naming a
     * release contribute {@link JkBuild.NativeConfig#METADATA_REPOSITORY_DEFAULT}.
     *
     * @throws IllegalStateException when two manifests declare different selectors
     */
    public static Optional<VersionSelector> selector(Path lockOwnerDir) throws IOException {
        Path dir = lockOwnerDir.toAbsolutePath().normalize();
        Path toml = ManifestPaths.manifestIn(dir);
        if (!Files.isRegularFile(toml)) return Optional.empty();

        // raw selector -> the manifest that asked for it, for the conflict message.
        Map<String, String> byRaw = new LinkedHashMap<>();
        JkBuild root = JkBuildParser.parseLocal(toml);
        collect(byRaw, ".", root);
        if (root.isWorkspaceRoot()) {
            for (Map.Entry<Path, JkBuild> e :
                    WorkspaceLoader.loadModules(dir, root).entrySet()) {
                String rel = dir.relativize(e.getKey()).toString().replace('\\', '/');
                collect(byRaw, rel.isEmpty() ? "." : rel, e.getValue());
            }
        }
        if (byRaw.isEmpty()) return Optional.empty();
        if (byRaw.size() > 1) {
            StringBuilder sb = new StringBuilder("[native] metadata-repository disagrees across the workspace:");
            byRaw.forEach((raw, where) -> sb.append("\n  ")
                    .append(where)
                    .append("/jk.toml wants `")
                    .append(raw)
                    .append('`'));
            sb.append("\nOne lock pins one repository release — make them agree.");
            throw new IllegalStateException(sb.toString());
        }
        return Optional.of(VersionSelector.parse(byRaw.keySet().iterator().next()));
    }

    /**
     * A module contributes iff it can actually build a native image, which is not the same as
     * declaring {@code [native]}: {@code [application] native = true} enables one with no table at
     * all (and would otherwise silently lose every third-party config it has today), while
     * {@code [native] enabled = false} declares a table and builds nothing (and would otherwise pay
     * a lock-time download for an image it never produces). {@link JkBuild#nativeImage()} is the one
     * predicate that answers the question both ways.
     */
    private static void collect(Map<String, String> byRaw, String where, JkBuild build) {
        if (!build.nativeImage()) return;
        VersionSelector selector = build.nativeConfigOpt()
                .map(JkBuild.NativeConfig::metadataRepository)
                .orElse(JkBuild.NativeConfig.METADATA_REPOSITORY_DEFAULT);
        byRaw.putIfAbsent(selector.raw(), where);
    }
}
