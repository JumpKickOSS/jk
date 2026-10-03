// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import java.net.URI;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A downloadable tool distribution. The tool's resolver produces these; the {@code ToolInstaller}
 * consumes them.
 *
 * <p>{@link #sha256} is the pin a wrapper's {@code distributionSha256Sum} or Node's {@code
 * SHASUMS256.txt} supplies, {@link #sha512} (hex) the one an npm registry's {@code dist.integrity}
 * does. With neither, the installer verifies the archive against a digest accepted for it earlier,
 * else the first {@link BuildTool#publishedChecksums() sidecar} the tool's publisher puts beside
 * it, and refuses the archive when none is available unless the run accepts it by name.
 */
public record ToolDistribution(
        BuildTool tool,
        String version,
        URI downloadUri,
        String archiveType,
        @Nullable String sha256,
        @Nullable String sha512) {

    public ToolDistribution {
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(downloadUri, "downloadUri");
        Objects.requireNonNull(archiveType, "archiveType");
        if (!archiveType.equals("zip") && !archiveType.equals("tar.gz")) {
            throw new IllegalArgumentException("unsupported archive type: " + archiveType);
        }
    }

    public ToolDistribution(BuildTool tool, String version, URI downloadUri, String archiveType) {
        this(tool, version, downloadUri, archiveType, null, null);
    }

    public ToolDistribution(
            BuildTool tool, String version, URI downloadUri, String archiveType, @Nullable String sha256) {
        this(tool, version, downloadUri, archiveType, sha256, null);
    }
}
