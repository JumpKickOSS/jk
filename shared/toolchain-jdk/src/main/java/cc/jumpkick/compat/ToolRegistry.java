// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Catalog of the build-tool distributions installed under {@code $JK_STORE_DIR/tools/}. Layout:
 * {@code <toolsRoot>/<slug>/<version>/}, where {@code <slug>} comes from {@link BuildTool} and
 * {@code <version>} is the upstream distribution version ({@code 3.9.9}, {@code 9.5.1}, {@code
 * 2.4.0}).
 *
 * <p>On the <strong>client-safe</strong> leaf rather than in the engine, because both sides ask:
 * the engine provisions into this layout and {@code jk tool list} / {@code jk tool uninstall} /
 * {@code jk doctor} read it. While it was engine-only the client could not reach it and
 * {@code jk doctor} grew its own copy of the {@code <slug>/<version>} walk — one layout, two
 * spellings, which is exactly the drift a rename half-lands in.
 */
public final class ToolRegistry {

    /**
     * The {@code jk mvn} / {@code jk gradle} option that installs an archive no checksum vouches
     * for and records its digest ({@link #acceptedDigest}); here, on the client-safe leaf, because
     * the CLI parses it and the engine's installer honours it.
     */
    public static final String ACCEPT_FLAG = "--accept-unverified-tool";

    /** The environment spelling of {@link #ACCEPT_FLAG}, for a CI step that cannot edit the command. */
    public static final String ACCEPT_ENV = "JK_ACCEPT_UNVERIFIED_TOOL";

    private final Path toolsRoot;

    public ToolRegistry(Path toolsRoot) {
        this.toolsRoot = Objects.requireNonNull(toolsRoot, "toolsRoot");
    }

    public Path toolsRoot() {
        return toolsRoot;
    }

    /** Installation directory for a given tool+version, whether or not it exists. */
    public Path installDir(BuildTool tool, String version) {
        return toolsRoot.resolve(tool.slug()).resolve(version);
    }

    /**
     * Where the digest of an archive accepted without a publisher's checksum is recorded: {@code
     * <toolsRoot>/<slug>/<version>.accepted.sha256}, beside the install directory it vouches for,
     * so it survives a purge of that directory and the next download is verified against it.
     */
    public Path acceptedDigest(BuildTool tool, String version) {
        return toolsRoot.resolve(tool.slug()).resolve(version + ".accepted.sha256");
    }

    public Optional<InstalledTool> find(BuildTool tool, String version) {
        Path dir = installDir(tool, version);
        return Files.isDirectory(dir) ? Optional.of(new InstalledTool(tool, version, dir)) : Optional.empty();
    }

    /** Usable installs of {@code tool}: a broken link is not one, so it is not listed. */
    public List<InstalledTool> list(BuildTool tool) throws IOException {
        return list(tool, false);
    }

    /**
     * As {@link #list}, but {@code includeBrokenLinks} also returns entries whose symlink target is
     * gone.
     *
     * <p>Both answers live here because both are questions about this layout. {@code jk doctor}
     * wants the broken ones — it exists to prune them — and had its own copy of the
     * {@code <slug>/<version>} walk to get them, which is one layout with two readers and one
     * rename away from disagreeing.
     */
    public List<InstalledTool> list(BuildTool tool, boolean includeBrokenLinks) throws IOException {
        Path slugDir = toolsRoot.resolve(tool.slug());
        if (!Files.exists(slugDir)) return List.of();
        List<InstalledTool> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(slugDir)) {
            stream.filter(p -> Files.isDirectory(p) || (includeBrokenLinks && Files.isSymbolicLink(p)))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .forEach(p ->
                            result.add(new InstalledTool(tool, p.getFileName().toString(), p)));
        }
        return result;
    }
}
