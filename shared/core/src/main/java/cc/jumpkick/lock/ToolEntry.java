// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import cc.jumpkick.model.Coordinate;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * One {@code [[tool]]} row: a plugin step tool's {@code group:artifact}, version and packaging
 * {@code type}, pinned by digest. A tool that is one artifact on every host carries its
 * {@code classifier} (null for none) and a {@link #checksum} ({@code sha256:<hex>}); a tool whose
 * classifier follows the host carries {@link #sha256}, classifier to hex, one entry per platform
 * that publishes it.
 */
public record ToolEntry(
        String coordinate,
        String version,
        String type,
        @Nullable String classifier,
        @Nullable String checksum,
        Map<String, String> sha256) {
    public ToolEntry {
        Objects.requireNonNull(coordinate, "coordinate");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(type, "type");
        sha256 = sha256 == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(sha256));
        if ((checksum == null) == sha256.isEmpty()) {
            throw new IllegalArgumentException("[[tool]] " + coordinate + ":" + version
                    + " needs exactly one of `checksum` or per-platform `sha256`");
        }
        if (checksum != null && !checksum.startsWith("sha256:")) {
            throw new IllegalArgumentException("[[tool]] " + coordinate + " checksum must be sha256:<hex>");
        }
        if (!sha256.isEmpty() && classifier != null) {
            throw new IllegalArgumentException(
                    "[[tool]] " + coordinate + " names a `classifier` beside per-platform `sha256`");
        }
    }

    /** A tool that is one artifact on every host. */
    public static ToolEntry single(Coordinate coord, String sha256Hex) {
        return new ToolEntry(
                coord.module(), coord.version(), coord.type(), coord.classifier(), "sha256:" + sha256Hex, Map.of());
    }

    /** True when this row is {@code coord}'s module, version and type, whatever the classifier. */
    public boolean names(Coordinate coord) {
        return coordinate.equals(coord.module()) && version.equals(coord.version()) && type.equals(coord.type());
    }

    /** True when this row pins {@code coord}'s bytes: its module, version, type and classifier. */
    public boolean pins(Coordinate coord) {
        return sha256Hex(coord.classifier()) != null && names(coord);
    }

    /** The hex digest pinned for {@code classifier}, or null when this row pins none for it. */
    public @Nullable String sha256Hex(@Nullable String classifier) {
        if (!sha256.isEmpty()) return classifier == null ? null : sha256.get(classifier);
        if (!Objects.equals(this.classifier, classifier) || checksum == null) return null;
        return checksum.substring("sha256:".length());
    }
}
