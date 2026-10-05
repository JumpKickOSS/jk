// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.node;

import cc.jumpkick.compat.BuildTool;
import cc.jumpkick.compat.DownloadOrigin;
import cc.jumpkick.compat.ToolDistribution;
import java.net.URI;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * An exact Node release and the sha256 of its archive per platform ({@link NodePlatform#key()} →
 * hex): what a lock records, and all an install on any of those platforms needs.
 */
public record NodeResolution(
        String version, @Nullable String npm, @Nullable String lts, Map<String, String> sha256) {

    public NodeResolution {
        Objects.requireNonNull(version, "version");
        sha256 = Map.copyOf(sha256);
    }

    /** The archive for {@code platform} under {@code distBase}, pinned to its digest. */
    public ToolDistribution distribution(NodePlatform platform, URI distBase) {
        String sha = sha256.get(platform.key());
        if (sha == null) {
            throw new IllegalArgumentException(
                    "Node " + version + " publishes no " + platform.archiveType() + " archive for " + platform.key());
        }
        URI base = DownloadOrigin.directory(distBase.toString());
        return new ToolDistribution(
                BuildTool.NODE,
                version,
                base.resolve("v" + version + "/" + platform.archiveName(version)),
                platform.archiveType(),
                sha);
    }
}
