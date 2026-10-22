// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.VersionSelector;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
 *
 * <p>A tool fetched as a runtime closure is a closure row: its root's coordinate, the other roots
 * resolved {@link #with} it, the BOM it is {@link #managedBy}, and every jar of the resolved
 * {@link #closure} as a coordinate ({@code group:artifact:version[:classifier][!type]}), sorted.
 * It carries no digest: each jar is an artifact row of its own.
 */
public record ToolEntry(
        @Nullable String coordinate,
        @Nullable String version,
        String type,
        @Nullable String classifier,
        @Nullable String checksum,
        Map<String, String> sha256,
        @Nullable String url,
        List<String> with,
        @Nullable String managedBy,
        List<String> closure) {

    /** The {@link #type} of a URL row. */
    public static final String FILE = "file";

    public ToolEntry {
        Objects.requireNonNull(type, "type");
        sha256 = sha256 == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(sha256));
        with = with == null ? List.of() : List.copyOf(with);
        List<String> jars = closure == null ? new ArrayList<>() : new ArrayList<>(closure);
        Collections.sort(jars);
        closure = List.copyOf(jars);
        String name = url != null ? url : coordinate;
        if (url != null) {
            if (coordinate != null
                    || version != null
                    || classifier != null
                    || !sha256.isEmpty()
                    || checksum == null
                    || !closure.isEmpty()) {
                throw new IllegalArgumentException("[[tool]] " + url + " names a `url` and a `checksum` alone");
            }
        } else {
            Objects.requireNonNull(coordinate, "coordinate");
            Objects.requireNonNull(version, "version");
            if (!closure.isEmpty()) {
                if (checksum != null || !sha256.isEmpty()) {
                    throw new IllegalArgumentException("[[tool]] " + coordinate + ":" + version
                            + " names a `closure` beside a digest: each jar of a closure is a row of its own");
                }
            } else if (!with.isEmpty() || managedBy != null) {
                throw new IllegalArgumentException(
                        "[[tool]] " + coordinate + " names `with` or `managed-by` without a `closure`");
            } else if ((checksum == null) == sha256.isEmpty()) {
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
        this(coordinate, version, type, classifier, checksum, sha256, null, List.of(), null, List.of());
    }

    /** A tool that is one artifact on every host. */
    public static ToolEntry single(Coordinate coord, String sha256Hex) {
        return new ToolEntry(
                coord.module(), coord.version(), coord.type(), coord.classifier(), "sha256:" + sha256Hex, Map.of());
    }

    /** A file fetched from {@code url}. */
    public static ToolEntry atUrl(String url, String sha256Hex) {
        return new ToolEntry(null, null, FILE, null, "sha256:" + sha256Hex, Map.of(), url, List.of(), null, List.of());
    }

    /** A runtime closure rooted at {@code root}, with {@code with} and {@code managedBy} as resolved. */
    public static ToolEntry closureOf(
            Coordinate root, List<Coordinate> with, @Nullable String managedBy, List<Coordinate> jars) {
        List<String> withSpecs = new ArrayList<>();
        for (Coordinate w : with) withSpecs.add(w.toString());
        List<String> jarSpecs = new ArrayList<>();
        for (Coordinate jar : jars) jarSpecs.add(jar.toString());
        return new ToolEntry(
                root.module(),
                root.version(),
                root.type(),
                root.classifier(),
                null,
                Map.of(),
                null,
                withSpecs,
                managedBy,
                jarSpecs);
    }

    /** The row of {@code rows} that {@link #isClosureOf} a tool's specs, or null when none is. */
    public static @Nullable ToolEntry closureIn(
            List<ToolEntry> rows, String rootSpec, List<String> withSpecs, @Nullable String managedBySpec) {
        for (ToolEntry row : rows) {
            if (row.isClosureOf(rootSpec, withSpecs, managedBySpec)) return row;
        }
        return null;
    }

    /** The hex digest a row of {@code rows} pins for the file at {@code url}, or null when none does. */
    public static @Nullable String urlSha256In(List<ToolEntry> rows, String url) {
        for (ToolEntry row : rows) {
            if (url.equals(row.url())) return row.sha256Hex(null);
        }
        return null;
    }

    /** True for a closure row. */
    public boolean isClosure() {
        return !closure.isEmpty();
    }

    /**
     * True when this is the closure row of a tool rooted at {@code rootSpec} with {@code withSpecs}
     * and {@code managedBySpec} as a manifest names them. A spec with an exact version matches that
     * version; a floating one ({@code ^3.38}) matches the version this row resolved it to.
     */
    public boolean isClosureOf(String rootSpec, List<String> withSpecs, @Nullable String managedBySpec) {
        if (!isClosure() || !specMatches(rootSpec, root()) || with.size() != withSpecs.size()) return false;
        for (int i = 0; i < withSpecs.size(); i++) {
            if (!specMatches(withSpecs.get(i), Coordinate.parse(with.get(i)))) return false;
        }
        return managedBySpec == null
                ? managedBy == null
                : managedBy != null && specMatches(managedBySpec, Coordinate.parse(managedBy));
    }

    private static boolean specMatches(String spec, Coordinate resolved) {
        Coordinate raw = Coordinate.parse(spec);
        if (!raw.module().equals(resolved.module())
                || !Objects.equals(raw.classifier(), resolved.classifier())
                || !raw.type().equals(resolved.type())) {
            return false;
        }
        return !(VersionSelector.parse(raw.version()) instanceof VersionSelector.Exact exact)
                || exact.version().equals(resolved.version());
    }

    /** The closure's root, as a coordinate; this row must be a closure row. */
    public Coordinate root() {
        return new Coordinate(
                Objects.requireNonNull(coordinate).substring(0, coordinate.indexOf(':')),
                coordinate.substring(coordinate.indexOf(':') + 1),
                Objects.requireNonNull(version),
                classifier,
                type);
    }

    /** True when this row is {@code coord}'s module, version and type, whatever the classifier. */
    public boolean names(Coordinate coord) {
        return url == null
                && closure.isEmpty()
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
