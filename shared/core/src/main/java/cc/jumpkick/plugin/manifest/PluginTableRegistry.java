// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.manifest;

import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.model.PluginConfig;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

/**
 * Installed plugins keyed by owned jk.toml table (built-ins + third-party). {@link #validate}
 * schema-checks a raw table into a {@link PluginConfig}.
 *
 * <p>Production built-ins come from self-describing plugin jars ({@code jk-plugin.toml} at the
 * zip root). The engine installs them via {@link #putBuiltIn}. {@code :core} main and the native
 * CLI see an empty set. Tests load the same files from {@code cc/jumpkick/plugin/manifest/} on
 * the test classpath, or — when that tree is absent — from {@code -Djk.<id>.plugin.jar} and
 * {@code plugins/<id>/jk-plugin.toml} in the enclosing workspace. The workspace walk runs only
 * when this class was loaded from jk-core (or the test-runner host) so a resident engine or
 * native CLI started from this repo does not pick up source manifests.
 */
public final class PluginTableRegistry {

    private static final String TEST_RUNNER_PLUGIN_CLASS = "cc.jumpkick.testrunner.TestRunner";
    private static final String BUILT_IN_SUFFIX = ".jk-plugin.toml";

    private static final List<String> BUILT_IN = List.of(
            "spring-boot.jk-plugin.toml",
            "grails.jk-plugin.toml",
            "quarkus.jk-plugin.toml",
            "android.jk-plugin.toml",
            "protobuf.jk-plugin.toml",
            "minified.jk-plugin.toml",
            "micronaut.jk-plugin.toml");

    /** Load a plugin resource relative to its manifest ({@code <id>/<relPath>}). */
    public static String resourceText(PluginDescriptor manifest, String relPath) {
        Path archive = ARCHIVES.get(manifest.id());
        if (archive != null) {
            try {
                String text = zipEntryText(archive, relPath);
                if (text == null) {
                    throw new JkBuildParseException(
                            "plugin " + manifest.id() + " names a missing resource: " + relPath);
                }
                return text;
            } catch (IOException e) {
                throw new JkBuildParseException(
                        "plugin " + manifest.id() + " resource " + relPath + " is unreadable: " + e.getMessage());
            }
        }
        String resource = manifest.id() + "/" + relPath;
        try (InputStream in = openBuiltIn(resource)) {
            if (in == null) {
                throw new JkBuildParseException("plugin " + manifest.id() + " names a missing resource: " + relPath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new JkBuildParseException(
                    "plugin " + manifest.id() + " resource " + relPath + " is unreadable: " + e.getMessage());
        }
    }

    /** Plugin id → jar that owns {@code jk-plugin.toml} and {@code templates/}. */
    private static final Map<String, Path> ARCHIVES = new ConcurrentHashMap<>();

    private static volatile Map<String, PluginDescriptor> BY_TABLE = loadBuiltIns();

    /** Jar the plugin was installed from, or {@code null} when the manifest is test-classpath only. */
    public static Path archive(String pluginId) {
        return pluginId == null ? null : ARCHIVES.get(pluginId);
    }

    /** Manifest whose {@code id} or {@code table} equals {@code name}. */
    public static PluginDescriptor byIdOrTable(String name) {
        if (name == null || name.isBlank()) return null;
        for (PluginDescriptor m : BY_TABLE.values()) {
            if (name.equals(m.id()) || name.equals(m.table())) return m;
        }
        return null;
    }

    /**
     * Load {@code jk-plugin.toml} from a self-describing plugin jar and {@link #putBuiltIn}.
     * Jars without that root entry are ignored.
     */
    public static void installFromJar(Path jar) {
        Objects.requireNonNull(jar, "jar");
        try {
            String toml = zipEntryText(jar, "jk-plugin.toml");
            if (toml == null || toml.isBlank()) return;
            PluginDescriptor manifest = PluginDescriptors.parse(toml, jar + "!jk-plugin.toml");
            putBuiltIn(manifest, jar);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read plugin manifest from " + jar, e);
        }
    }

    /**
     * Register or replace a built-in manifest loaded from a self-describing plugin jar.
     * {@code archive} is the zip {@link #resourceText} reads plugin resources from.
     */
    public static void putBuiltIn(PluginDescriptor manifest, Path archive) {
        Objects.requireNonNull(manifest, "manifest");
        synchronized (PluginTableRegistry.class) {
            Map<String, PluginDescriptor> next = new LinkedHashMap<>(BY_TABLE);
            next.put(manifest.table(), manifest);
            BY_TABLE = Map.copyOf(next);
        }
        if (archive != null) ARCHIVES.put(manifest.id(), archive);
    }

    private PluginTableRegistry() {}

    /** Every installed manifest, in registration order. */
    public static List<PluginDescriptor> manifests() {
        return List.copyOf(BY_TABLE.values());
    }

    /** True when {@code id} names a first-party manifest shipped inside jk itself. */
    public static boolean isBuiltIn(String id) {
        for (PluginDescriptor m : BY_TABLE.values()) {
            if (m.id().equals(id)) return true;
        }
        return false;
    }

    /**
     * The manifests a build of {@code moduleDir} sees: built-ins plus every {@code [plugins]}
     * declaration whose manifest is locked + materialized ({@link PluginDescriptorStore}). An
     * unresolved declaration is silently absent — its table validates on the next parse after the
     * engine materializes (sync/lock/build pre-flight). An explicit declaration may <em>replace</em>
     * a built-in with the same id or table; two declarations colliding with each other is an error.
     */
    public static List<PluginDescriptor> manifestsFor(Path moduleDir, List<cc.jumpkick.model.PluginDeclaration> decls) {
        if (decls == null || decls.isEmpty()) return manifests();
        List<PluginDescriptor> out = new ArrayList<>(manifests());
        Set<String> ids = new HashSet<>();
        Set<String> tables = new HashSet<>();
        for (PluginDescriptor m : out) {
            ids.add(m.id());
            tables.add(m.table());
        }
        Set<String> declaredIds = new HashSet<>();
        Set<String> declaredTables = new HashSet<>();
        for (cc.jumpkick.model.PluginDeclaration decl : decls) {
            PluginDescriptor m =
                    PluginDescriptorStore.manifestFor(moduleDir, decl).orElse(null);
            if (m == null) continue;
            if (!declaredIds.add(m.id())) {
                throw new JkBuildParseException("plugin " + decl.coordinateWithVersion() + " declares id `" + m.id()
                        + "`, which is already provided by another [plugins] entry");
            }
            if (!declaredTables.add(m.table())) {
                throw new JkBuildParseException("plugin " + decl.coordinateWithVersion() + " claims table [" + m.table()
                        + "], which is already owned by another [plugins] entry");
            }
            out.removeIf(existing -> {
                boolean hit = existing.id().equals(m.id()) || existing.table().equals(m.table());
                if (hit) {
                    ids.remove(existing.id());
                    tables.remove(existing.table());
                }
                return hit;
            });
            ids.add(m.id());
            tables.add(m.table());
            out.add(m);
        }
        return out;
    }

    /** The manifest owning jk.toml table {@code table}, when a plugin declares it. */
    public static Optional<PluginDescriptor> byTable(String table) {
        return Optional.ofNullable(BY_TABLE.get(table));
    }

    /** Schema-validate {@code table} (the raw {@code [<manifest.table>]} TOML) into a config. */
    public static PluginConfig validate(PluginDescriptor manifest, TomlTable table) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (PluginDescriptor.SchemaKey key : manifest.schema().values()) {
            // A schema key sharing its name with a sub-table group: the TOML value decides which
            // spelling this module used — a nested table is the group (handled below), a string
            // is the reference read here.
            if (manifest.subTables().containsKey(key.name()) && readTable(table, key.name()) != null) continue;
            Object value = read(manifest.table(), key, table);
            if (value == null) value = key.normalizedDefault();
            if (value == null) {
                if (key.required()) throw new JkBuildParseException(requiredMessage(manifest.table(), key));
                continue; // tri-state: absent stays absent
            }
            values.put(key.name(), value);
        }
        // Declared nested-table groups ([sub-tables.<name>]): each entry validates against its
        // sub-schema and rides the config as a nested map, resolved by name from a schema key
        // (signing = "release") and flattened into the effective config by VariantApply.
        for (PluginDescriptor.SubTable group : manifest.subTables().values()) {
            TomlTable groupTable = readTable(table, group.table());
            if (groupTable == null) continue;
            Map<String, PluginDescriptor.SchemaKey> subSchema =
                    manifest.subSchemas().get(group.schema());
            String whereBase = manifest.table() + "." + group.table();
            Map<String, Map<String, Object>> entries = new LinkedHashMap<>();
            for (String entry : groupTable.keySet()) {
                TomlTable entryTable = readTable(groupTable, entry);
                if (entryTable == null) {
                    throw new JkBuildParseException("[" + whereBase + "]." + entry + " must be a table");
                }
                entries.put(entry, validateSub(whereBase + "." + entry, subSchema, entryTable));
            }
            values.put(group.table(), entries);
        }
        return new PluginConfig(manifest.id(), values);
    }

    /**
     * Schema-validate a variant overlay ({@code [variants.<dim>.<value>.<plugin-table>]}) — the
     * partial-table cousin of {@link #validate}: keys coerce against the plugin's top-level
     * schema, but nothing is required and no defaults apply (an overlay only carries what it
     * sets). Nested groups (signing definitions) don't belong in overlays — reference them by
     * name via their schema key instead ({@code signing = "release"}).
     */
    public static Map<String, Object> validateOverlay(PluginDescriptor manifest, String where, TomlTable table) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String raw : table.keySet()) {
            // Group DEFINITIONS don't belong in overlays; the string REFERENCE spelling does.
            if (manifest.subTables().containsKey(raw) && readTable(table, raw) != null) {
                throw new JkBuildParseException("[" + where + "." + raw + "] — define ["
                        + manifest.table() + "." + raw + ".<name>] groups on the plugin table and reference"
                        + " them from the overlay by name (" + raw + " = \"<name>\")");
            }
            PluginDescriptor.SchemaKey key = manifest.schema().get(raw);
            if (key == null) {
                throw new JkBuildParseException("[" + where + "]." + raw + " is not a [" + manifest.table()
                        + "] key (schema: " + manifest.schema().keySet() + ")");
            }
            Object value = read(where, key, table);
            if (value != null) values.put(key.name(), value);
        }
        return values;
    }

    private static TomlTable readTable(TomlTable parent, String key) {
        Object raw = parent.contains(key) ? parent.get(key) : null;
        return raw instanceof TomlTable t ? t : null;
    }

    /** One nested entry against its sub-schema — same coercion + required semantics as the table. */
    private static Map<String, Object> validateSub(
            String where, Map<String, PluginDescriptor.SchemaKey> subSchema, TomlTable entryTable) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (PluginDescriptor.SchemaKey key : subSchema.values()) {
            Object value = read(where, key, entryTable);
            if (value == null) value = key.normalizedDefault();
            if (value == null) {
                if (key.required()) {
                    throw new JkBuildParseException("[" + where + "] requires `" + key.name() + "`"
                            + (key.example() != null ? " (e.g. " + key.name() + " = \"" + key.example() + "\")" : ""));
                }
                continue;
            }
            out.put(key.name(), value);
        }
        return out;
    }

    private static Object read(String tableName, PluginDescriptor.SchemaKey key, TomlTable table) {
        if (!table.contains(key.name())) return null;
        String where = "[" + tableName + "]." + key.name();
        return switch (key.type()) {
            case STRING -> {
                String v = getOr(() -> table.getString(key.name()), where + " must be a string");
                if (v == null || v.isBlank()) yield null;
                yield v;
            }
            case BOOL -> {
                Boolean v = getOr(() -> table.getBoolean(key.name()), where + " must be a boolean");
                if (v == null) throw new JkBuildParseException(where + " must be a boolean");
                yield v;
            }
            case INT -> {
                Long v = getOr(() -> table.getLong(key.name()), where + " must be an integer");
                if (v == null) throw new JkBuildParseException(where + " must be an integer");
                yield v;
            }
            case STRING_LIST -> {
                TomlArray arr = getOr(() -> table.getArray(key.name()), where + " must be an array of strings");
                if (arr == null) throw new JkBuildParseException(where + " must be an array of strings");
                List<String> out = new ArrayList<>(arr.size());
                for (int i = 0; i < arr.size(); i++) {
                    Object val = arr.get(i);
                    if (!(val instanceof String str)) {
                        throw new JkBuildParseException(where + " must be an array of strings");
                    }
                    out.add(str);
                }
                yield out;
            }
        };
    }

    /** tomlj throws {@code TomlInvalidTypeException} on mistyped keys — normalize to a parse error. */
    private static <T> T getOr(Supplier<T> read, String message) {
        try {
            return read.get();
        } catch (org.tomlj.TomlInvalidTypeException e) {
            throw new JkBuildParseException(message);
        }
    }

    /** Message parity with the old hand-written parsers: example + hint when the schema has them. */
    private static String requiredMessage(String tableName, PluginDescriptor.SchemaKey key) {
        StringBuilder sb = new StringBuilder("[" + tableName + "]." + key.name() + " is required");
        if (key.example() != null) {
            sb.append(" (e.g. ")
                    .append(key.name())
                    .append(" = \"")
                    .append(key.example())
                    .append("\")");
        }
        if (key.hint() != null) sb.append(" — ").append(key.hint());
        return sb.toString();
    }

    /**
     * Test-classpath fixtures live under {@code cc/jumpkick/plugin/manifest/}, not next to this
     * class in {@code :core} main. Try the class, then the context loader, then the defining
     * loader with the full path.
     */
    private static InputStream openBuiltIn(String resource) {
        InputStream in = PluginTableRegistry.class.getResourceAsStream(resource);
        if (in != null) return in;
        String full = "cc/jumpkick/plugin/manifest/" + resource;
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        if (ctx != null) {
            in = ctx.getResourceAsStream(full);
            if (in != null) return in;
        }
        ClassLoader def = PluginTableRegistry.class.getClassLoader();
        return def == null ? null : def.getResourceAsStream(full);
    }

    private static Map<String, PluginDescriptor> loadBuiltIns() {
        Map<String, PluginDescriptor> byTable = new LinkedHashMap<>();
        for (String resource : BUILT_IN) {
            try (InputStream in = openBuiltIn(resource)) {
                if (in == null) continue;
                PluginDescriptor manifest =
                        tryParseBuiltIn(new String(in.readAllBytes(), StandardCharsets.UTF_8), resource);
                if (manifest != null) byTable.put(manifest.table(), manifest);
            } catch (IOException e) {
                throw new UncheckedIOException("failed to load built-in plugin manifest " + resource, e);
            }
        }
        // Native CLI / :core main have no classpath fixtures. Tests may bake them; the engine
        // installs from self-describing jars via putBuiltIn. A test JVM loading this class from
        // jk-core (classes dir or jk-core-*.jar) overlays workspace sources and -Djk.<id>.plugin.jar
        // so leftover flattened copies on another module's classes dir cannot poison class init.
        if (shouldLoadWorkspacePluginSources()) {
            byTable.putAll(loadFromWorkspacePluginSources(discoverTestWorkspaceRoot()));
        }
        byTable.putAll(loadFromPluginJarProperties());
        if (byTable.size() == BUILT_IN.size()) return Map.copyOf(byTable);
        if (byTable.isEmpty()) return Map.of();
        throw new IllegalStateException("missing built-in plugin manifest resources ("
                + (BUILT_IN.size() - byTable.size())
                + "/"
                + BUILT_IN.size()
                + "; first-party manifests live on plugin jars and the test classpath, not :core)");
    }

    /**
     * Parse one built-in manifest; {@code null} when the bytes are not a current catalog (stale
     * {@code [scaffold]} copies, truncated fixtures). Class init must not die on leftovers.
     */
    static PluginDescriptor tryParseBuiltIn(String toml, String displayPath) {
        try {
            return parseBuiltIn(toml, displayPath);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static PluginDescriptor parseBuiltIn(String toml, String displayPath) {
        PluginDescriptor manifest = PluginDescriptors.parse(toml, displayPath);
        if (manifest.code() != null && manifest.code().worker() == null) {
            throw new IllegalStateException(
                    "built-in plugin manifest " + displayPath + " must name its registered worker jar ([code] worker)");
        }
        return manifest;
    }

    /**
     * Workspace sources are a test-classpath substitute, not a production catalog. Load them
     * when this class came from jk-core's classes dir / {@code jk-core-*.jar} (unit tests) or
     * the test-runner host set {@code jk.plugin.class}. Skip the native CLI and the engine fat
     * jar — those stay empty until {@link #putBuiltIn}.
     */
    private static boolean shouldLoadWorkspacePluginSources() {
        if (TEST_RUNNER_PLUGIN_CLASS.equals(System.getProperty("jk.plugin.class"))) return true;
        try {
            var src = PluginTableRegistry.class.getProtectionDomain().getCodeSource();
            if (src == null || src.getLocation() == null) return false;
            Path loc = Path.of(src.getLocation().toURI()).toAbsolutePath().normalize();
            if (Files.isDirectory(loc)) {
                return "main".equals(pathFileName(loc));
            }
            String name = pathFileName(loc);
            return name.startsWith("jk-core-") && name.endsWith(".jar");
        } catch (Exception e) {
            return false;
        }
    }

    private static String pathFileName(Path path) {
        Path name = path.getFileName();
        return name == null ? "" : name.toString();
    }

    private static String builtInId(String resource) {
        return resource.endsWith(BUILT_IN_SUFFIX)
                ? resource.substring(0, resource.length() - BUILT_IN_SUFFIX.length())
                : resource;
    }

    /**
     * Manifests from {@code -Djk.<id>.plugin.jar} (the same props {@code [build] test-plugin-jars}
     * hands the test JVM). Missing properties are skipped so a module can name only the workers
     * its tests fork.
     */
    static Map<String, PluginDescriptor> loadFromPluginJarProperties() {
        Map<String, PluginDescriptor> byTable = new LinkedHashMap<>();
        for (String resource : BUILT_IN) {
            String id = builtInId(resource);
            String override = System.getProperty("jk." + id + ".plugin.jar");
            if (override == null || override.isBlank()) continue;
            Path jar = Path.of(override);
            if (!Files.isRegularFile(jar)) continue;
            try {
                String toml = zipEntryText(jar, "jk-plugin.toml");
                if (toml == null || toml.isBlank()) continue;
                PluginDescriptor manifest = parseBuiltIn(toml, jar + "!jk-plugin.toml");
                byTable.put(manifest.table(), manifest);
                ARCHIVES.put(manifest.id(), jar);
            } catch (IOException e) {
                throw new UncheckedIOException("failed to load plugin manifest from " + jar, e);
            }
        }
        return byTable;
    }

    /**
     * First-party {@code plugins/<id>/jk-plugin.toml} under {@code root}. Empty when {@code root}
     * is null or none of the files exist; incomplete trees (some present, some not) fail closed.
     */
    static Map<String, PluginDescriptor> loadFromWorkspacePluginSources(Path root) {
        if (root == null) return Map.of();
        Map<String, PluginDescriptor> byTable = new LinkedHashMap<>();
        int missing = 0;
        for (String resource : BUILT_IN) {
            Path toml = root.resolve("plugins").resolve(builtInId(resource)).resolve("jk-plugin.toml");
            if (!Files.isRegularFile(toml)) {
                missing++;
                continue;
            }
            try {
                PluginDescriptor manifest = parseBuiltIn(Files.readString(toml, StandardCharsets.UTF_8), resource);
                byTable.put(manifest.table(), manifest);
            } catch (IOException e) {
                throw new UncheckedIOException("failed to load built-in plugin manifest " + resource, e);
            }
        }
        if (missing == BUILT_IN.size()) return Map.of();
        if (missing > 0) {
            throw new IllegalStateException("missing built-in plugin manifest sources ("
                    + missing
                    + "/"
                    + BUILT_IN.size()
                    + "; first-party manifests live under plugins/<id>/jk-plugin.toml)");
        }
        return byTable;
    }

    /**
     * Workspace root that owns the running tests. {@code user.dir} is tried first (module root
     * when the launcher sets it); otherwise walk from this class's code source — jk's test
     * classes live at {@code target/<module-rel>/classes/test}, so {@code user.dir} inference
     * from {@code …/target/classes/test} does not apply, but core's classes dir still sits under
     * the checkout.
     */
    static Path discoverTestWorkspaceRoot() {
        Path fromCwd = workspaceRootOwning(
                Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize());
        if (fromCwd != null) return fromCwd;
        return workspaceRootFromCodeSource();
    }

    private static Path workspaceRootOwning(Path dir) {
        if (dir == null) return null;
        var owned = WorkspaceScan.findRoot(dir);
        if (owned.isPresent() && isFirstPartyPluginCheckout(owned.get())) return owned.get();
        if (WorkspaceScan.isWorkspaceRoot(dir) && isFirstPartyPluginCheckout(dir)) return dir;
        var enclosing = WorkspaceScan.findEnclosingWorkspace(dir);
        if (enclosing.isPresent() && isFirstPartyPluginCheckout(enclosing.get())) return enclosing.get();
        return null;
    }

    private static Path workspaceRootFromCodeSource() {
        try {
            var src = PluginTableRegistry.class.getProtectionDomain().getCodeSource();
            if (src == null || src.getLocation() == null) return null;
            Path loc = Path.of(src.getLocation().toURI()).toAbsolutePath().normalize();
            if (Files.isRegularFile(loc)) loc = loc.getParent();
            for (int i = 0; i < 10 && loc != null; i++, loc = loc.getParent()) {
                if (isFirstPartyPluginCheckout(loc)) return loc;
            }
        } catch (Exception ignored) {
            // not a filesystem class location
        }
        return null;
    }

    /** True when {@code dir} is a jk checkout that ships first-party {@code plugins/<id>/jk-plugin.toml}. */
    private static boolean isFirstPartyPluginCheckout(Path dir) {
        return Files.isRegularFile(dir.resolve("jk.toml"))
                && Files.isRegularFile(dir.resolve("plugins")
                        .resolve(builtInId(BUILT_IN.getFirst()))
                        .resolve("jk-plugin.toml"));
    }

    private static String zipEntryText(Path jar, String entry) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry e = zip.getEntry(entry);
            if (e == null) return null;
            try (InputStream in = zip.getInputStream(e)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }
}
