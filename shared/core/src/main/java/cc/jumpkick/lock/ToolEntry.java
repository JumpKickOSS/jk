// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import cc.jumpkick.model.Coordinate;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * One {@code [[tool]]} row: a tool a plugin fetches, pinned by digest. A Maven artifact row names
 * its {@code group:artifact}, version and packaging {@code type}; one that is the same artifact on
 * every host carries its {@code classifier} (null for none) and a {@link #checksum} ({@code
 * sha256:<hex>}), and one whose classifier follows the host carries {@link #sha256}, classifier to
 * hex, one entry per platform that publishes it. A file fetched from a {@link #url} carries the URL
 * and its {@link #checksum} alone, one row per URL.
 */
public record ToolEntry(
        @Nullable String coordinate,
        @Nullable String version,
        String type,
        @Nullable String classifier,
        @Nullable String checksum,
        Map<String, String> sha256,
        @Nullable String url) {

    /** The {@link #type} of a URL row. */
    public static final String FILE = "file";

    public ToolEntry {
        Objects.requireNonNull(type, "type");
        sha256 = sha256 == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(sha256));
        String name = url != null ? url : coordinate;
        if (url != null) {
            if (coordinate != null || version != null || classifier != null || !sha256.isEmpty() || checksum == null) {
                throw new IllegalArgumentException("[[tool]] " + url + " names a `url` and a `checksum` alone");
            }
        } else {
            Objects.requireNonNull(coordinate, "coordinate");
            Objects.requireNonNull(version, "version");
            if ((checksum == null) == sha256.isEmpty()) {
                throw new IllegalArgumentException("[[tool]] " + coordinate + ":" + version
                        + " needs exactly one of `checksum` or per-platform `sha256`");
            }
            if (!sha256.isEmpty() && classifier != null) {
                throw new IllegalArgumentException(
                        "[[tool]] " + coordinate + " names a `classifier` beside per-platform `sha256`");
            }
        }
        if (checksum != null && !checksum.startsWith("sha256:")) {
            throw new IllegalArgumentException("[[tool]] " + name + " checksum must be sha256:<hex>");
        }
    }

    /** A Maven artifact row. */
    public ToolEntry(
            String coordinate,
            String version,
            String type,
            @Nullable String classifier,
            @Nullable String checksum,
            Map<String, String> sha256) {
        this(coordinate, version, type, classifier, checksum, sha256, null);
    }

    /** A tool that is one artifact on every host. */
    public static ToolEntry single(Coordinate coord, String sha256Hex) {
        return new ToolEntry(
                coord.module(), coord.version(), coord.type(), coord.classifier(), "sha256:" + sha256Hex, Map.of());
    }

    /** A file fetched from {@code url}. */
    public static ToolEntry atUrl(String url, String sha256Hex) {
        return new ToolEntry(null, null, FILE, null, "sha256:" + sha256Hex, Map.of(), url);
    }

    /** True when this row is {@code coord}'s module, version and type, whatever the classifier. */
    public boolean names(Coordinate coord) {
        return url == null
                && coord.module().equals(coordinate)
                && coord.version().equals(version)
                && type.equals(coord.type());
    }

    /** True when this row pins {@code coord}'s bytes: its module, version, type and classifier. */
    public boolean pins(Coordinate coord) {
        return names(coord) && sha256Hex(coord.classifier()) != null;
    }

    /** The hex digest pinned for {@code classifier}, or null when this row pins none for it. */
    public @Nullable String sha256Hex(@Nullable String classifier) {
        if (!sha256.isEmpty()) return classifier == null ? null : sha256.get(classifier);
        if (!Objects.equals(this.classifier, classifier) || checksum == null) return null;
        return checksum.substring("sha256:".length());
    }
}
