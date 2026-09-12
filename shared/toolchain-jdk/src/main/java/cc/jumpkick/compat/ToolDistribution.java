// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import java.net.URI;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Metadata for a downloadable Maven or Gradle distribution. The {@code MavenResolver} / {@code
 * GradleResolver} produces these; the {@code ToolInstaller} consumes them.
 *
 * <p>{@link #sha256} is the pin a wrapper's {@code distributionSha256Sum} supplies; when it is
 * absent the installer verifies the archive against the {@link BuildTool#publishedChecksum()
 * sidecar} the tool's publisher puts beside it, and refuses the archive when neither is available.
 */
public record ToolDistribution(
        BuildTool tool,
        String version,
        URI downloadUri,
        String archiveType,
        @Nullable String sha256) {

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
        this(tool, version, downloadUri, archiveType, null);
    }
}
