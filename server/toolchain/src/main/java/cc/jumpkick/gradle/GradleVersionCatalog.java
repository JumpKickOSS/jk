// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * Reads a Gradle version catalog ({@code gradle/libs.versions.toml}) and resolves type-safe
 * accessors — {@code libs.junit.platform.launcher}, {@code libs.bundles.testing} — back to concrete
 * coordinates so {@link GradleImporter} can turn catalog-based dependency declarations into
 * jk.toml entries.
 *
 * <p>Accessor mapping mirrors Gradle: an alias's {@code -}, {@code _} and {@code .} separators are
 * all folded to {@code .}, so the alias {@code junit-platform-launcher} is reached as {@code
 * libs.junit.platform.launcher}. Library values may use the table form ({@code { module = "g:a",
 * version.ref = "x" }} or {@code { group, name, version }}) or the string form ({@code "g:a:v"});
 * versions resolve through the {@code [versions]} table, including rich {@code strictly}/{@code
 * require}/ {@code prefer} forms.
 *
 * <p>Version-less libraries (BOM-managed) resolve to bare {@code group:artifact}. Unresolved
 * {@code version.ref} values are recorded in {@link #parseNotes()} rather than silently dropped.
 */
public final class GradleVersionCatalog {

    /** accessor (dot-folded) → {@code group:artifact:version} or version-less {@code group:artifact}. */
    private final Map<String, String> coordinates;

    /** bundle accessor (dot-folded) → module library accessors. */
    private final Map<String, List<String>> bundles;

    /** version accessor (dot-folded) → the {@code [versions]} entry's value. */
    private final Map<String, String> versions;

    /** Non-fatal parse notes (unresolved version.ref, malformed entries). */
    private final List<String> parseNotes;

    private GradleVersionCatalog(
            Map<String, String> coordinates,
            Map<String, List<String>> bundles,
            Map<String, String> versions,
            List<String> parseNotes) {
        this.coordinates = coordinates;
        this.bundles = bundles;
        this.versions = versions;
        this.parseNotes = List.copyOf(parseNotes);
    }

    /**
     * Walk up from {@code projectDir} looking for {@code gradle/libs.versions.toml}, checking the
     * project directory and then its parent. Returns the first match, or empty once the search would
     * climb above the project's parent directory.
     */
    public static Optional<Path> locate(Path projectDir) {
        if (projectDir == null) return Optional.empty();
        Path dir = projectDir.toAbsolutePath().normalize();
        Path stopAfter = dir.getParent(); // the project's parent dir is the last place we look
        while (dir != null) {
            Path candidate = dir.resolve("gradle").resolve("libs.versions.toml");
            if (Files.isRegularFile(candidate)) return Optional.of(candidate);
            if (dir.equals(stopAfter)) break;
            dir = dir.getParent();
        }
        return Optional.empty();
    }

    /**
     * Locate and parse the catalog for {@code projectDir}; empty if none found or it fails to parse.
     */
    public static Optional<GradleVersionCatalog> forProject(Path projectDir) {
        return locate(projectDir).flatMap(catalog -> {
            try {
                return Optional.of(parse(catalog));
            } catch (IOException e) {
                return Optional.empty();
            }
        });
    }

    public static GradleVersionCatalog parse(Path catalogFile) throws IOException {
        return fromToml(Toml.parse(catalogFile));
    }

    /** Parse from a TOML string (tests and in-memory fixtures). */
    public static GradleVersionCatalog parseToml(String toml) {
        return fromToml(Toml.parse(toml));
    }

    static GradleVersionCatalog fromToml(TomlParseResult toml) {
        List<String> notes = new ArrayList<>();
        Map<String, String> versions = new LinkedHashMap<>();
        TomlTable versionsTable = toml.getTable("versions");
        if (versionsTable != null) {
            for (String key : versionsTable.keySet()) {
                String v = readVersion(versionsTable.get(key));
                if (v != null) versions.put(key, v);
            }
        }

        Map<String, String> coordinates = new LinkedHashMap<>();
        TomlTable libraries = toml.getTable("libraries");
        if (libraries != null) {
            flattenLibraries(libraries, "", versions, coordinates, notes);
        }

        Map<String, List<String>> bundles = new LinkedHashMap<>();
        TomlTable bundlesTable = toml.getTable("bundles");
        if (bundlesTable != null) {
            for (String key : bundlesTable.keySet()) {
                TomlArray arr = bundlesTable.getArray(key);
                if (arr == null) continue;
                List<String> modules = new ArrayList<>();
                for (int i = 0; i < arr.size(); i++) {
                    if (arr.get(i) instanceof String s) modules.add(accessor(s));
                }
                bundles.put(accessor(key), modules);
            }
        }
        Map<String, String> versionAccessors = new LinkedHashMap<>();
        versions.forEach((key, value) -> versionAccessors.put(accessor(key), value));
        return new GradleVersionCatalog(coordinates, bundles, versionAccessors, notes);
    }

    /** Notes from parse (unresolved version.ref, …). Never null. */
    public List<String> parseNotes() {
        return parseNotes;
    }

    /** The {@code [versions]} entry a {@code libs.versions.<alias>} accessor (catalog name stripped) names. */
    public Optional<String> resolveVersion(String accessorPath) {
        return Optional.ofNullable(versions.get(accessorPath));
    }

    /**
     * Resolve a library accessor (catalog name already stripped) to {@code g:a:v} or version-less
     * {@code g:a}.
     */
    public Optional<String> resolveLibrary(String accessorPath) {
        return Optional.ofNullable(coordinates.get(accessorPath));
    }

    /**
     * Resolve a bundle accessor to module coordinates. Missing library members are listed in
     * {@link BundleResolution#missingMembers()} — never silently omitted without a record.
     */
    public Optional<BundleResolution> resolveBundle(String accessorPath) {
        List<String> modules = bundles.get(accessorPath);
        if (modules == null) return Optional.empty();
        List<String> coords = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String module : modules) {
            String coord = coordinates.get(module);
            if (coord != null) {
                coords.add(coord);
            } else {
                missing.add(module);
            }
        }
        return Optional.of(new BundleResolution(coords, missing));
    }

    /** Result of expanding a catalog bundle. */
    public record BundleResolution(List<String> coordinates, List<String> missingMembers) {
        public BundleResolution {
            coordinates = List.copyOf(coordinates);
            missingMembers = List.copyOf(missingMembers);
        }

        public boolean isEmpty() {
            return coordinates.isEmpty();
        }
    }

    // --- parsing helpers ----------------------------------------------------

    private static void flattenLibraries(
            TomlTable table, String prefix, Map<String, String> versions, Map<String, String> out, List<String> notes) {
        for (String key : table.keySet()) {
            String path = prefix.isEmpty() ? accessor(key) : prefix + "." + accessor(key);
            Object value = table.get(key);
            if (value instanceof String s) {
                if (!s.isBlank()) out.put(path, s.trim());
            } else if (value instanceof TomlTable lib) {
                if (lib.contains("module") || lib.contains("group") || lib.contains("name")) {
                    String coord = coordFromTable(lib, versions, path, notes);
                    if (coord != null) out.put(path, coord);
                } else {
                    // A namespace table produced by a dotted alias (e.g. junit.jupiter).
                    flattenLibraries(lib, path, versions, out, notes);
                }
            }
        }
    }

    private static @Nullable String coordFromTable(
            TomlTable lib, Map<String, String> versions, String accessorPath, List<String> notes) {
        String group;
        String name;
        String module = lib.getString("module");
        if (module != null) {
            int sep = module.indexOf(':');
            if (sep < 0) {
                notes.add("library `" + accessorPath + "` has malformed module `" + module + "` (want group:artifact)");
                return null;
            }
            group = module.substring(0, sep);
            name = module.substring(sep + 1);
        } else {
            group = lib.getString("group");
            name = lib.getString("name");
        }
        if (group == null || group.isBlank() || name == null || name.isBlank()) {
            notes.add("library `" + accessorPath + "` is missing group/name (or module)");
            return null;
        }
        String ga = group.trim() + ":" + name.trim();
        if (lib.contains("version.ref")) {
            String ref = lib.getString("version.ref");
            if (ref == null || ref.isBlank()) {
                notes.add("library `" + accessorPath + "` has empty version.ref");
                return ga; // version-less; BOM may manage
            }
            String v = versions.get(ref);
            if (v == null) {
                notes.add(
                        "library `"
                                + accessorPath
                                + "` version.ref `"
                                + ref
                                + "` is not defined in [versions]; imported as version-less (platform-managed if a BOM is present)");
                return ga;
            }
            return ga + ":" + v;
        }
        String version = readVersion(lib.get("version"));
        // Version-less entry (BOM-managed) — keep as g:a so import can emit platform-managed deps.
        return version == null ? ga : ga + ":" + version;
    }

    /** A version value: a plain string, or a rich {@code {strictly|require|prefer}} table. */
    private static @Nullable String readVersion(@Nullable Object value) {
        if (value instanceof String s) {
            return s.isBlank() ? null : s.trim();
        }
        if (value instanceof TomlTable t) {
            for (String field : List.of("strictly", "require", "prefer")) {
                if (t.contains(field)) {
                    String s = t.getString(field);
                    if (s != null && !s.isBlank()) return s.trim();
                }
            }
        }
        return null;
    }

    /** Fold an alias segment to its accessor form: {@code - _ .} all become {@code .}. */
    static String accessor(String alias) {
        return alias.replace('-', '.').replace('_', '.');
    }
}
