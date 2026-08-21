// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import cc.jumpkick.model.Scope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
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
    private static final ConcurrentHashMap<Path, Cached> READ_CACHE = new ConcurrentHashMap<>();

    private record Cached(long size, FileTime modified, Lockfile value) {}

    /** Test seam: drop the per-process read memo so freshly-written files re-parse. */
    public static void clearCache() {
        READ_CACHE.clear();
    }

    public static Lockfile read(Path file) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
        Path key = file.toAbsolutePath().normalize();
        Cached cached = READ_CACHE.get(key);
        if (cached != null && cached.size() == attrs.size() && cached.modified().equals(attrs.lastModifiedTime())) {
            return cached.value();
        }
        TomlParseResult result = Toml.parse(file);
        Lockfile lockfile = fromResult(result, file.toString());
        // Clear-on-overflow (same bound as ProjectIds): one parsed Lockfile — potentially MBs —
        // per distinct lockfile path the process ever read, forever.
        if (READ_CACHE.size() >= 64) READ_CACHE.clear();
        READ_CACHE.put(key, new Cached(attrs.size(), attrs.lastModifiedTime(), lockfile));
        return lockfile;
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
                    + ")");
        }
        String generatedBy = requireString(result, "generated-by");
        String resolutionAlgorithm = requireString(result, "resolution-algorithm");
        String jdk = result.getString("jdk"); // optional
        // The jk floor: minimum jk able to run this lock. Legacy locks carried an artifact pin
        // (`jk = { version, sha256 }`); its version reads as the floor — it never blocks a newer
        // jk, and the sha is ignored (a floor needs no engine artifact).
        String jkMin = result.getString("jk-min");
        if (jkMin == null || jkMin.isBlank()) {
            org.tomlj.TomlTable jkTable = result.getTable("jk");
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
                Boolean m2 = t.contains("m2install") ? t.getBoolean("m2install") : null;
                modules.add(new Lockfile.ModuleEntry(
                        path,
                        group,
                        name,
                        ver,
                        t.getString("jdk"),
                        java,
                        t.getString("kotlin"),
                        t.getString("groovy"),
                        t.getString("scala"),
                        t.getString("description"),
                        t.getString("sources"),
                        m2,
                        t.getString("layout")));
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
                kotlin,
                scala,
                artifacts,
                plugins,
                sdk,
                modules,
                jkMin,
                manifestsSha,
                projectId);
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
