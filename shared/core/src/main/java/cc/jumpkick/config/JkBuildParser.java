// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.library.LibraryCatalog;
import cc.jumpkick.model.Features;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PluginConfig;
import cc.jumpkick.model.PluginDeclaration;
import cc.jumpkick.model.Profiles;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.Variants;
import cc.jumpkick.model.Workspace;
import cc.jumpkick.plugin.manifest.PluginDescriptor;
import cc.jumpkick.plugin.manifest.PluginDescriptorStore;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * Loads {@code jk.toml} into a {@link JkBuild}. Table parsers live in {@code Manifest*}
 * collaborators; this type is the disk/string facade and parse memo.
 */
public final class JkBuildParser {

    private JkBuildParser() {}

    /**
     * Process-lifetime memo of {@link #parseLocal(Path)}, keyed by absolute path (size + mtime live
     * in the value so rewrites re-parse and replace the entry). Stores the <em>local</em> manifest
     * only — workspace inheritance is applied by {@link #parse(Path)} on top so it always sees a
     * fresh root.
     */
    // A plain (size, mtime) memo: the parse is a pure function of the file's bytes again, so
    // nothing environment-shaped belongs in the stamp.

    // Keyed by PATH, with the stamp in the value: keying by (path, size, mtime) would make every
    // save of a jk.toml a NEW key, stranding the superseded JkBuild for the engine's lifetime —
    // unbounded growth across a long `jk watch` session. One entry per file, replaced in place.

    private static final Map<Path, Cached> PARSE_CACHE = new ConcurrentHashMap<>();

    private record Cached(long size, FileTime modified, JkBuild value) {}

    /**
     * Parse {@code jk.toml} and resolve workspace inheritance / sibling placeholders for the module
     * directory that owns the file. Prefer this for build, publish, status, etc.
     */
    public static JkBuild parse(Path file) throws IOException {
        Path abs = file.toAbsolutePath().normalize();
        Path dir = abs.getParent();
        JkBuild resolved = WorkspaceResolve.applyWorkspace(dir, parseLocal(file));
        List<cc.jumpkick.model.PluginDeclaration> user = UserPlugins.fromConfig();
        if (user.isEmpty()) return resolved;
        return resolved.withPlugins(UserPlugins.merge(user, resolved.plugins()));
    }

    /**
     * Parse {@code jk.toml} as written — no workspace inheritance or sibling-coordinate rewrite.
     * Used by {@link WorkspaceLoader} / {@link WorkspaceResolve} to avoid recursive resolve while
     * assembling the workspace.
     */
    public static JkBuild parseLocal(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(file, BasicFileAttributes.class);
        } catch (NoSuchFileException e) {
            throw new JkBuildParseException("jk.toml not found: " + file);
        }
        Path key = file.toAbsolutePath().normalize();
        Cached cached = PARSE_CACHE.get(key);
        if (cached != null && cached.size() == attrs.size() && cached.modified().equals(attrs.lastModifiedTime())) {
            return cached.value();
        }
        Path moduleDir = file.toAbsolutePath().normalize().getParent();
        LibraryCatalog catalog;
        try {
            catalog = LibraryCatalog.forProject(moduleDir);
        } catch (IllegalStateException e) {
            throw new JkBuildParseException(e.getMessage(), e);
        }
        JkBuild parsed = parse(Files.readString(file), catalog, moduleDir);
        PARSE_CACHE.put(key, new Cached(attrs.size(), attrs.lastModifiedTime(), parsed));
        return parsed;
    }

    /** Test seam: how many files the parse memo currently holds. */
    static int parseCacheSizeForTest() {
        return PARSE_CACHE.size();
    }

    public static JkBuild reparse(Path file) throws IOException {
        PARSE_CACHE.remove(file.toAbsolutePath().normalize());
        return parse(file);
    }

    /**
     * Raw structural probe: does {@code file} declare a non-empty {@code [workspace] modules}
     * array? Reads the TOML directly — never builds a {@link JkBuild}, resolves plugins, or reads
     * the lockfile. {@link cc.jumpkick.lock.LockPaths} calls this from lock-location discovery,
     * which itself runs <em>inside</em> a full parse (plugin-manifest resolution needs the lock
     * path); a full parse here would re-enter {@link #parseLocal} on the very file being parsed
     * and recurse until the stack blows.
     */
    public static boolean declaresWorkspaceModules(Path file) throws IOException {
        return hasWorkspaceModules(Toml.parse(Files.readString(file)));
    }

    /** Drop memo and re-parse without workspace resolution. */
    public static JkBuild reparseLocal(Path file) throws IOException {
        PARSE_CACHE.remove(file.toAbsolutePath().normalize());
        return parseLocal(file);
    }

    public static JkBuild parse(String toml) {
        return parse(toml, LibraryCatalog.layered());
    }

    /**
     * Test seam: parse against a synthetic library catalog instead of the default layered one.
     * Project short names come from {@code jk-libs.toml} at the workspace root when parsing from
     * disk; string parses use {@code catalog} as-is.
     */
    public static JkBuild parse(String toml, LibraryCatalog catalog) {
        return parse(toml, catalog, null);
    }

    /**
     * Full parse. {@code moduleDir} may be null (string parses); when set, loads third-party
     * manifests from {@link PluginDescriptorStore}. Short-name resolution uses the catalog the
     * caller supplied (disk parse passes {@link LibraryCatalog#forProject}).
     */
    private static JkBuild parse(String toml, LibraryCatalog catalog, Path moduleDir) {
        Objects.requireNonNull(toml, "toml");
        Objects.requireNonNull(catalog, "catalog");
        TomlParseResult result = Toml.parse(toml);
        if (result.hasErrors()) {
            throw new JkBuildParseException(
                    "failed to parse jk.toml: " + result.errors().getFirst().getMessage());
        }
        // Reject ${VAR} outside the whitelisted positions before anything else reads the file, so
        // the message names the position rather than surfacing later as a bewildering "no such
        // version".
        Interpolation.guard(result);
        rejectRemovedCatalogConfig(result);
        rejectRemovedProjectTable(result);
        // Workspace roots keep concrete project defaults; members may omit fields and inherit.
        boolean workspaceRoot = hasWorkspaceModules(result);
        JkBuild.Project project = ManifestProject.parseProject(result, workspaceRoot);
        LibraryCatalog effective = catalog;
        Workspace workspace = ManifestTables.parseWorkspace(result, effective);
        JkBuild.Dependencies deps = ManifestDeps.parseDependencies(result, workspace, effective);
        List<RepositorySpec> repos = ManifestTables.parseRepositories(result);
        Profiles profiles = ManifestTables.parseProfiles(result);
        Features features = ManifestTables.parseFeatures(result);
        Map<String, String> manifest = ManifestTables.parseManifest(result);
        List<PluginDeclaration> plugins = ManifestBuild.parsePlugins(result);
        Optional<JkBuild.Application> application = ManifestTables.parseApplication(result);
        if (application.isPresent() && cc.jumpkick.plugin.PluginModule.isWorker(moduleDir)) {
            throw new JkBuildParseException(
                    "[application] is for apps — a plugin worker (jk-plugin.toml or Plugin service)"
                            + " already implies main "
                            + cc.jumpkick.plugin.PluginModule.WORKER_MAIN
                            + "; drop the [application] table");
        }
        Optional<JkBuild.NativeConfig> nativeConfig = ManifestBuild.parseNativeConfig(result);
        if (application.map(JkBuild.Application::nativeImage).orElse(false)
                && nativeConfig.isPresent()
                && nativeConfig.get().enabled() == JkBuild.NativeMode.DISABLED) {
            throw new JkBuildParseException("[application].native = true conflicts with [native] enabled = false");
        }
        List<PluginDescriptor> installedManifests = PluginTableRegistry.manifestsFor(moduleDir, plugins);
        Map<String, PluginConfig> pluginConfigs = ManifestTables.parsePluginTables(result, installedManifests);
        // minified = true enables the minified packager without requiring an empty [minified] table.
        pluginConfigs = ManifestTables.ensureMinifiedPluginConfigured(application, pluginConfigs, installedManifests);
        ManifestBuild.checkUnownedTables(result, moduleDir, plugins, installedManifests);
        boolean nativeDeclared = nativeConfig.isPresent()
                || application.map(JkBuild.Application::nativeImage).orElse(false);
        deps = ManifestBuild.withPlatformContributions(
                deps, project, nativeDeclared, pluginConfigs, installedManifests);
        JkBuild.Build build = ManifestBuild.parseBuild(result);
        List<JkBuild.KotlinPluginDecl> kotlinPlugins = ManifestBuild.parseKotlinPlugins(result);
        if (!kotlinPlugins.isEmpty()) {
            build = new JkBuild.Build(
                    build.orderAfter(),
                    build.testPluginJars(),
                    build.lint(),
                    kotlinPlugins,
                    build.kspOptions(),
                    build.extraSrc(),
                    build.testWorkers(),
                    build.testSerialTags(),
                    build.platformPolicy(),
                    build.unmappedPolicy(),
                    build.extraResources(),
                    build.testEnv());
        }
        // [test] is its own top-level table (test settings are not build inputs), but it folds into
        // the Build block, which already carries the other test-scoped setting, test-plugin-jars.
        Map<String, String> testEnv = ManifestBuild.parseTestEnv(result);
        if (!testEnv.isEmpty()) {
            build = new JkBuild.Build(
                    build.orderAfter(),
                    build.testPluginJars(),
                    build.lint(),
                    build.kotlinPlugins(),
                    build.kspOptions(),
                    build.extraSrc(),
                    build.testWorkers(),
                    build.testSerialTags(),
                    build.platformPolicy(),
                    build.unmappedPolicy(),
                    build.extraResources(),
                    testEnv);
        }
        JkBuild.FormatConfig format = ManifestTables.parseFormat(result);
        Variants variants = ManifestTables.parseVariants(result, workspace, effective, installedManifests);
        // *.workspace = true is for members only — the root is the inheritance source.
        if (project.inheritsFromWorkspace() && workspace != null && !workspace.isEmpty()) {
            throw new JkBuildParseException("workspace root must set concrete project values"
                    + " (`*.workspace = true` is only valid on workspace modules)");
        }
        return new JkBuild(
                project,
                deps,
                repos,
                profiles,
                features,
                workspace,
                manifest,
                plugins,
                application,
                nativeConfig,
                pluginConfigs,
                build,
                format,
                variants);
    }

    /** The {@link Scope} whose toml section is {@code name}, or null. */
    static Scope scopeForSection(String name) {
        for (Scope scope : Scope.values()) {
            if (scope.tomlSection().equals(name)) return scope;
        }
        return null;
    }

    static List<String> requireStringList(TomlTable table, String key, String where) {
        TomlArray arr = table.getArray(key);
        if (arr == null) throw new JkBuildParseException(where + " must be an array of directory strings");
        List<String> out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            if (!(arr.get(i) instanceof String s) || s.isBlank()) {
                throw new JkBuildParseException(where + " must be an array of directory strings");
            }
            out.add(s);
        }
        return out;
    }

    /** Present boolean key → its value; absent → null (caller applies the default). */
    static Boolean optionalBool(TomlTable table, String key) {
        return table.contains(key) ? table.getBoolean(key) : null;
    }

    /** Read an optional string key; present-but-non-string is a parse error; absent → null. */
    static String stringOrThrow(TomlTable table, String key, String path) {
        if (!table.contains(key)) return null;
        String value = table.getString(key);
        if (value == null) {
            throw new JkBuildParseException(path + " must be a string");
        }
        return value;
    }

    /**
     * Parse the optional top-level {@code [manifest]} table — string-valued custom jar-manifest
     * attributes (e.g. {@code "Implementation-Title"}). {@code Main-Class} is intentionally
     * <em>not</em> read here; it derives from {@code main}.
     */
    /**
     * Reject removed catalog knobs. Project short names live in workspace-root {@code jk-libs.toml};
     * the system catalog is always global → bundled (no host-local file, no {@code catalog=} pin).
     */
    static void rejectRemovedCatalogConfig(TomlTable root) {
        if (root.contains("catalog")) {
            throw new JkBuildParseException("catalog = … was removed — short names always resolve through the system "
                    + "catalog (global + bundled) plus optional workspace-root "
                    + LibraryCatalog.PROJECT_FILE);
        }
        if (root.getTable("libraries") != null) {
            throw new JkBuildParseException(
                    "[libraries] in jk.toml was removed — put short-name → group:artifact entries in "
                            + LibraryCatalog.PROJECT_FILE
                            + " at the workspace root (standalone: project root)");
        }
    }

    /** {@code [project]} is gone — identity keys are bare top-level fields. */
    static void rejectRemovedProjectTable(TomlTable root) {
        if (root.getTable("project") != null) {
            throw new JkBuildParseException("[project] was removed — move its keys to the top level of jk.toml"
                    + " (e.g. name = \"…\", group = \"…\", version = \"…\")");
        }
    }

    /** True when {@code [workspace] modules = [...]} is a non-empty array — a workspace root. */
    static boolean hasWorkspaceModules(TomlTable root) {
        TomlTable ws = root.getTable("workspace");
        if (ws == null || !ws.contains("modules") || !ws.isArray("modules")) return false;
        TomlArray modules = ws.getArray("modules");
        return modules != null && modules.size() > 0;
    }

    /** Convert a tomlj value to a plain JDK type so plugin-api stays tomlj-free. */
    static Object tomlToJava(Object value) {
        if (value instanceof TomlTable t) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (String k : t.keySet()) map.put(k, tomlToJava(t.get(k)));
            return Collections.unmodifiableMap(map);
        }
        if (value instanceof TomlArray arr) {
            List<Object> list = new ArrayList<>(arr.size());
            for (int i = 0; i < arr.size(); i++) list.add(tomlToJava(arr.get(i)));
            return List.copyOf(list);
        }
        return value; // String, Long, Double, Boolean — already JDK types
    }

    static String requireString(TomlTable table, String key, String displayPath) {
        String v = table.getString(key);
        if (v == null) {
            throw new JkBuildParseException("jk.toml is missing required key `" + displayPath + "`");
        }
        return v;
    }

    static List<String> optionalStringList(TomlTable table, String key, String displayPath) {
        if (!table.contains(key)) return List.of();
        Object value = table.get(key);
        if (!(value instanceof TomlArray arr)) {
            throw new JkBuildParseException("expected `" + displayPath + "` to be a list of strings");
        }
        List<String> result = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            Object element = arr.get(i);
            if (!(element instanceof String s)) {
                throw new JkBuildParseException("expected `" + displayPath + "` to be a list of strings");
            }
            result.add(s);
        }
        return result;
    }

    static Variants parseVariants(
            TomlTable root, Workspace workspace, LibraryCatalog catalog, List<PluginDescriptor> installed) {
        return ManifestTables.parseVariants(root, workspace, catalog, installed);
    }

    static boolean isVersionSpecOrKeyword(String value) {
        return ManifestDeps.isVersionSpecOrKeyword(value);
    }

    record EmbeddedUrlParts(String baseUrl, String subdir, String refSpec) {}

    static EmbeddedUrlParts splitEmbeddedUrl(String urlRaw) {
        return ManifestDeps.splitEmbeddedUrl(urlRaw);
    }

    public static TestTomlTags parseTestTags(Path buildFile) {
        return ManifestTables.parseTestTags(buildFile);
    }

    public record TestTomlTags(List<String> includeTags, List<String> excludeTags) {
        public static final TestTomlTags EMPTY = new TestTomlTags(List.of(), List.of());

        public TestTomlTags {
            includeTags = includeTags == null ? List.of() : List.copyOf(includeTags);
            excludeTags = excludeTags == null ? List.of() : List.copyOf(excludeTags);
        }

        public boolean isEmpty() {
            return includeTags.isEmpty() && excludeTags.isEmpty();
        }
    }

    public static JkBuild withArtifactOverride(JkBuild build, ArtifactOverride override) {
        return ManifestTables.withArtifactOverride(build, override);
    }

    public record ArtifactOverride(boolean assembly, boolean minified) {
        public ArtifactOverride {
            if (minified) assembly = true;
        }
    }

    public static ArtifactOverride parseArtifactOverride(String raw) {
        return ManifestTables.parseArtifactOverride(raw);
    }

    static JkBuild reapplyPlatformContributions(Path moduleDir, JkBuild module) {
        return ManifestBuild.reapplyPlatformContributions(moduleDir, module);
    }
}
