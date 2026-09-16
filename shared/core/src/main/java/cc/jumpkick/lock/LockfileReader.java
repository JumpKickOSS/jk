// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import cc.jumpkick.config.StampedMemo;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.model.Scope;
import cc.jumpkick.version.Versions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlInvalidTypeException;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * Parses {@code jk-lock.toml} into a {@link Lockfile}. Strict: unknown top-level keys, missing required
 * keys, and unsupported schema versions are rejected.
 */
public final class LockfileReader {

    /** Every key the writer emits at the top level; anything else is a shape this jk does not read. */
    private static final Set<String> TOP_LEVEL_KEYS = Set.of(
            "artifact",
            "generated-by",
            "graal",
            "jdk",
            "jk-min",
            "kotlin",
            "manifests-sha256",
            "module",
            "native",
            "plugin",
            "project-id",
            "resolution-algorithm",
            "scala",
            "sdk",
            "version");

    private LockfileReader() {}

    /**
     * Process-lifetime memo of {@link #read(Path)}: one entry per path, holding the size + mtime it
     * was parsed at (a rewrite re-parses and replaces).
     *
     * <p>Keyed by PATH, not (path, size, mtime): with the stamp in the key every {@code jk lock} /
     * {@code jk add} / {@code jk update} would strand the previous {@code Lockfile} — hundreds of
     * artifacts each — for the engine's lifetime.
     */
    private static final StampedMemo<Path, StampedMemo.FileStamp, Lockfile> READ_CACHE = StampedMemo.bounded(64);

    /** Test seam: drop the per-process read memo so freshly-written files re-parse. */
    public static void clearCache() {
        READ_CACHE.clear();
    }

    public static Lockfile read(Path file) throws IOException {
        Path key = file.toAbsolutePath().normalize();
        StampedMemo.FileStamp stamp = StampedMemo.FileStamp.of(key);
        if (stamp == null) {
            // Absent or unreadable: let the read below produce the caller's IOException, as before.
            return parse(file);
        }
        try {
            return READ_CACHE.get(key, stamp, () -> {
                try {
                    return parse(file);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /**
     * Read the whole file, then parse: the handle is open only for the read, so a concurrent
     * {@code AtomicWrites.replace} on Windows is not blocked by {@code Toml.parse(Path)} holding the
     * target open.
     */
    private static Lockfile parse(Path file) throws IOException {
        TomlParseResult result = Toml.parse(Files.readString(file));
        return fromResult(result, file.toString());
    }

    public static Lockfile parse(String content) {
        TomlParseResult result = Toml.parse(content);
        return fromResult(result, "<string>");
    }

    private static Lockfile fromResult(TomlParseResult result, String origin) {
        try {
            return read(result, origin);
        } catch (TomlInvalidTypeException e) {
            // A key holding the wrong TOML type is the same class of defect as a missing one.
            throw new IllegalArgumentException("jk-lock.toml in " + origin + ": " + e.getMessage(), e);
        }
    }

    private static Lockfile read(TomlParseResult result, String origin) {
        if (result.hasErrors()) {
            throw new IllegalArgumentException("jk-lock.toml parse error in " + origin + ": "
                    + result.errors().getFirst().getMessage());
        }
        Long lockVersionLong = result.getLong("version");
        if (lockVersionLong == null) {
            throw new IllegalArgumentException("jk-lock.toml is missing required key `version`");
        }
        int lockVersion = lockVersionLong.intValue();
        String writer = newerWriter(result);
        if (lockVersion != Lockfile.CURRENT_VERSION) {
            throw new IllegalArgumentException("jk-lock.toml schema version "
                    + lockVersion
                    + " is not supported (this jk reads v"
                    + Lockfile.CURRENT_VERSION
                    + ") — " + remedy(writer, origin));
        }
        Set<String> unknown = new TreeSet<>(result.keySet());
        unknown.removeAll(TOP_LEVEL_KEYS);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("jk-lock.toml in " + origin + " has unknown top-level key(s) " + unknown
                    + " — " + remedy(writer, origin));
        }
        String generatedBy = requireString(result, "generated-by");
        String resolutionAlgorithm = requireString(result, "resolution-algorithm");
        // Toolchain pins live in [jdk] / [graal] tables (not the deleted top-level jdk = string).
        JdkPin jdk = toPin(tableOrFail(result, "jdk", origin), "jdk", JdkPin::new);
        GraalPin graal = toPin(tableOrFail(result, "graal", origin), "graal", GraalPin::new);
        // The jk floor: minimum jk able to run this lock. It never blocks a newer jk. Distinct from [jdk].
        String jkMin = result.getString("jk-min");
        if (jkMin != null && jkMin.isBlank()) jkMin = null;
        String kotlin = result.getString("kotlin"); // optional, resolved Kotlin compiler version
        String scala = result.getString("scala"); // optional, resolved Scala 3 compiler version

        List<Lockfile.Artifact> artifacts = new ArrayList<>();
        TomlArray artifactArray = result.getArray("artifact");
        if (artifactArray != null) {
            for (int i = 0; i < artifactArray.size(); i++) {
                artifacts.add(toArtifact(artifactArray.getTable(i)));
            }
        }

        List<Lockfile.PluginEntry> plugins = readPlugins(result);

        List<Lockfile.SdkEntry> sdk = new ArrayList<>();
        TomlArray sdkArray = result.getArray("sdk");
        if (sdkArray != null) {
            for (int i = 0; i < sdkArray.size(); i++) {
                TomlTable t = sdkArray.getTable(i);
                if (t == null) continue;
                String component = t.getString("component");
                String revision = t.getString("revision");
                if (component == null || revision == null) continue;
                sdk.add(new Lockfile.SdkEntry(component, revision));
            }
        }

        List<ModuleEntry> modules = new ArrayList<>();
        TomlArray moduleArray = result.getArray("module");
        if (moduleArray != null) {
            for (int i = 0; i < moduleArray.size(); i++) {
                TomlTable t = moduleArray.getTable(i);
                if (t == null) continue;
                String path = t.getString("path");
                String group = t.getString("group");
                String name = t.getString("name");
                String ver = t.getString("version");
                if (path == null || group == null || name == null || ver == null) continue;
                Integer java = null;
                if (t.contains("java")) {
                    Object raw = t.get("java");
                    if (raw instanceof Long l) java = l.intValue();
                    else if (raw instanceof Integer n) java = n;
                }
                TomlTable m2Table = t.getTable("m2");
                Boolean m2 =
                        m2Table != null && m2Table.contains("integration") ? m2Table.getBoolean("integration") : null;
                Boolean m2install =
                        m2Table != null && m2Table.contains("install") ? m2Table.getBoolean("install") : null;
                modules.add(new ModuleEntry(
                        path,
                        group,
                        name,
                        ver,
                        java,
                        t.getString("kotlin"),
                        t.getString("groovy"),
                        t.getString("scala"),
                        t.getString("description"),
                        t.getString("sources"),
                        m2,
                        m2install));
            }
        }
        String manifestsSha = result.getString("manifests-sha256"); // optional, additive v1
        if (manifestsSha != null && manifestsSha.isBlank()) manifestsSha = null;
        String projectId = result.getString("project-id"); // optional durable identity
        if (projectId != null && projectId.isBlank()) projectId = null;
        return new Lockfile(
                lockVersion,
                generatedBy,
                resolutionAlgorithm,
                jdk,
                graal,
                kotlin,
                scala,
                artifacts,
                plugins,
                sdk,
                modules,
                jkMin,
                manifestsSha,
                projectId,
                toNativeMetadata(tableOrFail(result, "native", origin)));
    }

    /**
     * The table at {@code key}, or null when absent. A present non-table value (e.g. a lock written
     * by the pre-{@code [jdk]} format's top-level {@code jdk = "<id>"} string) is the reader's
     * normal clean diagnostic — not an unchecked {@code TomlInvalidTypeException} escaping to
     * callers that only catch {@code IOException}.
     */
    private static @Nullable TomlTable tableOrFail(TomlParseResult result, String key, String origin) {
        if (result.isTable(key)) return result.getTable(key);
        if (result.contains(key)) {
            throw new IllegalArgumentException("jk-lock.toml in " + origin + ": `" + key + "` must be a [" + key
                    + "] table — re-run `jk lock` to rewrite this lockfile");
        }
        return null;
    }

    /** A {@code [jdk]}/{@code [graal]} pin, or null when absent. Both fields required when present. */
    /**
     * One {@code [jdk]} / {@code [graal]} table. The pre-{@code suggested-*} shape (a bare
     * {@code vendor} + {@code version} pair) said nothing about whether it bound a later build, so
     * it cannot be read either way without guessing — it is rejected, and {@code jk lock} restates
     * it honestly.
     */
    private static <T> @Nullable T toPin(@Nullable TomlTable table, String section, Pins<T> factory) {
        if (table == null) return null;
        if (table.contains("vendor") || table.contains("version")) {
            throw new IllegalArgumentException("["
                    + section
                    + "] uses the old vendor/version shape, which does not say whether it is a"
                    + " suggestion or a pin — re-run `jk lock` to restate it as suggested-*/required-*");
        }
        T pin = factory.of(
                ToolchainPin.blankToEmpty(table.getString("suggested-vendor")),
                ToolchainPin.blankToEmpty(table.getString("suggested-version")),
                ToolchainPin.blankToEmpty(table.getString("required-vendor")),
                ToolchainPin.blankToEmpty(table.getString("required-version")));
        if (((ToolchainPin) pin).isEmpty()) {
            throw new IllegalArgumentException("[" + section + "] names no vendor or version — omit the table instead");
        }
        return pin;
    }

    /** The four-argument constructor shared by {@link JdkPin} and {@link GraalPin}. */
    private interface Pins<T> {
        T of(String suggestedVendor, String suggestedVersion, String requiredVendor, String requiredVersion);
    }

    /**
     * The {@code [native]} pin, or null when the lock predates it or no module declares {@code
     * [native]}. A table with no {@code metadata-repository} is the same as no table: there is
     * nothing to extract without a version, and the checksum alone pins nothing.
     */
    private static Lockfile.@Nullable NativeMetadata toNativeMetadata(@Nullable TomlTable table) {
        if (table == null) return null;
        String version = table.getString("metadata-repository");
        if (version == null || version.isBlank()) return null;
        String checksum = table.getString("checksum");
        return new Lockfile.NativeMetadata(version, checksum == null || checksum.isBlank() ? null : checksum);
    }

    private static Lockfile.Artifact toArtifact(TomlTable table) {
        String name = requireString(table, "name");
        String version = requireString(table, "version");
        String source = requireString(table, "source");
        String checksum = table.getString("checksum");
        String path = table.getString("path");
        String pinnedBy = table.getString("pinned-by"); // optional

        List<Scope> scopes = new ArrayList<>();
        TomlArray scopesArray = table.getArray("scopes");
        if (scopesArray != null) {
            for (int i = 0; i < scopesArray.size(); i++) {
                // fromCanonical, not valueOf: hyphenated scopes ("test-dev") don't
                // uppercase into enum constant names.
                scopes.add(Scope.fromCanonical(scopesArray.getString(i)));
            }
        }
        if (scopes.isEmpty()) {
            // v4 compatibility: untagged packages default to MAIN.
            scopes.add(Scope.MAIN);
        }

        List<String> deps = new ArrayList<>();
        Map<String, String> declared = new LinkedHashMap<>();
        TomlArray depsArray = table.getArray("deps");
        if (depsArray != null) {
            for (int i = 0; i < depsArray.size(); i++) {
                String line = depsArray.getString(i);
                int sep = line.indexOf(Lockfile.DECLARED_SEPARATOR);
                if (sep < 0) {
                    deps.add(line);
                } else {
                    String ref = line.substring(0, sep);
                    deps.add(ref);
                    declared.put(ref, line.substring(sep + Lockfile.DECLARED_SEPARATOR.length()));
                }
            }
        }

        Lockfile.Artifact.GitInfo git = null;
        String gitUrl = table.getString("git");
        if (gitUrl != null) {
            git = new Lockfile.Artifact.GitInfo(gitUrl, requireString(table, "rev"), table.getString("ref"));
        }
        String sourcesChecksum = table.getString("sources"); // optional
        List<String> excludedBy = new ArrayList<>();
        TomlArray excludedArray = table.getArray("excluded-by");
        if (excludedArray != null) {
            for (int i = 0; i < excludedArray.size(); i++) excludedBy.add(excludedArray.getString(i));
        }
        List<String> members = new ArrayList<>();
        TomlArray membersArray = table.getArray("members");
        if (membersArray != null) {
            for (int i = 0; i < membersArray.size(); i++) members.add(membersArray.getString(i));
        }
        return new Lockfile.Artifact(
                name,
                version,
                source,
                checksum,
                path,
                scopes,
                deps,
                pinnedBy,
                git,
                sourcesChecksum,
                declared,
                excludedBy,
                members);
    }

    /**
     * The version in {@code generated-by} when it is ahead of this jk, else null. A lock this jk
     * cannot read was written either by a newer jk — the format moved on — or by hand; the writer's
     * version tells the two apart, and the remedy differs.
     */
    static @Nullable String newerWriter(TomlParseResult result) {
        String generatedBy = result.getString("generated-by");
        if (generatedBy == null) return null;
        String version = generatedBy.trim();
        if (version.startsWith("jk ")) version = version.substring(3).trim();
        if (version.isEmpty()) return null;
        try {
            return Versions.compare(version, JkVersion.VERSION) > 0 ? version : null;
        } catch (RuntimeException notAVersion) {
            return null;
        }
    }

    /**
     * What to do about a lock this jk cannot read. Written by a newer jk, the answer is a newer
     * reader: re-locking with this one would restate the lock in the old format and hand the
     * newer tree an older lock. Written by anything else, the lock is malformed and a re-lock
     * restates it.
     */
    static String remedy(@Nullable String newerWriter, String origin) {
        if (newerWriter == null) return "re-run `jk lock` to restate it";
        return "it was written by jk " + newerWriter + " and this is jk " + JkVersion.VERSION
                + "; a newer lock format needs a newer reader, not a re-lock. Bootstrap " + origin
                + " with a jk built from the last commit this one can read"
                + " (docs/contributors/self-host.md, \"The bootstrap chain\")";
    }

    private static String requireString(TomlParseResult result, String key) {
        String value = result.getString(key);
        if (value == null) {
            throw new IllegalArgumentException("jk-lock.toml is missing required key `" + key + "`");
        }
        return value;
    }

    /**
     * The {@code [[plugin]]} rows: each pinned by a jar {@code checksum}, by a workspace module
     * {@code path}, or by version alone (a first-party plugin at a pre-release version).
     */
    private static List<Lockfile.PluginEntry> readPlugins(TomlParseResult result) {
        List<Lockfile.PluginEntry> plugins = new ArrayList<>();
        TomlArray pluginArray = result.getArray("plugin");
        if (pluginArray == null) return plugins;
        for (int i = 0; i < pluginArray.size(); i++) {
            TomlTable t = pluginArray.getTable(i);
            String coord = requireString(t, "coordinate");
            String ver = requireString(t, "version");
            String chk = t.getString("checksum");
            String path = t.getString("path");
            if (chk != null && path != null) {
                throw new IllegalArgumentException("[[plugin]] " + coord + " names both `checksum` and `path`"
                        + " — a row is verified by one of them, not both");
            }
            plugins.add(new Lockfile.PluginEntry(coord, ver, chk, path));
        }
        return plugins;
    }

    private static String requireString(TomlTable table, String key) {
        String value = table.getString(key);
        if (value == null) {
            throw new IllegalArgumentException("[[artifact]] is missing required key `" + key + "`");
        }
        return value;
    }
}
