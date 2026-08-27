// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import cc.jumpkick.config.StampedMemo;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.model.Scope;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * Parses {@code jk-lock.toml} into a {@link Lockfile}. Strict: unknown top-level keys, missing required
 * keys, and unsupported schema versions are rejected.
 */
public final class LockfileReader {

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
        if (result.hasErrors()) {
            throw new IllegalArgumentException("jk-lock.toml parse error in " + origin + ": "
                    + result.errors().getFirst().getMessage());
        }
        Long lockVersionLong = result.getLong("version");
        if (lockVersionLong == null) {
            throw new IllegalArgumentException("jk-lock.toml is missing required key `version`");
        }
        int lockVersion = lockVersionLong.intValue();
        if (lockVersion < Lockfile.MIN_SUPPORTED_VERSION || lockVersion > Lockfile.CURRENT_VERSION) {
            throw new IllegalArgumentException("jk-lock.toml schema version "
                    + lockVersion
                    + " is not supported (this jk reads v"
                    + Lockfile.MIN_SUPPORTED_VERSION
                    + "-v"
                    + Lockfile.CURRENT_VERSION
                    + ") — re-run `jk lock` to restate it");
        }
        String generatedBy = requireString(result, "generated-by");
        String resolutionAlgorithm = requireString(result, "resolution-algorithm");
        // Toolchain pins live in [jdk] / [graal] tables (not the deleted top-level jdk = string).
        Lockfile.JdkPin jdk = toPin(tableOrFail(result, "jdk", origin), "jdk", Lockfile.JdkPin::new);
        Lockfile.GraalPin graal = toPin(tableOrFail(result, "graal", origin), "graal", Lockfile.GraalPin::new);
        // The jk floor: minimum jk able to run this lock. Legacy locks carried an artifact pin
        // (`jk = { version, sha256 }`); its version reads as the floor — it never blocks a newer
        // jk, and the sha is ignored (a floor needs no engine artifact). Distinct from [jdk].
        String jkMin = result.getString("jk-min");
        if (jkMin == null || jkMin.isBlank()) {
            // Legacy fallback only — a non-table `jk` is simply no floor, not an error.
            TomlTable jkTable = result.isTable("jk") ? result.getTable("jk") : null;
            jkMin = jkTable != null ? jkTable.getString("version") : null;
        }
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

        List<Lockfile.PluginEntry> plugins = new ArrayList<>();
        TomlArray pluginArray = result.getArray("plugin");
        if (pluginArray != null) {
            for (int i = 0; i < pluginArray.size(); i++) {
                TomlTable t = pluginArray.getTable(i);
                String coord = requireString(t, "coordinate");
                String ver = requireString(t, "version");
                String chk = requireString(t, "checksum");
                plugins.add(new Lockfile.PluginEntry(coord, ver, chk));
            }
        }

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

        List<Lockfile.ModuleEntry> modules = new ArrayList<>();
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
                modules.add(new Lockfile.ModuleEntry(
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
    private static TomlTable tableOrFail(TomlParseResult result, String key, String origin) {
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
    private static <T> T toPin(TomlTable table, String section, Pins<T> factory) {
        if (table == null) return null;
        if (table.contains("vendor") || table.contains("version")) {
            throw new IllegalArgumentException("["
                    + section
                    + "] uses the old vendor/version shape, which does not say whether it is a"
                    + " suggestion or a pin — re-run `jk lock` to restate it as suggested-*/required-*");
        }
        T pin = factory.of(
                table.getString("suggested-vendor"),
                table.getString("suggested-version"),
                table.getString("required-vendor"),
                table.getString("required-version"));
        if (((Lockfile.ToolchainPin) pin).isEmpty()) {
            throw new IllegalArgumentException("[" + section + "] names no vendor or version — omit the table instead");
        }
        return pin;
    }

    /** The four-argument constructor shared by {@link Lockfile.JdkPin} and {@link Lockfile.GraalPin}. */
    private interface Pins<T> {
        T of(String suggestedVendor, String suggestedVersion, String requiredVendor, String requiredVersion);
    }

    /**
     * The {@code [native]} pin, or null when the lock predates it or no module declares {@code
     * [native]}. A table with no {@code metadata-repository} is the same as no table: there is
     * nothing to extract without a version, and the checksum alone pins nothing.
     */
    private static Lockfile.NativeMetadata toNativeMetadata(TomlTable table) {
        if (table == null) return null;
        String version = table.getString("metadata-repository");
        if (version == null || version.isBlank()) return null;
        String checksum = table.getString("checksum");
        return new Lockfile.NativeMetadata(version, checksum == null || checksum.isBlank() ? null : checksum);
    }

    private static Lockfile.Artifact toArtifact(TomlTable table) {
        String name = requireString(table, "name");
        String version = requireString(table, "version");
        String source = normalizeSource(requireString(table, "source"));
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
        TomlArray depsArray = table.getArray("deps");
        if (depsArray != null) {
            for (int i = 0; i < depsArray.size(); i++) {
                deps.add(depsArray.getString(i));
            }
        }

        Lockfile.Artifact.GitInfo git = null;
        String gitUrl = table.getString("git");
        if (gitUrl != null) {
            git = new Lockfile.Artifact.GitInfo(gitUrl, requireString(table, "rev"), table.getString("ref"));
        }
        String sourcesChecksum = table.getString("sources"); // optional
        return new Lockfile.Artifact(
                name, version, source, checksum, path, scopes, deps, pinnedBy, git, sourcesChecksum);
    }

    /**
     * Lockfiles written before the jk-local rename mark first-party file-dep entries with the bare
     * source {@code "local"}. A user remote named {@code local} always serializes with its URL
     * ({@code "local+file://…"}), so the bare form is unambiguous — fold it to the current marker
     * rather than making every consumer accept both.
     */
    private static String normalizeSource(String source) {
        return "local".equals(source) ? RepositorySpec.JK_LOCAL : source;
    }

    private static String requireString(TomlParseResult result, String key) {
        String value = result.getString(key);
        if (value == null) {
            throw new IllegalArgumentException("jk-lock.toml is missing required key `" + key + "`");
        }
        return value;
    }

    private static String requireString(TomlTable table, String key) {
        String value = table.getString(key);
        if (value == null) {
            throw new IllegalArgumentException("[[artifact]] is missing required key `" + key + "`");
        }
        return value;
    }
}
