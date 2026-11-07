// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.VersionSelector;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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
 *
 * <p>A closure a plugin resolves itself ({@code [[contribute.lock-closure]]}) is a resolved row: the
 * {@link #resolvedBy} plugin and closure name ({@code quarkus/deployment}), the {@link #inputs}
 * digest of what the answer was resolved from, and every file of it, POMs among them ({@code
 * !pom}), each with its digest on one line ({@code <coordinate> sha256:<hex>}, written as {@code
 * files}, held in {@link #closure}): hundreds of files, so one line each rather than a row each.
 * Each row names the module it answers for ({@link #module}, a path under the lock; none for the
 * lock's own) and the {@link #plugin} that answered ({@link LockClosureStamp}).
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
        List<String> closure,
        @Nullable String resolvedBy,
        @Nullable String inputs,
        @Nullable String module,
        @Nullable String plugin) {

    /** The {@link #type} of a URL row. */
    public static final String FILE = "file";

    /** The {@link #type} of a resolved row. */
    public static final String RESOLVED = "resolved";

    public ToolEntry {
        Objects.requireNonNull(type, "type");
        sha256 = sha256 == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(sha256));
        with = with == null ? List.of() : List.copyOf(with);
        List<String> jars = closure == null ? new ArrayList<>() : new ArrayList<>(closure);
        Collections.sort(jars);
        closure = List.copyOf(jars);
        String name = url != null ? url : resolvedBy != null ? resolvedBy : coordinate;
        if (resolvedBy != null) {
            if (url != null
                    || coordinate != null
                    || version != null
                    || classifier != null
                    || checksum != null
                    || !sha256.isEmpty()
                    || !with.isEmpty()
                    || managedBy != null) {
                throw new IllegalArgumentException(
                        "[[tool]] " + resolvedBy + " names `resolved-by`, `inputs` and `files` alone");
            }
            if (!isDigest(inputs)) {
                throw new IllegalArgumentException("[[tool]] " + resolvedBy + " inputs must be sha256:<hex>");
            }
            for (String file : closure) {
                int at = file.indexOf(" sha256:");
                if (at <= 0 || !isDigest(file.substring(at + 1))) {
                    throw new IllegalArgumentException(
                            "[[tool]] " + resolvedBy + " file `" + file + "` must be `<coordinate> sha256:<hex>`");
                }
            }
            if (plugin == null || plugin.isBlank()) {
                throw new IllegalArgumentException("[[tool]] " + resolvedBy + " names no `plugin` that answered it");
            }
            if (module != null && (module.isBlank() || module.startsWith("/") || module.contains(".."))) {
                throw new IllegalArgumentException(
                        "[[tool]] " + resolvedBy + " module must be a path under the lock — got: " + module);
            }
        } else if (inputs != null || module != null || plugin != null) {
            throw new IllegalArgumentException(
                    "[[tool]] " + name + " names `inputs`, `module` or `plugin` without `resolved-by`");
        } else if (url != null) {
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
                    throw new IllegalArgumentException(
                            "[[tool]] " + coordinate + ":" + version + " sets both `closure` and a digest; keep one");
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
        if (checksum != null && !isDigest(checksum)) {
            throw new IllegalArgumentException("[[tool]] " + name + " checksum must be sha256:<hex>");
        }
        for (Map.Entry<String, String> platform : sha256.entrySet()) {
            if (!Hashing.isHex(platform.getValue(), 64)) {
                throw new IllegalArgumentException(
                        "[[tool]] " + name + " sha256." + platform.getKey() + " must be 64 hex digits");
            }
        }
    }

    /**
     * True for {@code sha256:} and 64 hex digits. A digest names a file in the store, so anything
     * else — a path segment, a {@code ..} — is refused where the lock is read, never resolved.
     */
    private static boolean isDigest(@Nullable String value) {
        return value != null && value.startsWith("sha256:") && Hashing.isHex(value.substring("sha256:".length()), 64);
    }

    /** A row that is not {@linkplain #isResolved resolved}. */
    public ToolEntry(
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
        this(
                coordinate,
                version,
                type,
                classifier,
                checksum,
                sha256,
                url,
                with,
                managedBy,
                closure,
                null,
                null,
                null,
                null);
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

    /**
     * The closure {@code resolvedBy} ({@code <plugin>/<name>}) answered for the module at {@code
     * module} (a path under the lock; null for the lock's own module) by {@code plugin} (its
     * {@link LockClosureStamp}), for inputs digesting to {@code inputsHex}: each file ({@code
     * group:artifact:version[:classifier][!type]}) to the hex digest of its bytes.
     */
    public static ToolEntry resolved(
            String resolvedBy,
            @Nullable String module,
            String plugin,
            String inputsHex,
            Map<Coordinate, String> files) {
        List<String> specs = new ArrayList<>();
        for (Map.Entry<Coordinate, String> file : files.entrySet()) {
            specs.add(file.getKey() + " sha256:" + file.getValue());
        }
        return new ToolEntry(
                null,
                null,
                RESOLVED,
                null,
                null,
                Map.of(),
                null,
                List.of(),
                null,
                specs,
                resolvedBy,
                "sha256:" + inputsHex,
                module == null || module.isEmpty() ? null : module,
                plugin);
    }

    /**
     * The row of {@code rows} answering {@code resolvedBy} for the module at {@code module} (a path
     * under the lock; empty or null for the lock's own module), or null when none does.
     */
    public static @Nullable ToolEntry resolvedIn(List<ToolEntry> rows, String resolvedBy, @Nullable String module) {
        String wanted = module == null || module.isEmpty() ? null : module;
        for (ToolEntry row : rows) {
            if (resolvedBy.equals(row.resolvedBy()) && Objects.equals(wanted, row.module())) return row;
        }
        return null;
    }

    /** The hex digest of the inputs a resolved row was answered for. */
    public String inputsHex() {
        return Objects.requireNonNull(inputs, "inputs").substring("sha256:".length());
    }

    /** True for a closure a plugin resolved ({@link #resolvedBy}). */
    public boolean isResolved() {
        return resolvedBy != null;
    }

    /** A resolved row's files, each to the hex digest it pins, in coordinate order; empty for any other row. */
    public Map<Coordinate, String> files() {
        Map<Coordinate, String> out = new LinkedHashMap<>();
        if (resolvedBy == null) return out;
        for (String file : closure) {
            int at = file.indexOf(" sha256:");
            out.put(Coordinate.parse(file.substring(0, at)), file.substring(at + " sha256:".length()));
        }
        return out;
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

    /** True for a closure row of a {@code transitive} tool; a {@linkplain #isResolved resolved} row is not one. */
    public boolean isClosure() {
        return resolvedBy == null && !closure.isEmpty();
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
                && resolvedBy == null
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
