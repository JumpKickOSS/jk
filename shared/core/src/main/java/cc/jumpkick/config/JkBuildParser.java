// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.library.LibraryCatalog;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.DependencyKind;
import cc.jumpkick.model.Feature;
import cc.jumpkick.model.Features;
import cc.jumpkick.model.GitRefSpec;
import cc.jumpkick.model.GitSource;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.ObjectStoreConfig;
import cc.jumpkick.model.PathSource;
import cc.jumpkick.model.PlatformPolicy;
import cc.jumpkick.model.PluginDeclaration;
import cc.jumpkick.model.Profile;
import cc.jumpkick.model.Profiles;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.Variants;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.model.Workspace;
import cc.jumpkick.model.Workspace.WorkspaceDependency;
import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.manifest.PluginContributions;
import cc.jumpkick.plugin.manifest.PluginDescriptor;
import cc.jumpkick.plugin.manifest.PluginDescriptorStore;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import cc.jumpkick.util.GitUrl;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/** Loads {@code jk.toml} into a {@link JkBuild}. */
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
    //
    // Keyed by PATH, with the stamp in the value: keying by (path, size, mtime) would make every
    // save of a jk.toml a NEW key, stranding the superseded JkBuild for the engine's lifetime —
    // unbounded growth across a long `jk watch` session. One entry per file, replaced in place
    // (JK-1483).
    private static final Map<Path, Cached> PARSE_CACHE = new ConcurrentHashMap<>();

    private record Cached(long size, FileTime modified, JkBuild value) {}

    /**
     * Parse {@code jk.toml} and resolve workspace inheritance / sibling placeholders for the module
     * directory that owns the file. Prefer this for build, publish, status, etc.
     */
    public static JkBuild parse(Path file) throws IOException {
        Path abs = file.toAbsolutePath().normalize();
        Path dir = abs.getParent();
        return WorkspaceResolve.applyWorkspace(dir, parseLocal(file));
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
        JkBuild parsed = parse(
                Files.readString(file),
                LibraryCatalog.layered(),
                file.toAbsolutePath().getParent());
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
     * Test seam: parse against a synthetic library catalog instead of the default layered one. The
     * manifest's own {@code [libraries]} table is still layered on top via {@link
     * LibraryCatalog#withProjectOverrides}.
     */
    public static JkBuild parse(String toml, LibraryCatalog catalog) {
        return parse(toml, catalog, null);
    }

    /**
     * Full parse. {@code moduleDir} may be null (string parses); when set, loads third-party
     * manifests from {@link PluginDescriptorStore}.
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
        // Workspace roots keep concrete [project] defaults; members may omit fields and inherit.
        boolean workspaceRoot = hasWorkspaceModules(result);
        JkBuild.Project project = parseProject(result, workspaceRoot);
        LibraryCatalog effective = catalogBase(result, catalog).withProjectOverrides(parseProjectLibraries(result));
        Workspace workspace = parseWorkspace(result, effective);
        JkBuild.Dependencies deps = parseDependencies(result, workspace, effective);
        List<RepositorySpec> repos = parseRepositories(result);
        Profiles profiles = parseProfiles(result);
        Features features = parseFeatures(result);
        Map<String, String> manifest = parseManifest(result);
        List<PluginDeclaration> plugins = parsePlugins(result);
        Optional<JkBuild.Application> application = parseApplication(result);
        Optional<JkBuild.NativeConfig> nativeConfig = parseNativeConfig(result);
        List<PluginDescriptor> installedManifests = PluginTableRegistry.manifestsFor(moduleDir, plugins);
        Map<String, PluginConfig> pluginConfigs = parsePluginTables(result, installedManifests);
        // minified = true enables the minified packager without requiring an empty [minified] table.
        pluginConfigs = ensureMinifiedPluginConfigured(application, pluginConfigs, installedManifests);
        checkUnownedTables(result, moduleDir, plugins, installedManifests);
        deps = withPlatformContributions(deps, project, nativeConfig.isPresent(), pluginConfigs, installedManifests);
        JkBuild.Build build = parseBuild(result);
        List<JkBuild.KotlinPluginDecl> kotlinPlugins = parseKotlinPlugins(result);
        if (!kotlinPlugins.isEmpty()) {
            build = new JkBuild.Build(
                    build.orderAfter(),
                    build.testPluginJars(),
                    build.lint(),
                    kotlinPlugins,
                    build.kspOptions(),
                    build.extraSrc(),
                    build.testWorkers(),
                    build.platformPolicy(),
                    build.unmappedPolicy(),
                    build.extraResources(),
                    build.testEnv());
        }
        // [test] is its own top-level table (test settings are not build inputs), but it folds into
        // the Build block, which already carries the other test-scoped setting, test-plugin-jars.
        Map<String, String> testEnv = parseTestEnv(result);
        if (!testEnv.isEmpty()) {
            build = new JkBuild.Build(
                    build.orderAfter(),
                    build.testPluginJars(),
                    build.lint(),
                    build.kotlinPlugins(),
                    build.kspOptions(),
                    build.extraSrc(),
                    build.testWorkers(),
                    build.platformPolicy(),
                    build.unmappedPolicy(),
                    build.extraResources(),
                    testEnv);
        }
        JkBuild.FormatConfig format = parseFormat(result);
        Variants variants = parseVariants(result, workspace, effective, installedManifests);
        // project.*.workspace = true is for members only — the root is the inheritance source.
        if (project.inheritsFromWorkspace() && workspace != null && !workspace.isEmpty()) {
            throw new JkBuildParseException("workspace root must set concrete [project] values"
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

    /**
     * The {@code [variants]} block: {@code [variants.<dim>.<value>]} declares one value of one
     * dimension, whose body is an overlay — {@code extra-src}, dependency-scope sub-tables
     * (parsed with the standard scope grammar, so shorthands / workspace refs / git deps all
     * work), and plugin sub-tables validated against the owning plugin's schema (partial: nothing
     * required, no defaults). {@code [variants.<dim>] default = "<value>"} makes the dimension
     * optional; without it a declared dimension must be selected ({@code --variant <dim>=<value>}).
     */
    private static Variants parseVariants(
            TomlTable root, Workspace workspace, LibraryCatalog catalog, List<PluginDescriptor> installed) {
        TomlTable table = root.getTable("variants");
        if (table == null) return Variants.EMPTY;
        Map<String, PluginDescriptor> byTable = new LinkedHashMap<>();
        for (PluginDescriptor m : installed) byTable.put(m.table(), m);
        List<Variants.Dimension> dimensions = new ArrayList<>();
        for (String dim : table.keySet()) {
            TomlTable dimTable = table.getTable(dim);
            if (dimTable == null) {
                throw new JkBuildParseException("[variants." + dim + "] must be a table of named values");
            }
            String defaultValue = null;
            Map<String, Variants.Value> values = new LinkedHashMap<>();
            for (String key : dimTable.keySet()) {
                if (key.equals("default")) {
                    defaultValue = dimTable.getString("default");
                    if (defaultValue == null || defaultValue.isBlank()) {
                        throw new JkBuildParseException("[variants." + dim + "].default must be a value name");
                    }
                    continue;
                }
                TomlTable valueTable = dimTable.getTable(key);
                if (valueTable == null) {
                    throw new JkBuildParseException("[variants." + dim + "]." + key
                            + " must be a table (a value's overlay) — or `default = \"<value>\"`");
                }
                values.put(
                        key, parseVariantValue("variants." + dim + "." + key, valueTable, workspace, catalog, byTable));
            }
            try {
                dimensions.add(new Variants.Dimension(dim, defaultValue, values));
            } catch (IllegalArgumentException e) {
                throw new JkBuildParseException(e.getMessage());
            }
        }
        return new Variants(dimensions);
    }

    private static Variants.Value parseVariantValue(
            String where,
            TomlTable valueTable,
            Workspace workspace,
            LibraryCatalog catalog,
            Map<String, PluginDescriptor> pluginsByTable) {
        List<String> extraSrc = List.of();
        EnumMap<Scope, List<Dependency>> deps = new EnumMap<>(Scope.class);
        Map<String, Map<String, Object>> pluginOverlays = new LinkedHashMap<>();
        for (String key : valueTable.keySet()) {
            if (key.equals("extra-src")) {
                extraSrc = requireStringList(valueTable, "extra-src", "[" + where + "].extra-src");
                continue;
            }
            Scope scope = scopeForSection(key);
            if (scope != null) {
                TomlTable scopeTable = valueTable.getTable(key);
                if (scopeTable == null) {
                    throw new JkBuildParseException("[" + where + "." + key + "] must be a dependency table");
                }
                List<Dependency> parsed =
                        parseScopeTable(scopeTable, new ArrayList<>(scopeTable.keySet()), scope, workspace, catalog);
                if (!parsed.isEmpty()) deps.put(scope, parsed);
                continue;
            }
            PluginDescriptor plugin = pluginsByTable.get(key);
            if (plugin != null) {
                TomlTable overlay = valueTable.getTable(key);
                if (overlay == null) {
                    throw new JkBuildParseException(
                            "[" + where + "." + key + "] must be a table of [" + plugin.table() + "] key overlays");
                }
                pluginOverlays.put(
                        plugin.table(), PluginTableRegistry.validateOverlay(plugin, where + "." + key, overlay));
                continue;
            }
            throw new JkBuildParseException("[" + where + "]." + key + " is not an overlayable section —"
                    + " expected extra-src, a dependency scope table (dependencies, test-dependencies, ...),"
                    + " or an installed plugin's table " + pluginsByTable.keySet());
        }
        return new Variants.Value(extraSrc, deps, pluginOverlays);
    }

    /** The {@link Scope} whose toml section is {@code name}, or null. */
    private static Scope scopeForSection(String name) {
        for (Scope scope : Scope.values()) {
            if (scope.tomlSection().equals(name)) return scope;
        }
        return null;
    }

    private static List<String> requireStringList(TomlTable table, String key, String where) {
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

    /**
     * Parse the optional {@code [format]} table — the styles {@code jk format} uses. {@code style} is
     * a cross-language preset; {@code java} / {@code kotlin} are per-language overrides. All are
     * plain strings, validated downstream by {@code jk format} (the model + parser stay
     * tool-agnostic). Absent → EMPTY.
     */
    private static JkBuild.FormatConfig parseFormat(TomlTable root) {
        TomlTable format = root.getTable("format");
        if (format == null) return JkBuild.FormatConfig.EMPTY;
        Boolean optimizeImports = format.contains("optimize-imports") ? format.getBoolean("optimize-imports") : null;
        return new JkBuild.FormatConfig(
                stringOrThrow(format, "style", "format.style"),
                stringOrThrow(format, "java", "format.java"),
                stringOrThrow(format, "kotlin", "format.kotlin"),
                optimizeImports);
    }

    /** Read an optional string key; present-but-non-string is a parse error; absent → null. */
    private static String stringOrThrow(TomlTable table, String key, String path) {
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
     * <em>not</em> read here; it derives from {@code project.main}.
     */
    private static Map<String, String> parseManifest(TomlParseResult root) {
        TomlTable table = root.getTable("manifest");
        if (table == null) return Map.of();
        Map<String, String> attrs = new java.util.LinkedHashMap<>();
        for (String key : table.keySet()) {
            String value = table.getString(key);
            if (value == null) {
                throw new JkBuildParseException("manifest." + key + " must be a string");
            }
            if (key.equalsIgnoreCase("Main-Class")) {
                throw new JkBuildParseException("manifest.Main-Class is not allowed; set project.main instead");
            }
            attrs.put(key, value);
        }
        return attrs;
    }

    /**
     * Parse the optional top-level {@code [libraries]} table. Empty map when absent. Validated
     * through {@link LibraryCatalog#parseLibrariesTable} so the schema matches the bundled and user
     * files.
     */
    private static java.util.Map<String, LibraryCatalog.Module> parseProjectLibraries(TomlTable root) {
        TomlTable libraries = root.getTable("libraries");
        if (libraries == null) return java.util.Map.of();
        try {
            return LibraryCatalog.parseLibrariesTable(libraries, "jk.toml");
        } catch (IllegalStateException e) {
            throw new JkBuildParseException(e.getMessage(), e);
        }
    }

    /** True when {@code [workspace] modules = [...]} is a non-empty array — a workspace root. */
    private static boolean hasWorkspaceModules(TomlTable root) {
        TomlTable ws = root.getTable("workspace");
        if (ws == null || !ws.contains("modules") || !ws.isArray("modules")) return false;
        TomlArray modules = ws.getArray("modules");
        return modules != null && modules.size() > 0;
    }

    /**
     * @param workspaceRoot when true, omitted optional fields keep local defaults (no inherit);
     *     group/name/version stay required and concrete. When false (module or standalone), omitted
     *     fields other than {@code name} and {@code description} mark workspace inheritance —
     *     members resolve them from the root; standalones drop optional inherits and still require
     *     concrete group+version (or fail if those were omitted).
     */
    private static JkBuild.Project parseProject(TomlTable root, boolean workspaceRoot) {
        TomlTable project = root.getTable("project");
        if (project == null) {
            throw new JkBuildParseException("jk.toml must declare a top-level `[project]` table");
        }
        // name is the module identity — never workspace-inherited (Cargo package.name rule).
        if (project.isTable("name")) {
            throw new JkBuildParseException(
                    "project.name cannot use workspace inheritance — every module must declare its own name");
        }
        java.util.EnumSet<JkBuild.ProjectInherit> inherits = java.util.EnumSet.noneOf(JkBuild.ProjectInherit.class);

        String name = requireString(project, "name", "project.name");

        String group = parseInheritableString(
                project,
                "group",
                JkBuild.ProjectInherit.GROUP,
                inherits,
                workspaceRoot,
                /* requiredWhenRootOrStandalone */ true);

        String version = parseInheritableString(
                project,
                "version",
                JkBuild.ProjectInherit.VERSION,
                inherits,
                workspaceRoot,
                /* requiredWhenRootOrStandalone */ true);

        String jdk;
        if (isWorkspaceInherit(project, "jdk") || (!workspaceRoot && !project.contains("jdk"))) {
            inherits.add(JkBuild.ProjectInherit.JDK);
            jdk = null;
        } else {
            jdk = parseJdkSpec(project);
        }

        int java;
        if (isWorkspaceInherit(project, "java") || (!workspaceRoot && !project.contains("java"))) {
            inherits.add(JkBuild.ProjectInherit.JAVA);
            java = 0;
        } else {
            java = parseJavaRelease(project);
            requireSupportedMajor("project.java", java);
        }

        VersionSelector kotlin;
        if (isWorkspaceInherit(project, "kotlin") || (!workspaceRoot && !project.contains("kotlin"))) {
            inherits.add(JkBuild.ProjectInherit.KOTLIN);
            kotlin = null;
        } else {
            kotlin = parseKotlinVersion(project);
        }

        VersionSelector groovy;
        if (isWorkspaceInherit(project, "groovy") || (!workspaceRoot && !project.contains("groovy"))) {
            inherits.add(JkBuild.ProjectInherit.GROOVY);
            groovy = null;
        } else {
            groovy = parseGroovyVersion(project);
        }

        // sources = true → PUBLISH; sources = "always" → ALWAYS; absent/false → DISABLED
        // description is special: omit stays null (no auto-inherit). Explicit description.workspace = true ok.
        JkBuild.SourcesMode sourcesMode;
        if (isWorkspaceInherit(project, "sources") || (!workspaceRoot && !project.contains("sources"))) {
            inherits.add(JkBuild.ProjectInherit.SOURCES);
            sourcesMode = JkBuild.SourcesMode.DISABLED;
        } else {
            Object sourcesRaw = project.get("sources");
            if ("always".equalsIgnoreCase(sourcesRaw instanceof String s ? s : "")) {
                sourcesMode = JkBuild.SourcesMode.ALWAYS;
            } else if (Boolean.TRUE.equals(sourcesRaw)) {
                sourcesMode = JkBuild.SourcesMode.PUBLISH;
            } else {
                sourcesMode = JkBuild.SourcesMode.DISABLED;
            }
        }

        String description;
        if (isWorkspaceInherit(project, "description")) {
            inherits.add(JkBuild.ProjectInherit.DESCRIPTION);
            description = null;
        } else {
            // Omitted description stays unset — never auto-inherits from the workspace root.
            description = project.getString("description");
        }

        boolean m2install;
        if (isWorkspaceInherit(project, "m2install") || (!workspaceRoot && !project.contains("m2install"))) {
            inherits.add(JkBuild.ProjectInherit.M2INSTALL);
            m2install = false;
        } else {
            // m2install defaults to false: ~/.cache/jk is primary. true mirrors into ~/.m2.
            m2install = Boolean.TRUE.equals(project.getBoolean("m2install"));
        }

        JkBuild.Layout layout;
        if (isWorkspaceInherit(project, "layout") || (!workspaceRoot && !project.contains("layout"))) {
            inherits.add(JkBuild.ProjectInherit.LAYOUT);
            layout = JkBuild.Layout.AUTO;
        } else if (project.contains("layout")) {
            String layoutRaw = project.getString("layout");
            try {
                layout = JkBuild.Layout.parse(layoutRaw);
            } catch (IllegalArgumentException e) {
                throw new JkBuildParseException(e.getMessage());
            }
        } else {
            layout = JkBuild.Layout.AUTO;
        }

        return new JkBuild.Project(
                group, name, version, jdk, java, kotlin, groovy, sourcesMode, description, m2install, layout, inherits);
    }

    /**
     * String project field that may be concrete, {@code field.workspace = true}, or omitted (member
     * → inherit; root → error if required).
     */
    private static String parseInheritableString(
            TomlTable project,
            String key,
            JkBuild.ProjectInherit inherit,
            java.util.EnumSet<JkBuild.ProjectInherit> inherits,
            boolean workspaceRoot,
            boolean required) {
        String path = "project." + key;
        if (isWorkspaceInherit(project, key)) {
            if (workspaceRoot) {
                throw new JkBuildParseException("workspace root must set a concrete " + path + " (`" + key
                        + ".workspace = true` is only valid" + " on workspace modules)");
            }
            inherits.add(inherit);
            return JkBuild.VERSION_FROM_WORKSPACE;
        }
        if (!project.contains(key)) {
            if (workspaceRoot || required) {
                // Members: omit → inherit. Roots: omit of group/version → error.
                if (!workspaceRoot) {
                    inherits.add(inherit);
                    return JkBuild.VERSION_FROM_WORKSPACE;
                }
            }
            if (required) {
                throw new JkBuildParseException("jk.toml is missing required key `" + path + "`");
            }
            return null;
        }
        String value = project.getString(key);
        if (value == null) {
            throw new JkBuildParseException(path + " must be a string (e.g. \"1.0.0\") or `{ workspace = true }`");
        }
        if (value.isBlank()) {
            throw new JkBuildParseException(path + " must not be blank");
        }
        return value;
    }

    /**
     * Cargo-style {@code field.workspace = true} / {@code field = { workspace = true }} under
     * {@code [project]}. Only the boolean {@code true} is legal; extra keys are rejected.
     */
    private static boolean isWorkspaceInherit(TomlTable project, String key) {
        if (!project.contains(key) || !project.isTable(key)) return false;
        TomlTable t = project.getTable(key);
        Boolean ws = t.getBoolean("workspace");
        if (!Boolean.TRUE.equals(ws)) {
            throw new JkBuildParseException("project."
                    + key
                    + ".workspace must be `true` (the only legal value), or set project."
                    + key
                    + " to a concrete value");
        }
        for (String k : t.keySet()) {
            if (!"workspace".equals(k)) {
                throw new JkBuildParseException("project."
                        + key
                        + " with workspace inheritance must only set `workspace = true` (unexpected key `"
                        + k
                        + "`)");
            }
        }
        return true;
    }

    /**
     * {@code project.jdk}: vendor+major, bare major, unquoted int, or keyword
     * ({@code lts}/{@code stable}/{@code latest}/{@code native}). Point releases rejected.
     * Absent/blank → null.
     */
    private static String parseJdkSpec(TomlTable project) {
        String spec = parseVersionSpec(project, "jdk", "project.jdk", "\"temurin-25\" or \"25\"");
        if (spec == null || isVersionKeyword(spec)) return spec;
        int major = JkBuild.Project.majorOf(spec);
        if (major == 0) {
            throw new JkBuildParseException(
                    "project.jdk = \"" + spec + "\" must include a major version (e.g. \"temurin-25\" or \"25\")");
        }
        requireSupportedMajor("project.jdk", major);
        return spec;
    }

    /**
     * {@code [native].graal}: same shape as {@code project.jdk}; {@code "native"} ≡ {@code "graalvm"}.
     * Point releases rejected. Absent/blank → null.
     */
    private static String parseGraalSpec(TomlTable native_) {
        return parseVersionSpec(native_, "graal", "[native].graal", "\"graalvm-25\", \"25\", or \"native\"");
    }

    /**
     * Shared {@code jdk}/{@code graal} spec parser: int or string; keywords pass through; point
     * releases rejected. Null when absent/blank.
     */
    private static String parseVersionSpec(TomlTable table, String key, String pathLabel, String exampleHint) {
        if (!table.contains(key)) return null;
        Object raw = table.get(key);
        String spec;
        if (raw instanceof Long l) {
            spec = Long.toString(l);
        } else if (raw instanceof String s) {
            spec = s.trim();
        } else {
            throw new JkBuildParseException(pathLabel + " must be a string, e.g. " + exampleHint);
        }
        if (spec.isEmpty()) return null;
        if (isVersionKeyword(spec)) return spec;
        if (JkBuild.Project.hasPointRelease(spec)) {
            throw new JkBuildParseException(pathLabel
                    + " = \""
                    + spec
                    + "\" must not pin a point release — use \"<vendor>-<major>\" or "
                    + "\"<major>\" (e.g. "
                    + exampleHint
                    + "); jk keeps the patch current.");
        }
        return spec;
    }

    /** Version keywords (kept local so :core does not depend on :toolchain-jdk). */
    private static boolean isVersionKeyword(String spec) {
        String norm = spec.toLowerCase(Locale.ROOT);
        return norm.equals("lts") || norm.equals("stable") || norm.equals("latest") || norm.equals("native");
    }

    /**
     * {@code project.java} accepts either an unquoted TOML integer or a quoted numeric string
     * (coerced). Absent → {@code 0} ({@code javaRelease} falls back to the {@code jdk} major).
     */
    private static int parseJavaRelease(TomlTable project) {
        if (!project.contains("java")) return 0;
        Object raw = project.get("java");
        long value;
        if (raw instanceof Long l) {
            value = l;
        } else if (raw instanceof String s) {
            try {
                value = Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                throw new JkBuildParseException("project.java must be an integer, got: \"" + s + "\"");
            }
        } else {
            throw new JkBuildParseException("project.java must be an integer");
        }
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new JkBuildParseException("project.java out of range: " + value);
        }
        return (int) value;
    }

    /**
     * {@code project.kotlin} is a Kotlin compiler version selector (string), parsed the same way as a
     * floating dependency version: bare {@code 2.3.21} → caret, {@code =2.3.21} pins. Absent → {@code
     * null} (a Java project).
     */
    private static VersionSelector parseKotlinVersion(TomlTable project) {
        if (!project.contains("kotlin")) return null;
        String raw = project.getString("kotlin");
        if (raw == null) {
            throw new JkBuildParseException("project.kotlin must be a version string, e.g. \"2.3.21\"");
        }
        if (raw.isBlank()) return null;
        return VersionSelector.parseFloating(raw);
    }

    /**
     * {@code project.groovy} is a Groovy compiler version selector (string), parsed the same way as
     * a floating dependency version: bare {@code 5.0.4} → caret, {@code =5.0.4} pins. Absent →
     * {@code null} (not a Groovy project).
     */
    private static VersionSelector parseGroovyVersion(TomlTable project) {
        if (!project.contains("groovy")) return null;
        String raw = project.getString("groovy");
        if (raw == null) {
            throw new JkBuildParseException("project.groovy must be a version string, e.g. \"5.0.4\"");
        }
        if (raw.isBlank()) return null;
        return VersionSelector.parseFloating(raw);
    }

    /**
     * jk only supports JDK 17 and above (LTS + latest). Reject any older value at parse time so users
     * learn the constraint up front instead of in the middle of a resolve.
     */
    private static void requireSupportedMajor(String path, int value) {
        if (value == 0) return;
        if (value < 17) {
            throw new JkBuildParseException(path
                    + " = "
                    + value
                    + " is not supported — jk targets JDK 17 and above "
                    + "(LTS: 17, 21, 25, … plus the latest release).");
        }
    }

    // Dependencies

    private static JkBuild.Dependencies parseDependencies(TomlTable root, Workspace workspace, LibraryCatalog catalog) {
        EnumMap<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);

        // [dependencies] → MAIN scope (all entries are flat deps, no sub-tables)
        TomlTable mainDeps = root.getTable("dependencies");
        if (mainDeps != null) {
            List<Dependency> parsed =
                    parseScopeTable(mainDeps, new ArrayList<>(mainDeps.keySet()), Scope.MAIN, workspace, catalog);
            if (!parsed.isEmpty()) byScope.put(Scope.MAIN, parsed);
        }

        // Top-level scope tables: [test-dependencies], [provided-dependencies], etc.
        addScopeDeps(byScope, root, Scope.TEST, workspace, catalog);
        addScopeDeps(byScope, root, Scope.PROVIDED, workspace, catalog);
        addScopeDeps(byScope, root, Scope.PROCESSOR, workspace, catalog);
        addScopeDeps(byScope, root, Scope.EXPORT, workspace, catalog);
        addScopeDeps(byScope, root, Scope.RUNTIME, workspace, catalog);
        // [platform-dependencies] — BOM imports (version constraints, not classpath entries).
        addScopeDeps(byScope, root, Scope.PLATFORM, workspace, catalog);
        // DEV / TEST_DEV: run-time only / run+test, never packaged.
        addScopeDeps(byScope, root, Scope.DEV, workspace, catalog);
        addScopeDeps(byScope, root, Scope.TEST_DEV, workspace, catalog);

        return new JkBuild.Dependencies(byScope);
    }

    private static void addScopeDeps(
            EnumMap<Scope, List<Dependency>> byScope,
            TomlTable root,
            Scope scope,
            Workspace workspace,
            LibraryCatalog catalog) {
        TomlTable table = root.getTable(scope.tomlSection());
        if (table == null) return;
        List<Dependency> parsed = parseScopeTable(table, new ArrayList<>(table.keySet()), scope, workspace, catalog);
        if (!parsed.isEmpty()) byScope.put(scope, parsed);
    }

    private static List<Dependency> parseScopeTable(
            TomlTable scopeTable, List<String> keys, Scope scope, Workspace workspace, LibraryCatalog catalog) {
        List<Dependency> result = new ArrayList<>(keys.size());
        for (String name : keys) {
            Object value = scopeTable.get(List.of(name));
            if (value instanceof String versionShorthand) {
                // Cargo-style one-liner: `name = "1.0.0"`. Resolve the
                // coord via the bundled catalog; the user provides only
                // the version selector.
                result.add(parseShorthandEntry(name, versionShorthand, scope, catalog));
                continue;
            }
            if (!(value instanceof TomlTable entry)) {
                throw new JkBuildParseException(scope.tomlSection()
                        + "."
                        + name
                        + " must be an inline table (e.g. { group = \"...\", version = \"...\" })"
                        + " or a version-string shorthand for a catalog-known name");
            }
            result.add(parseDepEntry(name, entry, scope, workspace, catalog));
        }
        return result;
    }

    /**
     * Resolve a {@code name = "value"} string shorthand. Two forms are recognised:
     *
     * <ul>
     * <li>Git URL — value starts with {@code git://} or {@code https://}: a git dependency with
     * URL-embedded ref/subdir parsing. When no ref is embedded, {@code branch = "main"} is
     * implied.
     * <li>Version spec — anything else: looked up in the bundled catalog by {@code name} and
     * treated as a floating version selector (the Cargo-style {@code name = "1.2.3"} form).
     * </ul>
     *
     * <p>A leading {@code.} or {@code /} is a local-path shorthand — a consume-only path dependency
     * ({@link Dependency#pathByName}), built compile/package-only. A local sibling that should be
     * built fully (with tests) belongs in {@code [workspace] modules} instead.
     */
    private static Dependency parseShorthandEntry(String name, String value, Scope scope, LibraryCatalog catalog) {
        String displayPath = scope.tomlSection() + "." + name;
        if (value.isBlank()) {
            throw new JkBuildParseException(displayPath + " has an empty value string");
        }

        // Local-path shorthand: a relative (`./x`, `../x`) or absolute (`/x`) path is a consume-only
        // path dependency. `isVersionSpecOrKeyword` already excludes `.`/`/`-leading strings, so this
        // never shadows a version spec.
        if (value.startsWith(".") || value.startsWith("/")) {
            return Dependency.pathByName(name, new PathSource(value));
        }

        // Git URL shorthand: starts with "git://" or "https://".
        if (value.startsWith("git://") || value.startsWith("https://")) {
            EmbeddedUrlParts parts = splitEmbeddedUrl(value);
            GitRefSpec ref;
            boolean shallow;
            if (parts.refSpec() != null) {
                ref = parseUrlEmbeddedRefSpec(parts.refSpec());
                shallow = false;
            } else {
                // No ref embedded → default to branch "main", full clone.
                ref = new GitRefSpec.Branch("main");
                shallow = false;
            }
            String canonical = GitUrl.canonicalize(parts.baseUrl());
            GitSource source = new GitSource(canonical, parts.baseUrl(), ref, parts.subdir(), true, false, shallow);
            return Dependency.gitByName(name, source);
        }

        // Version spec or reserved keyword → catalog lookup.
        // A reserved keyword (latest/stable/lts/…) or a string that starts with a
        // version-spec character (digit, ^, ~, =, >, <) is always a catalog dep.
        if (isVersionSpecOrKeyword(value)) {
            LibraryCatalog.Module mod = catalog.lookup(name)
                    .orElseThrow(() -> new JkBuildParseException(unknownLibraryMessage(displayPath, name, catalog)));
            VersionSelector selector = VersionSelector.parseFloating(value);
            return Dependency.of(name, mod.moduleKey(), selector);
        }

        // Ambiguous string: not a recognised version spec, not a git URL. There is no
        // deferred-path fallback anymore — this is always an unknown-library error.
        throw new JkBuildParseException(unknownLibraryMessage(displayPath, name, catalog));
    }

    /**
     * Returns {@code true} when {@code value} should always be treated as a version spec or
     * reserved keyword — never as a filesystem path — regardless of what the filesystem contains.
     *
     * <ul>
     * <li>Reserved keywords: {@code latest}, {@code stable}, {@code lts}, {@code preview},
     * {@code nightly}.
     * <li>Version spec operators: leading {@code ^} (caret), {@code ~} (tilde), {@code =}
     * (exact), {@code >}, {@code <}.
     * <li>Bare version numbers: leading digit (e.g. {@code 1.2.3}, {@code 2.0}).
     * </ul>
     */
    static boolean isVersionSpecOrKeyword(String value) {
        if (value.isEmpty()) return false;
        return switch (value) {
            case "latest", "stable", "lts", "preview", "nightly" -> true;
            default -> {
                char first = value.charAt(0);
                yield Character.isDigit(first)
                        || first == '^'
                        || first == '~'
                        || first == '='
                        || first == '>'
                        || first == '<';
            }
        };
    }

    /** Unknown short-name error, with catalog "did you mean" suggestions when available. */
    /**
     * Top-level {@code catalog = "bundled" | "layered"} (default layered). {@code bundled} pins
     * short-name resolution to the catalog shipped inside this jk build, immune to
     * {@code ~/.jk/libs.toml} and the downloaded registry mirror — jk's own manifests use it so a
     * machine-local catalog entry can never repoint self-host dependencies at re-lock. The
     * manifest's own {@code [libraries]} table still layers on top either way.
     */
    private static LibraryCatalog catalogBase(TomlParseResult result, LibraryCatalog fallback) {
        if (!result.contains("catalog")) return fallback;
        if (!result.isString("catalog")) {
            throw new JkBuildParseException("catalog must be a string: \"bundled\" or \"layered\"");
        }
        String mode = result.getString("catalog");
        return switch (mode) {
            case "bundled" -> LibraryCatalog.bundled();
            case "layered" -> fallback;
            default ->
                throw new JkBuildParseException("catalog must be \"bundled\" or \"layered\", got \"" + mode + "\"");
        };
    }

    private static String unknownLibraryMessage(String displayPath, String name, LibraryCatalog catalog) {
        StringBuilder msg = new StringBuilder(displayPath)
                .append(" — unknown short name `")
                .append(name)
                .append("`. ");
        List<String> suggestions = catalog.suggestionsFor(name, 5);
        if (!suggestions.isEmpty()) {
            msg.append("Did you mean: ").append(String.join(", ", suggestions)).append("? ");
        }
        msg.append("Either spell out the coord as `{ group = \"...\", version = \"...\" }`, ")
                .append("or pick a curated name from the catalog.");
        return msg.toString();
    }

    private static Dependency parseDepEntry(
            String name, TomlTable entry, Scope scope, Workspace workspace, LibraryCatalog catalog) {
        // `optional = true` withholds the dep from the default resolution; a
        // [features] entry pulls it in by name. Works with every dep form
        // (coord / git / path / workspace / sha256) since it's applied to the
        // parsed result regardless of source.
        boolean optional = Boolean.TRUE.equals(entry.getBoolean("optional"));
        Dependency dep =
                parseDepEntryForm(name, entry, scope, workspace, catalog).withOptional(optional);
        dep = applyDependencyKind(dep, entry, scope, name);
        // Cross-package features: only when the consumer set `features` and/or
        // `default-features` — absent keys leave prior resolve behavior unchanged.
        boolean hasFeaturesKey = entry.contains("features");
        boolean hasDefaultFeaturesKey = entry.contains("default-features");
        if (!hasFeaturesKey && !hasDefaultFeaturesKey) return dep;
        List<String> features = hasFeaturesKey
                ? optionalStringList(entry, "features", scope.tomlSection() + "." + name + ".features")
                : List.of();
        boolean defaultFeatures = !hasDefaultFeaturesKey || !Boolean.FALSE.equals(entry.getBoolean("default-features"));
        return dep.withFeatures(features, defaultFeatures);
    }

    /**
     * {@code kind = "main"|"tests"} — workspace sibling tests kind (Mill {@code testModuleDeps})
     * or external Maven test-jar. Tests kind is only legal in test scopes so helpers never leak
     * into main jars. External (non-workspace) kind=tests is only legal on Maven GAs.
     */
    private static Dependency applyDependencyKind(Dependency dep, TomlTable entry, Scope scope, String name) {
        if (!entry.contains("kind")) return dep;
        String displayPath = scope.tomlSection() + "." + name;
        String raw = entry.getString("kind");
        DependencyKind kind;
        try {
            kind = DependencyKind.parse(raw);
        } catch (IllegalArgumentException e) {
            throw new JkBuildParseException(displayPath + ".kind: " + e.getMessage());
        }
        if (kind == DependencyKind.MAIN) return dep.withKind(kind);
        // kind = "tests"
        if (scope != Scope.TEST && scope != Scope.TEST_DEV) {
            throw new JkBuildParseException(displayPath
                    + ".kind = \"tests\" is only legal under [test-dependencies] or"
                    + " [test-dev-dependencies] (got ["
                    + scope.tomlSection()
                    + "])");
        }
        if (!dep.isWorkspace()) {
            if (dep.isGit() || dep.isPath() || dep.isFile()) {
                throw new JkBuildParseException(displayPath
                        + ".kind = \"tests\" requires `workspace = true` or a Maven"
                        + " coordinate (got git/path/file source)");
            }
            // Maven GA only (group:artifact). packageKey maps this to g:a:test-jar:tests.
            String mod = dep.module();
            if (mod == null || mod.indexOf(':') <= 0 || mod.indexOf(':') != mod.lastIndexOf(':')) {
                throw new JkBuildParseException(displayPath
                        + ".kind = \"tests\" on an external dep requires a Maven"
                        + " group:artifact module");
            }
        }
        return dep.withKind(kind);
    }

    private static Dependency parseDepEntryForm(
            String name, TomlTable entry, Scope scope, Workspace workspace, LibraryCatalog catalog) {
        String displayPath = scope.tomlSection() + "." + name;
        boolean hasWorkspace = entry.contains("workspace");
        boolean hasVersion = entry.contains("version");
        boolean hasGit = entry.contains("git");
        boolean hasSha256 = entry.contains("sha256");
        // A standalone `path` (not the git sub-directory modifier, which only applies alongside
        // `git`) is a consume-only path dependency.
        boolean hasPath = entry.contains("path") && !hasGit;

        int sourceCount = (hasVersion ? 1 : 0)
                + (hasGit ? 1 : 0)
                + (hasWorkspace ? 1 : 0)
                + (hasSha256 ? 1 : 0)
                + (hasPath ? 1 : 0);
        // The only legal multi-source pairing: sha256 + version (version records the coordinate).
        boolean sha256WithVersion = hasSha256 && hasVersion && !hasGit && !hasWorkspace;
        // No source + group/name = platform-managed (BOM supplies the version at resolve).
        boolean platformManaged = sourceCount == 0 && (entry.contains("group") || entry.contains("name"));
        if (sourceCount == 0 && !platformManaged) {
            throw new JkBuildParseException(
                    displayPath + " must set exactly one of `version`, `git`, `path`, `sha256`, or"
                            + " `workspace = true` — or `group`/`name` alone for a version managed by a"
                            + " [platform-dependencies] BOM");
        }
        if (sourceCount > 1 && !sha256WithVersion) {
            throw new JkBuildParseException(displayPath
                    + " sets more than one of `version` / `git` / `path` / `sha256` / `workspace`; "
                    + "pick exactly one");
        }

        if (hasWorkspace) {
            Boolean ws = entry.getBoolean("workspace");
            if (!Boolean.TRUE.equals(ws)) {
                throw new JkBuildParseException(displayPath + ".workspace must be `true` (the only legal value)");
            }
            // workspace = true is mutually exclusive with group/name too.
            if (entry.contains("group") || entry.contains("name")) {
                throw new JkBuildParseException(
                        displayPath + " with `workspace = true` must not set `group` or `name`");
            }
            // kind is applied in parseDepEntry after this form returns.
            return resolveWorkspaceDep(name, displayPath, workspace);
        }

        // For non-workspace deps, group/name may come from the table or
        // fall back to the bundled catalog (which keys off the short name).
        // A git source still REQUIRES explicit `group` — it's inherently a
        // user-controlled override where defaulting silently would be
        // surprising.
        String groupExplicit = entry.getString("group");
        String artifactExplicit = entry.getString("name");
        LibraryCatalog.Module catalogHit =
                (groupExplicit == null) ? catalog.lookup(name).orElse(null) : null;

        String group = groupExplicit != null ? groupExplicit : (catalogHit != null ? catalogHit.group() : null);
        String artifact =
                artifactExplicit != null ? artifactExplicit : (catalogHit != null ? catalogHit.artifact() : name);
        if (artifact != null && artifact.isBlank()) {
            throw new JkBuildParseException(displayPath + ".name must not be blank");
        }

        if (hasSha256) {
            if (groupExplicit == null || groupExplicit.isBlank()) {
                throw new JkBuildParseException(displayPath
                        + " with `sha256 = ...` must set a `group` explicitly "
                        + "(catalog shorthand applies only to version-based deps)");
            }
            String sha256 = entry.getString("sha256");
            if (sha256 == null || sha256.isBlank()) {
                throw new JkBuildParseException(displayPath + ".sha256 must not be blank");
            }
            String versionRaw = entry.getString("version");
            if (versionRaw == null || versionRaw.isBlank()) {
                throw new JkBuildParseException(displayPath + " with `sha256 = ...` must also set `version`");
            }
            return Dependency.file(name, group + ":" + artifact, versionRaw, sha256);
        }

        if (hasGit) {
            // git deps are always pure discovery: the coordinate (group, name) and
            // version come from the cloned repo's jk.toml. Specifying `group`,
            // `name`, or `version` in the dep entry is an error.
            for (String forbidden : new String[] {"group", "name", "version"}) {
                if (entry.contains(forbidden)) {
                    throw new JkBuildParseException(displayPath
                            + " with `git` must not set `" + forbidden + "` — the coordinate"
                            + " and version are always read from the cloned repo's jk.toml");
                }
            }
            return Dependency.gitByName(name, parseGitSource(entry, displayPath));
        }

        if (hasPath) {
            // Like git, a path dep is pure discovery: the coordinate and version are read from the
            // target project when it's built (its jk.toml for a jk project, or the derived GAV for a
            // Gradle/Maven project). Specifying `group`/`name`/`version` here is an error.
            for (String forbidden : new String[] {"group", "name", "version"}) {
                if (entry.contains(forbidden)) {
                    throw new JkBuildParseException(displayPath
                            + " with `path` must not set `" + forbidden + "` — the coordinate"
                            + " and version are always read from the target project");
                }
            }
            String pathValue = entry.getString("path");
            if (pathValue == null || pathValue.isBlank()) {
                throw new JkBuildParseException(displayPath + ".path must not be blank");
            }
            return Dependency.pathByName(name, new PathSource(pathValue));
        }

        if (platformManaged) {
            if (group == null || group.isBlank()) {
                throw new JkBuildParseException(displayPath
                        + " has no `version` and no resolvable `group` — a platform-managed dep needs"
                        + " an explicit `group` (or a catalog short name)");
            }
            return Dependency.platformManaged(name, group + ":" + artifact);
        }

        // version-only.
        if (group == null || group.isBlank()) {
            throw new JkBuildParseException(displayPath + " must set a `group` (or use a catalog-known short name)");
        }
        String versionRaw = entry.getString("version");
        if (versionRaw == null || versionRaw.isBlank()) {
            throw new JkBuildParseException(displayPath + ".version must not be blank");
        }
        VersionSelector selector = VersionSelector.parseFloating(versionRaw);
        return Dependency.of(name, group + ":" + artifact, selector);
    }

    private static Dependency resolveWorkspaceDep(String name, String displayPath, Workspace workspace) {
        // The workspace lookup chain: modules are resolved upstream at
        // merge time (we don't have them here at single-file parse time),
        // so first check [workspace.dependencies], then fall back to
        // emitting a placeholder coord (Dependency.workspace) that
        // WorkspaceMerge can re-resolve against the sibling list.
        if (workspace != null) {
            WorkspaceDependency wd = workspace.dependencies().get(name);
            if (wd != null) {
                return materialize(name, wd);
            }
        }
        // No [workspace.dependencies] match. The parser cannot resolve the
        // sibling here — that requires the full module list, which only
        // WorkspaceMerge / WorkspaceLoader has. Emit a placeholder dep
        // tagged with the short name; WorkspaceMerge resolves it. The
        // unresolved state is encoded as a synthetic workspace:<name>
        // module with a Latest selector; the resolver never sees this
        // because WorkspaceMerge rewrites it first.
        return Dependency.workspace(name);
    }

    private static Dependency materialize(String name, WorkspaceDependency wd) {
        String module = wd.module();
        if (wd.gitSource() != null) {
            return Dependency.git(name, module, wd.gitSource());
        }
        return Dependency.of(name, module, wd.version());
    }

    private static GitSource parseGitSource(TomlTable obj, String displayPath) {
        String urlRaw = obj.getString("git");
        if (urlRaw == null) {
            throw new JkBuildParseException(displayPath + " requires a `git` URL");
        }

        EmbeddedUrlParts parts = splitEmbeddedUrl(urlRaw);

        String tag = obj.getString("tag");
        String branch = obj.getString("branch");
        String rev = obj.getString("rev");
        boolean hasExplicitRef = tag != null || branch != null || rev != null;

        if (parts.refSpec() != null && hasExplicitRef) {
            throw new JkBuildParseException(displayPath
                    + " sets both a URL-embedded ref (`@` or `#` suffix) and an explicit ref"
                    + " key (`tag`, `branch`, or `rev`); use one or the other");
        }

        String explicitPath = obj.getString("path");
        if (parts.subdir() != null && explicitPath != null) {
            throw new JkBuildParseException(displayPath
                    + " sets both a URL-embedded sub-directory (`!` suffix) and an explicit"
                    + " `path` key; use one or the other");
        }
        String path = parts.subdir() != null ? parts.subdir() : explicitPath;

        boolean submodules = obj.getBoolean("submodules", () -> true);
        boolean verifySigned = obj.getBoolean("verify-signed", () -> false);
        if (obj.contains("fetch")) {
            throw new JkBuildParseException(displayPath + ".fetch is no longer supported — every git dependency is"
                    + " resolved once and pinned in jk-lock.toml; a branch ref's tip only moves on an explicit `jk"
                    + " update --git` or `jk fetch`. Remove the `fetch` key.");
        }

        GitRefSpec ref;
        boolean shallow;

        if (parts.refSpec() != null) {
            // URL-embedded refs are always full (deep) clones regardless of ref type.
            ref = parseUrlEmbeddedRefSpec(parts.refSpec());
            shallow = false;
        } else {
            int set = (tag != null ? 1 : 0) + (branch != null ? 1 : 0) + (rev != null ? 1 : 0);
            if (set == 0) {
                throw new JkBuildParseException(
                        displayPath + " must set `tag`, `branch`, or `rev` (or embed the ref in the URL)");
            }
            if (set > 1) {
                throw new JkBuildParseException(displayPath + " must set exactly one of `tag`, `branch`, or `rev`");
            }
            if (tag != null) {
                ref = new GitRefSpec.Tag(tag);
                shallow = true; // explicit tag = → shallow clone
            } else if (branch != null) {
                ref = new GitRefSpec.Branch(branch);
                shallow = false;
            } else {
                ref = new GitRefSpec.Rev(rev);
                shallow = false;
            }
        }

        String canonical = GitUrl.canonicalize(parts.baseUrl());
        return new GitSource(canonical, parts.baseUrl(), ref, path, submodules, verifySigned, shallow);
    }

    /**
     * The result of splitting URL-embedded ref and sub-directory out of a raw git URL. At most one
     * of {@link #refSpec} and {@link #subdir} may be non-null when this is produced from a plain
     * URL, but the parser also handles both.
     *
     * @param baseUrl the git repository URL with no embedded suffix
     * @param subdir sub-directory inside the repo, from the {@code !path} suffix, or {@code null}
     * @param refSpec raw ref string prefixed by {@code "@"} or {@code "#"}, or {@code null}
     */
    record EmbeddedUrlParts(String baseUrl, String subdir, String refSpec) {}

    /**
     * Parse embedded ref ({@code @name} / {@code #sha}) and sub-directory ({@code !subdir}) out of
     * a raw git URL. Either or both may be absent. The two suffixes may appear in either order:
     *
     * <ul>
     * <li>{@code url@ref!subdir} — ref before subdir
     * <li>{@code url!subdir@ref} — subdir before ref
     * <li>{@code url#sha!subdir} / {@code url!subdir#sha} — sha with subdir
     * </ul>
     *
     * <p>The {@code @} ref delimiter is searched only after the last {@code /} or {@code:} in the
     * URL, so the {@code git@host} userinfo form is not confused for an embedded ref. The {@code #}
     * and {@code !} delimiters are searched from the start of the string (they are not valid in
     * standard git URL paths without encoding).
     */
    static EmbeddedUrlParts splitEmbeddedUrl(String urlRaw) {
        // @ must follow the authority so we don't mistake "git@github.com" for an embedded branch.
        // Anchor at the first '/' after the scheme+host ("://host/"), or after ':' for SCP form.
        int schemeEnd = urlRaw.indexOf("://");
        int pathStart;
        if (schemeEnd >= 0) {
            int hostSlash = urlRaw.indexOf('/', schemeEnd + 3);
            pathStart = hostSlash >= 0 ? hostSlash : urlRaw.length();
        } else {
            int colon = urlRaw.indexOf(':');
            pathStart = colon >= 0 ? colon : 0;
        }
        int atPos = urlRaw.indexOf('@', pathStart);
        int hashPos = urlRaw.indexOf('#');
        int bangPos = urlRaw.indexOf('!');

        // Ignore an @ that appears after a # (it would be inside the SHA string).
        if (hashPos >= 0 && atPos > hashPos) atPos = -1;

        boolean hasRef = atPos >= 0 || hashPos >= 0;
        boolean hasBang = bangPos >= 0;

        if (!hasRef && !hasBang) return new EmbeddedUrlParts(urlRaw, null, null);

        // Determine which delimiter comes first.
        int firstAt = atPos >= 0 ? atPos : Integer.MAX_VALUE;
        int firstHash = hashPos >= 0 ? hashPos : Integer.MAX_VALUE;
        int firstBang = bangPos >= 0 ? bangPos : Integer.MAX_VALUE;
        int first = Math.min(firstAt, Math.min(firstHash, firstBang));

        if (hasBang && bangPos == first) {
            // Pattern: baseUrl!subdir[@ref|#sha]
            String base = urlRaw.substring(0, bangPos);
            String rest = urlRaw.substring(bangPos + 1);
            int atInRest = rest.indexOf('@');
            int hashInRest = rest.indexOf('#');
            if (atInRest >= 0 && (hashInRest < 0 || atInRest < hashInRest)) {
                return new EmbeddedUrlParts(base, rest.substring(0, atInRest), "@" + rest.substring(atInRest + 1));
            } else if (hashInRest >= 0) {
                return new EmbeddedUrlParts(base, rest.substring(0, hashInRest), "#" + rest.substring(hashInRest + 1));
            } else {
                return new EmbeddedUrlParts(base, rest, null); // subdir only, no ref (will error at validation)
            }
        } else {
            // Pattern: baseUrl[@ref|#sha][!subdir]
            int refPos = firstAt < firstHash ? atPos : hashPos;
            char refChar = urlRaw.charAt(refPos);
            String base = urlRaw.substring(0, refPos);
            String refAndRest = urlRaw.substring(refPos + 1);
            int bangInRest = refAndRest.indexOf('!');
            if (bangInRest >= 0) {
                return new EmbeddedUrlParts(
                        base,
                        refAndRest.substring(bangInRest + 1),
                        (refChar == '#' ? "#" : "@") + refAndRest.substring(0, bangInRest));
            } else {
                return new EmbeddedUrlParts(base, null, (refChar == '#' ? "#" : "@") + refAndRest);
            }
        }
    }

    /**
     * Parse the raw ref string extracted from a URL suffix ({@code "@name"} or {@code "#sha"}).
     * A hex-only string (any length) → {@link GitRefSpec.Rev}; a version-like name (starts with a
     * digit, or {@code v}/{@code r} followed by a digit) → {@link GitRefSpec.Tag}; anything else →
     * {@link GitRefSpec.Branch}. All are {@code shallow = false} — URL-embedded refs always do a
     * full clone even when they resolve to a tag.
     */
    private static GitRefSpec parseUrlEmbeddedRefSpec(String prefixedRef) {
        // "#sha" is always a Rev; "@name" is classified by heuristic.
        if (prefixedRef.startsWith("#")) {
            return new GitRefSpec.Rev(prefixedRef.substring(1));
        }
        String name = prefixedRef.substring(1); // strip leading "@"
        if (name.isEmpty()) return new GitRefSpec.Branch(name);
        if (name.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
            return new GitRefSpec.Rev(name); // "@hexsha" form
        }
        char first = name.charAt(0);
        boolean versionLike = Character.isDigit(first)
                || ((first == 'v' || first == 'r') && name.length() > 1 && Character.isDigit(name.charAt(1)));
        return versionLike ? new GitRefSpec.Tag(name) : new GitRefSpec.Branch(name);
    }

    // Repositories / profiles / features / workspace

    /**
     * {@code [repositories]}. Credential and object-store fields keep their raw {@code ${VAR}} text:
     * expansion happens in {@code RepoCredentialResolver}, at the point a credential is actually
     * used.
     *
     * <p>Interpolating here made the parse environment-dependent, which is wrong in two ways. The
     * parse is memoized on (path, size, mtime), so the first caller's environment pinned everyone
     * else's interpolation; and the authoritative parse runs inside a long-lived engine, so it read
     * the daemon's environment rather than the caller's. Expanding at the point of use puts the
     * lookup where the request's environment is already in scope, and leaves the parse a pure
     * function of the file — fully cacheable, with no environment in its key.
     *
     * <p>URLs are deliberately <b>not</b> interpolated: the lockfile records a repository's URL, so
     * an environment-dependent URL would make a committed lock differ between machines from the same
     * commit. Credentials never reach the lock.
     */
    private static List<RepositorySpec> parseRepositories(TomlTable root) {
        TomlTable repos = root.getTable("repositories");
        if (repos == null) return List.of();
        List<RepositorySpec> result = new ArrayList<>(repos.size());
        for (String name : repos.keySet()) {
            Object value = repos.get(name);
            String url;
            Optional<RepoCredential> credential = Optional.empty();
            Optional<ObjectStoreConfig> objectStore = Optional.empty();
            List<String> groups = List.of();
            if (value instanceof String s) {
                url = s;
            } else if (value instanceof TomlTable t) {
                String u = t.getString("url");
                if (u == null) {
                    throw new JkBuildParseException("repositories." + name + " requires a string `url` field");
                }
                url = u;
                // Left unexpanded on purpose: ${VAR} in a credential or object-store value is expanded at
                // the credential resolver, not here, so a parsed manifest never carries a secret.
                credential = RepositoryToml.credential(t, java.util.function.UnaryOperator.identity());
                objectStore = RepositoryToml.objectStore(t, java.util.function.UnaryOperator.identity());
                try {
                    groups = RepositoryToml.groups(t, "repositories." + name);
                } catch (IllegalArgumentException e) {
                    throw new JkBuildParseException(e.getMessage(), e);
                }
            } else {
                throw new JkBuildParseException(
                        "repositories." + name + " must be a URL string or an inline table with `url`");
            }
            try {
                result.add(new RepositorySpec(name, URI.create(url), credential, objectStore, groups));
            } catch (IllegalArgumentException e) {
                throw new JkBuildParseException("repositories." + name + " has malformed URL: " + url, e);
            }
        }
        return result;
    }

    /**
     * Optional object-store config on a {@code [repositories.<name>]} table for {@code s3://}/{@code
     * gs://} backends: {@code region}, {@code endpoint}, {@code access-key}, {@code secret-key},
     * {@code session-token}. All support {@code ${ENV}} interpolation (so keys aren't committed
     * literally); any unset field falls back to the AWS environment / default chain.
     */
    private static Profiles parseProfiles(TomlTable root) {
        TomlTable profiles = root.getTable("profiles");
        if (profiles == null) return Profiles.empty();
        Map<String, Profile> byName = new LinkedHashMap<>();
        for (String name : profiles.keySet()) {
            TomlTable body = profiles.getTable(name);
            if (body == null) {
                throw new JkBuildParseException("profiles." + name + " must be a table");
            }
            String inherits = body.getString("inherits");
            List<String> javacArgs = optionalStringList(body, "javac", "profiles." + name + ".javac");
            List<String> jvmArgs = optionalStringList(body, "jvm-args", "profiles." + name + ".jvm-args");
            boolean includeTagsSet = body.contains("include-tags");
            boolean excludeTagsSet = body.contains("exclude-tags");
            List<String> includeTags = includeTagsSet
                    ? optionalStringList(body, "include-tags", "profiles." + name + ".include-tags")
                    : List.of();
            List<String> excludeTags = excludeTagsSet
                    ? optionalStringList(body, "exclude-tags", "profiles." + name + ".exclude-tags")
                    : List.of();
            byName.put(
                    name,
                    new Profile(
                            name,
                            inherits,
                            javacArgs,
                            jvmArgs,
                            includeTags,
                            excludeTags,
                            includeTagsSet,
                            excludeTagsSet));
        }
        return new Profiles(byName);
    }

    /**
     * {@code [test] include-tags} / {@code exclude-tags} — baseline tag filters when no profile or
     * CLI overrides apply. Empty lists when the table/key is absent.
     */
    public static TestTomlTags parseTestTags(Path buildFile) {
        if (buildFile == null || !java.nio.file.Files.isRegularFile(buildFile)) return TestTomlTags.EMPTY;
        try {
            String toml = java.nio.file.Files.readString(buildFile);
            TomlParseResult result = Toml.parse(toml);
            if (result.hasErrors()) return TestTomlTags.EMPTY;
            TomlTable test = result.getTable("test");
            if (test == null) return TestTomlTags.EMPTY;
            return new TestTomlTags(
                    optionalStringList(test, "include-tags", "test.include-tags"),
                    optionalStringList(test, "exclude-tags", "test.exclude-tags"));
        } catch (Exception e) {
            return TestTomlTags.EMPTY;
        }
    }

    /** Tag lists from {@code [test]} (baseline before profile/CLI). */
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

    private static Features parseFeatures(TomlTable root) {
        TomlTable features = root.getTable("features");
        if (features == null) return Features.empty();
        List<String> defaults = optionalStringList(features, "default", "features.default");
        Map<String, Feature> byName = new LinkedHashMap<>();
        for (String key : features.keySet()) {
            if (key.equals("default")) continue;
            TomlTable body = features.getTable(key);
            if (body == null) {
                throw new JkBuildParseException("features." + key + " must be a table with `deps` and/or `features`");
            }
            // `deps` and `features` are both lists of names (not coord strings).
            // Resolution against [dependencies.*] happens at activation time.
            List<String> deps = optionalStringList(body, "deps", "features." + key + ".deps");
            List<String> nested = optionalStringList(body, "features", "features." + key + ".features");
            byName.put(key, new Feature(key, deps, nested));
        }
        return new Features(byName, defaults);
    }

    private static Workspace parseWorkspace(TomlTable root, LibraryCatalog catalog) {
        TomlTable workspace = root.getTable("workspace");
        if (workspace == null) return null;
        List<String> modules = optionalStringList(workspace, "modules", "workspace.modules");
        Map<String, WorkspaceDependency> wsDeps = parseWorkspaceDependencies(workspace, catalog);
        return new Workspace(modules, wsDeps);
    }

    private static Map<String, WorkspaceDependency> parseWorkspaceDependencies(
            TomlTable workspace, LibraryCatalog catalog) {
        TomlTable wsDeps = workspace.getTable("dependencies");
        if (wsDeps == null) return Map.of();
        Map<String, WorkspaceDependency> out = new LinkedHashMap<>();
        for (String name : wsDeps.keySet()) {
            Object value = wsDeps.get(List.of(name));
            String displayPath = "workspace.dependencies." + name;
            if (value instanceof String shorthand) {
                out.put(name, parseWorkspaceShorthand(name, shorthand, displayPath, catalog));
            } else if (value instanceof TomlTable entry) {
                out.put(name, parseWorkspaceDepEntry(name, entry, catalog));
            } else {
                throw new JkBuildParseException(
                        displayPath + " must be a version-string shorthand" + " (e.g. \"1.2.3\") or an inline table");
            }
        }
        return out;
    }

    /**
     * String shorthand for a workspace dependency: {@code name = "1.2.3"} (or {@code "^1.0"}, {@code
     * "latest"}, …). The short {@code name} is resolved to a {@code group:artifact} through the
     * bundled catalog, matching the {@code [dependencies]} shorthand. Local-path and git-URL string
     * forms are not accepted here — a shared workspace dep must be a Maven coordinate (use the inline
     * {@code git = "..."} table form for git, or a {@code [workspace] modules} entry for a sibling).
     */
    private static WorkspaceDependency parseWorkspaceShorthand(
            String name, String value, String displayPath, LibraryCatalog catalog) {
        if (value.isBlank()) {
            throw new JkBuildParseException(displayPath + " has an empty value string");
        }
        if (value.startsWith(".")
                || value.startsWith("/")
                || value.startsWith("git://")
                || value.startsWith("https://")) {
            throw new JkBuildParseException(displayPath + " string shorthand must be a version spec"
                    + " (e.g. \"1.2.3\"); for a git source use the inline `{ git = \"...\" }` form,"
                    + " for a local sibling add it to `[workspace] modules`");
        }
        LibraryCatalog.Module mod = catalog.lookup(name)
                .orElseThrow(() -> new JkBuildParseException(unknownLibraryMessage(displayPath, name, catalog)));
        return new WorkspaceDependency(mod.group(), mod.artifact(), VersionSelector.parseFloating(value), null);
    }

    private static WorkspaceDependency parseWorkspaceDepEntry(String name, TomlTable entry, LibraryCatalog catalog) {
        String displayPath = "workspace.dependencies." + name;
        boolean hasVersion = entry.contains("version");
        boolean hasGit = entry.contains("git");
        if (entry.contains("path")) {
            throw new JkBuildParseException(displayPath + " uses `path = \"...\"` — this is no longer supported."
                    + " Move `" + name + "` into the root jk.toml's `[workspace] modules = [...]` list directly"
                    + " instead (it becomes an ordinary workspace sibling); remove this"
                    + " [workspace.dependencies." + name + "] entry.");
        }
        int sourceCount = (hasVersion ? 1 : 0) + (hasGit ? 1 : 0);
        if (sourceCount == 0) {
            throw new JkBuildParseException(displayPath + " must set exactly one of `version` or `git`");
        }
        if (sourceCount > 1) {
            throw new JkBuildParseException(displayPath + " sets more than one of `version` / `git`; pick exactly one");
        }
        if (hasGit) {
            // Git workspace deps still carry an explicit coordinate: the shared coordinate is what
            // sibling modules pin against, and it must be known at parse time.
            String gitGroup = entry.getString("group");
            if (gitGroup == null || gitGroup.isBlank()) {
                throw new JkBuildParseException(displayPath + " with `git` must set a `group`");
            }
            String gitArtifact = entry.getString("name");
            if (gitArtifact == null) gitArtifact = name;
            if (gitArtifact.isBlank()) {
                throw new JkBuildParseException(displayPath + ".name must not be blank");
            }
            GitSource source = parseGitSource(entry, displayPath);
            return new WorkspaceDependency(gitGroup, gitArtifact, null, source);
        }
        // Version dep: group/name may be explicit or resolved from the catalog by the short name,
        // exactly like a [dependencies] entry.
        String groupExplicit = entry.getString("group");
        String artifactExplicit = entry.getString("name");
        LibraryCatalog.Module catalogHit =
                (groupExplicit == null) ? catalog.lookup(name).orElse(null) : null;
        String group = groupExplicit != null ? groupExplicit : (catalogHit != null ? catalogHit.group() : null);
        String artifact =
                artifactExplicit != null ? artifactExplicit : (catalogHit != null ? catalogHit.artifact() : name);
        if (group == null || group.isBlank()) {
            throw new JkBuildParseException(displayPath + " must set a `group` (or use a catalog-known short name)");
        }
        if (artifact.isBlank()) {
            throw new JkBuildParseException(displayPath + ".name must not be blank");
        }
        String versionRaw = entry.getString("version");
        if (versionRaw == null || versionRaw.isBlank()) {
            throw new JkBuildParseException(displayPath + ".version must not be blank");
        }
        return new WorkspaceDependency(group, artifact, VersionSelector.parseFloating(versionRaw), null);
    }

    /**
     * The optional {@code [application]} table. Its mere presence marks the project as an
     * application ({@link JkBuild#isApplication}) — {@code Optional.empty} when absent, never a
     * defaulted-fields sentinel, so presence and "declared but empty" stay distinguishable.
     */
    private static Optional<JkBuild.Application> parseApplication(TomlTable root) {
        TomlTable application = root.getTable("application");
        if (application == null) return Optional.empty();
        String main = application.getString("main");
        return Optional.of(new JkBuild.Application(
                main, artifactFlag(application, "assembly"), artifactFlag(application, "minified")));
    }

    /**
     * One additive artifact switch. Artifacts stack — a thin jar always, {@code assembly} adds the
     * fat jar, {@code minified} adds the R8 jar and implies the fat one — so each key is a plain
     * boolean rather than a mode.
     */
    private static boolean artifactFlag(TomlTable application, String key) {
        if (!application.contains(key)) return false;
        if (application.isBoolean(key)) return Boolean.TRUE.equals(application.getBoolean(key));
        String raw = application.isString(key) ? application.getString(key) : null;
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (value.equals("true") || value.equals("on")) return true;
        if (value.equals("false") || value.equals("off") || value.equals("none")) return false;
        if (value.equals("shrink") || value.equals("shrunk") || value.equals("r8")) {
            throw new JkBuildParseException("[application]." + key + " is a boolean, not \"" + raw
                    + "\" — artifacts are additive: set `minified = true` for an R8 jar (it builds"
                    + " the fat jar too)");
        }
        throw new JkBuildParseException(
                "[application]." + key + " must be true or false (got \"" + (raw == null ? "" : raw) + "\")");
    }

    /** Schema-validate each installed plugin's owned table into a {@link PluginConfig}. */
    private static Map<String, PluginConfig> parsePluginTables(TomlTable root, List<PluginDescriptor> installed) {
        Map<String, PluginConfig> out = new LinkedHashMap<>();
        for (PluginDescriptor manifest : installed) {
            TomlTable table = root.getTable(manifest.table());
            if (table == null) continue;
            out.put(manifest.id(), PluginTableRegistry.validate(manifest, table));
        }
        return out;
    }

    /**
     * When {@code [application] minified = true} and no {@code [minified]} table is present, inject
     * minified plugin defaults so the packager is available.
     */
    /**
     * {@code minified = true} pulls in the minified plugin's config so the packager is active. The
     * reverse is not implied: a {@code [minified]} table configures the minified artifact, it does
     * not ask for one. Saying so is better than building the table's rules into nothing.
     */
    private static Map<String, PluginConfig> ensureMinifiedPluginConfigured(
            Optional<JkBuild.Application> application,
            Map<String, PluginConfig> pluginConfigs,
            List<PluginDescriptor> installed) {
        boolean minified = application.isPresent() && application.get().minified();
        if (!minified) {
            if (pluginConfigs.containsKey("minified")) {
                throw new JkBuildParseException("[minified] configures the minified jar, but no minified jar is"
                        + " requested — add `minified = true` under [application], or drop the [minified] table");
            }
            return pluginConfigs;
        }
        return ensureMinifiedPluginConfig(pluginConfigs, installed);
    }

    /**
     * Apply a CLI packaging override over a parsed build for this invocation only. Minified pulls
     * in the minified plugin config when the project has none; anything else drops it, so a prior
     * {@code minified = true} or a bare {@code [minified]} table cannot still produce an R8 jar for
     * this run.
     */
    public static JkBuild withArtifactOverride(JkBuild build, ArtifactOverride override) {
        Objects.requireNonNull(build, "build");
        if (override == null) return build;
        JkBuild next = build.withArtifacts(override.assembly(), override.minified());
        if (!override.minified()) return next.withoutPluginConfig("minified");
        if (next.pluginConfig("minified").isPresent()) return next;
        Map<String, PluginConfig> configs = ensureMinifiedPluginConfig(
                next.pluginConfigs(), PluginTableRegistry.manifestsFor(null, next.plugins()));
        PluginConfig minified = configs.get("minified");
        return minified == null ? next : next.withPluginConfig(minified);
    }

    /** Which artifacts a single invocation asks for, from {@code --fat} / {@code --minified}. */
    public record ArtifactOverride(boolean assembly, boolean minified) {
        public ArtifactOverride {
            if (minified) assembly = true;
        }
    }

    private static Map<String, PluginConfig> ensureMinifiedPluginConfig(
            Map<String, PluginConfig> pluginConfigs, List<PluginDescriptor> installed) {
        if (pluginConfigs.containsKey("minified")) return pluginConfigs;
        PluginDescriptor minified = null;
        for (PluginDescriptor m : installed) {
            if ("minified".equals(m.id()) || "minified".equals(m.table())) {
                minified = m;
                break;
            }
        }
        if (minified == null) {
            minified = PluginTableRegistry.byTable("minified").orElse(null);
        }
        if (minified == null) {
            throw new JkBuildParseException("minified = true requires the built-in minified plugin (not installed)");
        }
        TomlTable empty = Objects.requireNonNull(Toml.parse("[minified]\n").getTable("minified"));
        Map<String, PluginConfig> out = new LinkedHashMap<>(pluginConfigs);
        out.put(minified.id(), PluginTableRegistry.validate(minified, empty));
        return out;
    }

    /** Parse CLI / wire override: empty → null (no override), {@code fat} / {@code minified}. */
    public static ArtifactOverride parseArtifactOverride(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "fat", "true", "assembly" -> new ArtifactOverride(true, false);
            case "minified", "min" -> new ArtifactOverride(true, true);
            case "off", "false", "none", "thin" -> new ArtifactOverride(false, false);
            default -> throw new IllegalArgumentException("unknown artifact override: " + raw + " (want fat|minified)");
        };
    }

    /** Core top-level tables; anything else must be owned by an installed plugin. */
    private static final java.util.Set<String> CORE_TABLES = coreTables();

    private static java.util.Set<String> coreTables() {
        java.util.Set<String> out = new java.util.HashSet<>(java.util.Set.of(
                "project",
                "repositories",
                "profiles",
                "features",
                "workspace",
                "manifest",
                "plugins",
                "application",
                "native",
                "image",
                "train",
                "build",
                "test",
                "format",
                "resolve",
                "variants",
                "libraries",
                "jvm",
                "deny",
                "config",
                "forge",
                "kotlin-plugins"));
        for (Scope scope : Scope.values()) out.add(scope.tomlSection()); // [dependencies] + scoped spellings
        return java.util.Set.copyOf(out);
    }

    /**
     * Error on top-level tables neither core nor owned by an installed plugin. Suppressed while
     * any {@code [plugins]} declaration is still unresolved (unknown ownership pre-lock).
     */
    private static void checkUnownedTables(
            TomlTable root, Path moduleDir, List<PluginDeclaration> plugins, List<PluginDescriptor> installed) {
        if (!plugins.isEmpty() && PluginDescriptorStore.hasUnresolved(moduleDir, plugins)) return;
        java.util.Set<String> owned = new java.util.HashSet<>(CORE_TABLES);
        for (PluginDescriptor m : installed) owned.add(m.table());
        for (String key : root.keySet()) {
            if (owned.contains(key)) continue;
            if (!(root.get(key) instanceof TomlTable) && !(root.get(key) instanceof org.tomlj.TomlArray)) continue;
            StringBuilder known = new StringBuilder();
            for (PluginDescriptor m : installed) {
                if (known.length() > 0) known.append(", ");
                known.append('[').append(m.table()).append(']');
            }
            if ("shrink".equals(key)) {
                // The plugin was renamed (JK-1798); steer pre-rename projects the same way the
                // `assembly = "shrink"` migration message does.
                throw new JkBuildParseException(
                        "[shrink] was renamed — use a [minified] table (and `assembly = \"minified\"`)");
            }
            throw new JkBuildParseException("[" + key + "] is not owned by any installed plugin — add it under"
                    + " [plugins] (plugin tables installed here: " + (known.length() == 0 ? "none" : known) + ")");
        }
    }

    /**
     * Merge plugin platform contributions into deps; user-declared modules win (no duplicates).
     */
    /**
     * Re-fold conditioned plugin platform contributions against the RESOLVED project.
     * {@code parseLocal} folds them before workspace inheritance, so a manifest condition like
     * {@code kotlin-project} evaluates against the thin manifest (kotlin not yet inherited) and
     * conditioned deps silently never contribute. Idempotent: modules already declared (including
     * everything the pre-resolution fold added) are skipped.
     */
    static JkBuild reapplyPlatformContributions(Path moduleDir, JkBuild module) {
        try {
            List<PluginDescriptor> manifests = PluginTableRegistry.manifestsFor(moduleDir, module.plugins());
            return module.withDependencies(withPlatformContributions(
                    module.dependencies(),
                    module.project(),
                    module.nativeConfig().isPresent(),
                    module.pluginConfigs(),
                    manifests));
        } catch (RuntimeException e) {
            // Contribution refolding is best-effort here — parseLocal already surfaced real
            // manifest errors.
            return module;
        }
    }

    private static JkBuild.Dependencies withPlatformContributions(
            JkBuild.Dependencies deps,
            JkBuild.Project project,
            boolean nativeDeclared,
            Map<String, PluginConfig> pluginConfigs,
            List<PluginDescriptor> installedManifests) {
        List<PluginContributions.PlatformDep> contributed =
                PluginContributions.platformDependencies(project, nativeDeclared, pluginConfigs, installedManifests);
        if (contributed.isEmpty()) return deps;
        List<Dependency> platform = new ArrayList<>(deps.of(Scope.PLATFORM));
        boolean changed = false;
        for (PluginContributions.PlatformDep dep : contributed) {
            boolean declared = platform.stream().anyMatch(d -> dep.module().equals(d.module()));
            if (declared) continue;
            platform.add(new Dependency(dep.module(), VersionSelector.parseFloating(dep.version())));
            changed = true;
        }
        if (!changed) return deps;
        EnumMap<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        deps.byScope().forEach(byScope::put);
        byScope.put(Scope.PLATFORM, platform);
        return new JkBuild.Dependencies(byScope);
    }

    /**
     * Optional {@code [native]}: empty when absent (presence marks native-eligible). Default
     * {@code graal} is {@code "graalvm"}.
     */
    private static Optional<JkBuild.NativeConfig> parseNativeConfig(TomlTable root) {
        TomlTable native_ = root.getTable("native");
        if (native_ == null) return Optional.empty();
        String mainClass = native_.getString("main-class");
        String name = native_.getString("name");
        List<String> args = new ArrayList<>();
        TomlArray argsArr = native_.getArray("args");
        if (argsArr != null) {
            for (int i = 0; i < argsArr.size(); i++) {
                Object val = argsArr.get(i);
                if (!(val instanceof String s))
                    throw new JkBuildParseException("[native].args must be an array of strings");
                args.add(s);
            }
        }
        String graal = parseGraalSpec(native_);
        if (graal == null) graal = "graalvm";
        boolean always = Boolean.TRUE.equals(native_.getBoolean("always"));
        return Optional.of(new JkBuild.NativeConfig(mainClass, name, args, graal, always));
    }

    /**
     * Optional {@code [build]} and/or {@code [test]} tables. Either may appear alone: a lone
     * {@code [test] workers = 1} is enough for Mill-style serial opt-out without a {@code [build]}
     * block. Absent both → {@link JkBuild.Build#EMPTY}.
     */
    private static JkBuild.Build parseBuild(TomlTable root) {
        TomlTable build = root.getTable("build");
        TomlTable test = root.getTable("test");
        TomlTable resolve = root.getTable("resolve");
        // Dead/renamed [test] keys fail loudly: silently ignoring default-exclude-tags would run
        // the slow/integration tests the config meant to exclude with no signal (JK-1825).
        if (test != null) {
            if (test.contains("default-exclude-tags")) {
                throw new JkBuildParseException("[test].default-exclude-tags was renamed to exclude-tags "
                        + "(profiles and --exclude-tags replace it per run; `exclude-tags = []` clears)");
            }
            if (test.contains("include-tag") || test.contains("exclude-tag")) {
                throw new JkBuildParseException("[test] tag keys are plural: include-tags / exclude-tags");
            }
        }
        PlatformPolicy platformPolicy = PlatformPolicy.ENFORCED;
        cc.jumpkick.model.UnmappedPolicy unmappedPolicy = cc.jumpkick.model.UnmappedPolicy.MEDIATE;
        if (resolve != null && resolve.contains("platform")) {
            String raw = resolve.getString("platform");
            if (raw == null || raw.isBlank()) {
                throw new JkBuildParseException("[resolve].platform must be a string (enforced or floor)");
            }
            try {
                platformPolicy = PlatformPolicy.parse(raw);
            } catch (IllegalArgumentException e) {
                throw new JkBuildParseException("[resolve].platform: " + e.getMessage());
            }
        }
        if (resolve != null && resolve.contains("unmapped")) {
            String raw = resolve.getString("unmapped");
            if (raw == null || raw.isBlank()) {
                throw new JkBuildParseException("[resolve].unmapped must be a string (mediate or strict)");
            }
            try {
                unmappedPolicy = cc.jumpkick.model.UnmappedPolicy.parse(raw);
            } catch (IllegalArgumentException e) {
                throw new JkBuildParseException("[resolve].unmapped: " + e.getMessage());
            }
        }
        if (build == null && test == null && resolve == null) return JkBuild.Build.EMPTY;
        if (build == null && test == null) {
            return new JkBuild.Build(
                    List.of(),
                    List.of(),
                    true,
                    List.of(),
                    List.of(),
                    List.of(),
                    null,
                    platformPolicy,
                    unmappedPolicy,
                    List.of(),
                    Map.of());
        }

        List<String> orderAfter = new ArrayList<>();
        List<String> testPluginJars = new ArrayList<>();
        boolean lint = true;
        List<String> kspOptions = new ArrayList<>();
        List<String> extraSrc = new ArrayList<>();
        Integer testWorkers = null;

        if (build != null) {
            TomlArray arr = build.getArray("order-after");
            if (arr != null) {
                for (int i = 0; i < arr.size(); i++) {
                    Object val = arr.get(i);
                    if (!(val instanceof String s))
                        throw new JkBuildParseException("[build].order-after must be an array of strings");
                    if (!s.isBlank()) orderAfter.add(s);
                }
            }
            TomlArray twj = build.getArray("test-plugin-jars");
            if (twj != null) {
                for (int i = 0; i < twj.size(); i++) {
                    Object val = twj.get(i);
                    if (!(val instanceof String s))
                        throw new JkBuildParseException("[build].test-plugin-jars must be an array of strings");
                    if (!s.isBlank()) testPluginJars.add(s);
                }
            }
            // `lint` defaults on (surface deprecation/unchecked); `lint = false`
            // suppresses jk's default javac lint flags for users who don't want it.
            lint = !Boolean.FALSE.equals(build.getBoolean("lint"));
            // [build] ksp-options — project-declared KSP processor options (`key=value`; Room's
            // room.schemaLocation is the canonical consumer). Plugin manifests contribute theirs
            // via [[contribute.compiler-args]] ksp; this is the project-owned lane.
            TomlArray kspOpts = build.getArray("ksp-options");
            if (kspOpts != null) {
                for (int i = 0; i < kspOpts.size(); i++) {
                    Object val = kspOpts.get(i);
                    if (!(val instanceof String s) || s.isBlank() || !s.contains("=")) {
                        throw new JkBuildParseException("[build].ksp-options must be an array of key=value strings");
                    }
                    kspOptions.add(s);
                }
            }
            // [build] extra-src — additional module-relative source roots (variant overlays append).
            TomlArray es = build.getArray("extra-src");
            if (es != null) {
                for (int i = 0; i < es.size(); i++) {
                    Object val = es.get(i);
                    if (!(val instanceof String s) || s.isBlank())
                        throw new JkBuildParseException("[build].extra-src must be an array of directory strings");
                    extraSrc.add(s);
                }
            }
            // [build] test-workers — pin within-module test JVMs (1 = serial; 0 = auto; omit = CLI).
            // [build] test-parallel = false is an alias for test-workers = 1 (Mill testParallelism=false).
            if (build.contains("test-workers")) {
                Long n = build.getLong("test-workers");
                if (n == null) throw new JkBuildParseException("[build].test-workers must be an integer >= 0");
                if (n < 0) throw new JkBuildParseException("[build].test-workers must be >= 0");
                testWorkers = n.intValue();
            }
            if (Boolean.FALSE.equals(build.getBoolean("test-parallel"))) {
                testWorkers = 1;
            }
        }

        // Optional [test] table (Mill-shaped): workers / parallel override [build] pins when set.
        if (test != null) {
            if (test.contains("workers")) {
                Long n = test.getLong("workers");
                if (n == null) throw new JkBuildParseException("[test].workers must be an integer >= 0");
                if (n < 0) throw new JkBuildParseException("[test].workers must be >= 0");
                testWorkers = n.intValue();
            }
            if (Boolean.FALSE.equals(test.getBoolean("parallel"))) {
                testWorkers = 1;
            }
        }
        return new JkBuild.Build(
                orderAfter,
                testPluginJars,
                lint,
                List.of(),
                kspOptions,
                extraSrc,
                testWorkers,
                platformPolicy,
                unmappedPolicy,
                parseExtraResources(build),
                Map.of());
    }

    /**
     * {@code [build] extra-resources} — files from outside the module copied onto its classpath
     * . Each entry is an inline table:
     *
     * <pre>
     * extra-resources = [
     * { from = "../../plugins/&#42;/jk-plugin.toml", into = "cc/jumpkick/plugin/manifest",
     * rename = "{1}.jk-plugin.toml" },
     * ]
     * </pre>
     *
     * {@code from} is a module-relative glob; {@code exclude} narrows it; {@code optional} allows a
     * pattern to match nothing (by default that is an error, since a typo'd path that silently
     * contributes no files is indistinguishable from success until runtime).
     */
    /**
     * {@code [test] env} — environment variables for each forked test JVM.
     *
     * <pre>
     * [test]
     * env = { JK_HOME = "${target}/test-jk-home", JK_HTTP_ENABLED = "false" }
     * </pre>
     *
     * Values are literal strings; {@code ${target}} and {@code ${module}} are substituted at launch
     * (see {@code TestEnv}). Environment variables are deliberately <em>not</em> interpolated here
     * yet — that is whitelisted separately.
     */
    private static Map<String, String> parseTestEnv(TomlTable root) {
        TomlTable test = root.getTable("test");
        if (test == null) return Map.of();
        TomlTable env = test.getTable("env");
        if (env == null) return Map.of();
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (String key : env.keySet()) {
            Object value = env.get(List.of(key));
            if (value == null) continue;
            if (!(value instanceof String s)) {
                if (value instanceof Boolean || value instanceof Long || value instanceof Double) {
                    out.put(key, String.valueOf(value));
                    continue;
                }
                throw new JkBuildParseException("[test].env." + key + " must be a string (or a bare boolean/number)");
            }
            out.put(key, s);
        }
        return out;
    }

    private static List<JkBuild.ExtraResource> parseExtraResources(TomlTable build) {
        List<JkBuild.ExtraResource> out = new ArrayList<>();
        if (build == null) return out;
        TomlArray arr = build.getArray("extra-resources");
        if (arr == null) return out;
        for (int i = 0; i < arr.size(); i++) {
            if (!(arr.get(i) instanceof TomlTable entry)) {
                throw new JkBuildParseException("[build].extra-resources entries must be tables, e.g."
                        + " { from = \"../../plugins/*/jk-plugin.toml\", into = \"pkg/dir\" }");
            }
            String from = entry.getString("from");
            if (from == null || from.isBlank()) {
                throw new JkBuildParseException("[build].extra-resources entries require a `from` path or glob");
            }
            List<String> exclude = new ArrayList<>();
            TomlArray ex = entry.getArray("exclude");
            if (ex != null) {
                for (int j = 0; j < ex.size(); j++) {
                    Object v = ex.get(j);
                    if (!(v instanceof String g) || g.isBlank()) {
                        throw new JkBuildParseException(
                                "[build].extra-resources `exclude` must be an array of glob strings");
                    }
                    exclude.add(g);
                }
            }
            Boolean optional = entry.getBoolean("optional");
            out.add(new JkBuild.ExtraResource(
                    from, entry.getString("into"), entry.getString("rename"), exclude, optional != null && optional));
        }
        return out;
    }

    /**
     * {@code [[kotlin-plugins]]}: {@code coordinate} is {@code group:artifact[:version]} (omitted
     * version → project Kotlin version); {@code id} defaults to the artifact.
     */
    private static List<JkBuild.KotlinPluginDecl> parseKotlinPlugins(TomlTable root) {
        TomlArray arr = root.getArray("kotlin-plugins");
        if (arr == null) return List.of();
        List<JkBuild.KotlinPluginDecl> out = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            if (!(arr.get(i) instanceof TomlTable t)) {
                throw new JkBuildParseException("[[kotlin-plugins]] entries must be tables");
            }
            String coordinate = t.getString("coordinate");
            if (coordinate == null || coordinate.isBlank()) {
                throw new JkBuildParseException("[[kotlin-plugins]] requires a `coordinate`"
                        + " (group:artifact[:version]; version defaults to the project's Kotlin version)");
            }
            String[] parts = coordinate.split(":");
            if (parts.length < 2 || parts.length > 3 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new JkBuildParseException(
                        "[[kotlin-plugins]] coordinate must be group:artifact[:version] — got: " + coordinate);
            }
            String id = t.getString("id");
            List<String> options = new ArrayList<>();
            TomlArray opts = t.getArray("options");
            if (opts != null) {
                for (int j = 0; j < opts.size(); j++) {
                    if (!(opts.get(j) instanceof String o)) {
                        throw new JkBuildParseException("[[kotlin-plugins]].options must be an array of strings");
                    }
                    options.add(o);
                }
            }
            out.add(new JkBuild.KotlinPluginDecl(id == null || id.isBlank() ? parts[1] : id, coordinate, options));
        }
        return out;
    }

    private static final java.util.Set<String> PLUGIN_RESERVED =
            java.util.Set.of("group", "name", "version", "path", "sha256", "coordinate");

    private static List<PluginDeclaration> parsePlugins(TomlTable root) {
        TomlTable plugins = root.getTable("plugins");
        if (plugins == null) return List.of();
        List<PluginDeclaration> result = new ArrayList<>();
        for (String alias : plugins.keySet()) {
            Object val = plugins.get(alias);
            if (!(val instanceof TomlTable entry)) {
                throw new JkBuildParseException("plugins." + alias + " must be a table");
            }
            String shaRaw = entry.getString("sha256");
            if (shaRaw == null || shaRaw.isBlank()) {
                throw new JkBuildParseException("plugins." + alias
                        + " must declare `sha256` (content pin — refuse unpinned plugin jars)."
                        + " Compute with: sha256sum vendor/your-plugin.jar");
            }
            String path = entry.getString("path");
            String group = entry.getString("group");
            String name = entry.getString("name");
            String version = entry.getString("version");
            // coordinate = "g:a:v" shorthand for group/name/version.
            String coordinate = entry.getString("coordinate");
            if (coordinate != null && !coordinate.isBlank()) {
                String[] parts = coordinate.split(":", -1);
                if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
                    throw new JkBuildParseException("plugins." + alias + ".coordinate must be group:artifact:version");
                }
                if (group == null || group.isBlank()) group = parts[0];
                if (name == null || name.isBlank()) name = parts[1];
                if (version == null || version.isBlank()) version = parts[2];
            }
            boolean pathPin = path != null && !path.isBlank();
            if (pathPin) {
                // Path pin: identity for the lock is path:<alias>:local unless group/name/version given.
                if (group == null || group.isBlank()) group = PluginDeclaration.PATH_GROUP;
                if (name == null || name.isBlank()) name = alias;
                if (version == null || version.isBlank()) version = "local";
            } else {
                if (group == null || group.isBlank())
                    throw new JkBuildParseException(
                            "plugins." + alias + " must declare `group` (or `path` / `coordinate`)");
                if (name == null || name.isBlank())
                    throw new JkBuildParseException(
                            "plugins." + alias + " must declare `name` (or `path` / `coordinate`)");
                if (version == null || version.isBlank())
                    throw new JkBuildParseException(
                            "plugins." + alias + " must declare `version` (or `path` / `coordinate`)");
            }
            // Every key other than the reserved identity fields becomes plugin config.
            java.util.Map<String, Object> config = new java.util.LinkedHashMap<>();
            for (String key : entry.keySet()) {
                if (!PLUGIN_RESERVED.contains(key)) {
                    config.put(key, tomlToJava(entry.get(key)));
                }
            }
            try {
                result.add(new PluginDeclaration(
                        alias,
                        group,
                        name,
                        version,
                        pathPin ? path : null,
                        shaRaw,
                        java.util.Collections.unmodifiableMap(config)));
            } catch (IllegalArgumentException e) {
                throw new JkBuildParseException("plugins." + alias + ": " + e.getMessage());
            }
        }
        return List.copyOf(result);
    }

    /** Convert a tomlj value to a plain JDK type so plugin-api stays tomlj-free. */
    private static Object tomlToJava(Object value) {
        if (value instanceof TomlTable t) {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            for (String k : t.keySet()) map.put(k, tomlToJava(t.get(k)));
            return java.util.Collections.unmodifiableMap(map);
        }
        if (value instanceof TomlArray arr) {
            java.util.List<Object> list = new ArrayList<>(arr.size());
            for (int i = 0; i < arr.size(); i++) list.add(tomlToJava(arr.get(i)));
            return List.copyOf(list);
        }
        return value; // String, Long, Double, Boolean — already JDK types
    }

    private static String requireString(TomlTable table, String key, String displayPath) {
        String v = table.getString(key);
        if (v == null) {
            throw new JkBuildParseException("jk.toml is missing required key `" + displayPath + "`");
        }
        return v;
    }

    private static List<String> optionalStringList(TomlTable table, String key, String displayPath) {
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
}
