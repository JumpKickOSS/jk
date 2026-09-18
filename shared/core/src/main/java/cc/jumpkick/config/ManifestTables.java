// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static cc.jumpkick.config.JkBuildParser.*;

import cc.jumpkick.library.LibraryCatalog;
import cc.jumpkick.model.BuildBlock;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.Feature;
import cc.jumpkick.model.Features;
import cc.jumpkick.model.GitSource;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PluginConfig;
import cc.jumpkick.model.PomMetadata;
import cc.jumpkick.model.Profile;
import cc.jumpkick.model.Profiles;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.Variants;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.model.Workspace;
import cc.jumpkick.model.Workspace.WorkspaceDependency;
import cc.jumpkick.plugin.manifest.PluginDescriptor;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import cc.jumpkick.run.TaskNames;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * Secondary tables: variants, format, repos, profiles, features, workspace, application.
 */
@NullMarked
public final class ManifestTables {

    private ManifestTables() {}

    /**
     * The {@code [variants]} block: {@code [variants.<dim>.<value>]} declares one value of one
     * dimension, whose body is an overlay — {@code extra-src}, dependency-scope sub-tables
     * (parsed with the standard scope grammar, so shorthands / workspace refs / git deps all
     * work), and plugin sub-tables validated against the owning plugin's schema (partial: nothing
     * required, no defaults). {@code [variants.<dim>] default = "<value>"} makes the dimension
     * optional; without it a declared dimension must be selected ({@code --variant <dim>=<value>}).
     */
    static Variants parseVariants(
            TomlTable root, @Nullable Workspace workspace, LibraryCatalog catalog, List<PluginDescriptor> installed) {
        TomlTable table = root.getTable("variants");
        if (table == null) return Variants.EMPTY;
        Map<String, PluginDescriptor> byTable = new LinkedHashMap<>();
        for (PluginDescriptor m : installed) byTable.put(m.table(), m);
        List<Variants.Dimension> dimensions = new ArrayList<>();
        for (String dim : table.keySet()) {
            TomlTable dimTable = table.getTable(List.of(dim));
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
                TomlTable valueTable = dimTable.getTable(List.of(key));
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

    static Variants.Value parseVariantValue(
            String where,
            TomlTable valueTable,
            @Nullable Workspace workspace,
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
                TomlTable scopeTable = valueTable.getTable(List.of(key));
                if (scopeTable == null) {
                    throw new JkBuildParseException("[" + where + "." + key + "] must be a dependency table");
                }
                List<Dependency> parsed = ManifestDeps.parseScopeTable(
                        scopeTable, new ArrayList<>(scopeTable.keySet()), scope, workspace, catalog);
                if (!parsed.isEmpty()) deps.put(scope, parsed);
                continue;
            }
            PluginDescriptor plugin = pluginsByTable.get(key);
            if (plugin != null) {
                TomlTable overlay = valueTable.getTable(List.of(key));
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

    static JkBuild.FormatConfig parseFormat(TomlTable root) {
        TomlTable format = root.getTable("format");
        if (format == null) return JkBuild.FormatConfig.EMPTY;
        return new JkBuild.FormatConfig(
                stringOrThrow(format, "style", "format.style"),
                stringOrThrow(format, "java", "format.java"),
                stringOrThrow(format, "kotlin", "format.kotlin"),
                optionalBool(format, "optimize-imports"),
                optionalBool(format, "import-order"),
                optionalBool(format, "remove-unused-imports"));
    }

    /**
     * {@code [install]} — what installing this module produces besides its jar and POM.
     *
     * <p>Absent for every ordinary target, which is the point: a library, an executable, a native
     * binary, a script and an external jar are complete shapes already and declare nothing. The one
     * keys are {@code product-lib} and {@code product-bin}, which are jk installing jk — see
     * {@link JkBuild.Install}.
     */
    static Optional<JkBuild.Install> parseInstall(TomlTable root) {
        if (root.contains("install") && !root.isTable("install")) {
            throw new JkBuildParseException("`install` must be a table — use [install] with a product-lib key");
        }
        TomlTable install = root.getTable("install");
        if (install == null) return Optional.empty();
        for (String key : install.keySet()) {
            if (!"product-lib".equals(key) && !"product-bin".equals(key)) {
                throw new JkBuildParseException(
                        "[install] unknown key `" + key + "` — expected product-lib or product-bin");
            }
        }
        String productLib = stringOrThrow(install, "product-lib", "install.product-lib");
        String productBin = stringOrThrow(install, "product-bin", "install.product-bin");
        // The destination is not configurable: EngineInstall hardcodes the jk-engine home, the
        // pointer name and the jar naming, so any other value would be freshness-checked and
        // installed under jk-engine/ while announcing a directory nothing wrote to. (A client-io
        // test pins this literal to EngineInstall.BIN_NAME.)
        if (productLib != null && !productLib.equals("jk-engine")) {
            throw new JkBuildParseException("[install] product-lib must be \"jk-engine\" — installing into jk's own"
                    + " product layout is jk installing jk, and the engine home is not configurable");
        }
        // The PATH client's name is not configurable either: EngineInstall writes `jk` (and its
        // `jkx` twin) under <home>/bin, and that is the one name LauncherName refuses to every
        // other install.
        if (productBin != null && !productBin.equals("jk")) {
            throw new JkBuildParseException("[install] product-bin must be \"jk\" — the PATH client under jk's own"
                    + " bin/ is the one binary an install may replace there");
        }
        if (productLib == null && productBin == null) return Optional.empty();
        return Optional.of(new JkBuild.Install(productLib, productBin));
    }

    static final List<String> DOKKA_KEYS = List.of("version", "format");

    /**
     * {@code [dokka]} — the Dokka release and output format a Kotlin or mixed module's javadoc jar
     * is built with; see {@link BuildBlock.Dokka}. Absent keys keep their defaults.
     */
    static Optional<BuildBlock.Dokka> parseDokka(TomlTable root) {
        if (root.contains("dokka") && !root.isTable("dokka")) {
            throw new JkBuildParseException("`dokka` must be a table — use [dokka] with version and format keys");
        }
        TomlTable table = root.getTable("dokka");
        if (table == null) return Optional.empty();
        rejectUnknownKeys(table, DOKKA_KEYS, "[dokka]");
        String version = stringOrThrow(table, "version", "dokka.version");
        VersionSelector selector = BuildBlock.Dokka.DEFAULT.version();
        if (version != null) {
            try {
                selector = VersionSelector.parse(version);
            } catch (IllegalArgumentException e) {
                throw new JkBuildParseException("[dokka] version: " + e.getMessage());
            }
        }
        String format = stringOrThrow(table, "format", "dokka.format");
        BuildBlock.Dokka.Format shape = BuildBlock.Dokka.DEFAULT.format();
        if (format != null) {
            try {
                shape = BuildBlock.Dokka.Format.parse(format);
            } catch (IllegalArgumentException e) {
                throw new JkBuildParseException("[dokka] format: " + e.getMessage());
            }
        }
        return Optional.of(new BuildBlock.Dokka(selector, shape));
    }

    static final List<String> BUILD_INFO_KEYS = List.of("file", "time");

    /**
     * {@code [build-info]} — the git build-info resources the jar carries; see {@link
     * BuildBlock.BuildInfo}. An empty table is the whole battery at its defaults.
     */
    static Optional<BuildBlock.BuildInfo> parseBuildInfo(TomlTable root) {
        if (root.contains(TaskNames.BUILD_INFO) && !root.isTable(TaskNames.BUILD_INFO)) {
            throw new JkBuildParseException(
                    "`build-info` must be a table — use [build-info], optionally with file and time keys");
        }
        TomlTable table = root.getTable(TaskNames.BUILD_INFO);
        if (table == null) return Optional.empty();
        rejectUnknownKeys(table, BUILD_INFO_KEYS, "[build-info]");
        String file = stringOrThrow(table, "file", "build-info.file");
        if (file != null && (file.isBlank() || file.startsWith("/") || file.contains(".."))) {
            throw new JkBuildParseException(
                    "[build-info] file must be a relative resource path inside the jar, such as git.properties");
        }
        String time = stringOrThrow(table, "time", "build-info.time");
        boolean buildTime =
                switch (time == null ? "commit" : time.trim().toLowerCase(Locale.ROOT)) {
                    case "commit" -> false;
                    case "build" -> true;
                    default ->
                        throw new JkBuildParseException(
                                "[build-info] time must be \"commit\" (the HEAD"
                                        + " commit's time; reproducible) or \"build\" (the wall clock; every build repackages)");
                };
        return Optional.of(
                new BuildBlock.BuildInfo(file == null ? BuildBlock.BuildInfo.DEFAULT_FILE : file, buildTime));
    }

    static final List<String> PUBLISH_KEYS = List.of("name", "url", "licenses", "developers", "scm");
    static final List<String> PUBLISH_LICENSE_KEYS = List.of("name", "url");
    static final List<String> PUBLISH_DEVELOPER_KEYS = List.of("id", "name", "email");
    static final List<String> PUBLISH_SCM_KEYS = List.of("url", "connection", "developer-connection");

    /**
     * {@code [publish]} — the POM metadata a release carries. Every key is optional here; what a
     * repository requires is the repository's rule, and {@code jk publish --central} names the
     * missing ones before it signs anything.
     */
    static Optional<PomMetadata> parsePublish(TomlTable root) {
        if (root.contains("publish") && !root.isTable("publish")) {
            throw new JkBuildParseException(
                    "`publish` must be a table — use [publish] with url, licenses, developers, scm");
        }
        TomlTable publish = root.getTable("publish");
        if (publish == null) return Optional.empty();
        rejectUnknownKeys(publish, PUBLISH_KEYS, "[publish]");
        List<PomMetadata.License> licenses = new ArrayList<>();
        for (TomlTable t : tables(publish, "licenses", "publish.licenses")) {
            rejectUnknownKeys(t, PUBLISH_LICENSE_KEYS, "[publish] licenses");
            String name = stringOrThrow(t, "name", "publish.licenses.name");
            if (name == null || name.isBlank()) {
                throw new JkBuildParseException(
                        "[publish] licenses: every license needs a name (an SPDX id such as Apache-2.0)");
            }
            licenses.add(new PomMetadata.License(name, stringOrThrow(t, "url", "publish.licenses.url")));
        }
        List<PomMetadata.Developer> developers = new ArrayList<>();
        for (TomlTable t : tables(publish, "developers", "publish.developers")) {
            rejectUnknownKeys(t, PUBLISH_DEVELOPER_KEYS, "[publish] developers");
            String id = stringOrThrow(t, "id", "publish.developers.id");
            if (id == null || id.isBlank()) {
                throw new JkBuildParseException("[publish] developers: every developer needs an id");
            }
            developers.add(new PomMetadata.Developer(
                    id,
                    stringOrThrow(t, "name", "publish.developers.name"),
                    stringOrThrow(t, "email", "publish.developers.email")));
        }
        PomMetadata.Scm scm = null;
        if (publish.contains("scm")) {
            if (!publish.isTable("scm")) {
                throw new JkBuildParseException(
                        "[publish] scm must be a table with url, connection, developer-connection");
            }
            TomlTable t = Objects.requireNonNull(publish.getTable("scm"));
            rejectUnknownKeys(t, PUBLISH_SCM_KEYS, "[publish] scm");
            scm = new PomMetadata.Scm(
                    stringOrThrow(t, "url", "publish.scm.url"),
                    stringOrThrow(t, "connection", "publish.scm.connection"),
                    stringOrThrow(t, "developer-connection", "publish.scm.developer-connection"));
        }
        return Optional.of(new PomMetadata(
                stringOrThrow(publish, "name", "publish.name"),
                null,
                stringOrThrow(publish, "url", "publish.url"),
                licenses,
                developers,
                scm));
    }

    private static List<TomlTable> tables(TomlTable parent, String key, String displayPath) {
        if (!parent.contains(key)) return List.of();
        if (!(parent.get(key) instanceof TomlArray arr)) {
            throw new JkBuildParseException("`" + displayPath + "` must be an array of tables");
        }
        List<TomlTable> out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            if (!(arr.get(i) instanceof TomlTable t)) {
                throw new JkBuildParseException("`" + displayPath + "` must be an array of tables");
            }
            out.add(t);
        }
        return out;
    }

    private static void rejectUnknownKeys(TomlTable table, List<String> known, String where) {
        for (String key : table.keySet()) {
            if (!known.contains(key)) {
                throw new JkBuildParseException(
                        where + " unknown key `" + key + "` — expected one of: " + String.join(", ", known));
            }
        }
    }

    /** Present boolean key → its value; absent → null (caller applies the default). */
    static @Nullable Boolean optionalBool(TomlTable table, String key) {
        return table.contains(key) ? table.getBoolean(key) : null;
    }

    /** Read an optional string key; present-but-non-string is a parse error; absent → null. */
    static @Nullable String stringOrThrow(TomlTable table, String key, String path) {
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
    static Map<String, String> parseManifest(TomlParseResult root) {
        TomlTable table = root.getTable("manifest");
        if (table == null) return Map.of();
        Map<String, String> attrs = new LinkedHashMap<>();
        for (String key : table.keySet()) {
            String value = table.getString(List.of(key));
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
     * {@code [repositories]} — strict: a manifest that lies about a repository is a build error.
     * {@code ${VAR}} is left unexpanded on purpose, so a parsed manifest never carries a secret;
     * the credential resolver expands it. One reader with {@link RepositoryToml.OnBad#REJECT};
     * {@link GlobalConfig#repositories()} is the same reader with the other policy.
     */
    static List<RepositorySpec> parseRepositories(TomlTable root) {
        return RepositoryToml.repositories(
                root.getTable("repositories"), RepositoryToml.VarPolicy.DEFER, RepositoryToml.OnBad.REJECT);
    }

    /**
     * Optional object-store config on a {@code [repositories.<name>]} table for {@code s3://}/{@code
     * gs://} backends: {@code region}, {@code endpoint}, {@code access-key}, {@code secret-key},
     * {@code session-token}. All support {@code ${ENV}} interpolation (so keys aren't committed
     * literally); any unset field falls back to the AWS environment / default chain.
     */
    static Profiles parseProfiles(TomlTable root) {
        TomlTable profiles = root.getTable("profiles");
        if (profiles == null) return Profiles.empty();
        Map<String, Profile> byName = new LinkedHashMap<>();
        for (String name : profiles.keySet()) {
            TomlTable body = profiles.getTable(List.of(name));
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
     * CLI overrides apply. Empty lists when the table/key is absent. Reached through
     * {@link JkBuildParser#parseTestTags(Path)}, which owns the read.
     */
    static JkBuildParser.TestTomlTags parseTestTags(TomlTable root) {
        TomlTable test = root.getTable("test");
        if (test == null) return JkBuildParser.TestTomlTags.EMPTY;
        return new JkBuildParser.TestTomlTags(
                optionalStringList(test, "include-tags", "test.include-tags"),
                optionalStringList(test, "exclude-tags", "test.exclude-tags"));
    }

    static Features parseFeatures(TomlTable root) {
        TomlTable features = root.getTable("features");
        if (features == null) return Features.empty();
        List<String> defaults = optionalStringList(features, "default", "features.default");
        Map<String, Feature> byName = new LinkedHashMap<>();
        for (String key : features.keySet()) {
            if (key.equals("default")) continue;
            TomlTable body = features.getTable(List.of(key));
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

    static @Nullable Workspace parseWorkspace(TomlTable root, LibraryCatalog catalog) {
        TomlTable workspace = root.getTable("workspace");
        if (workspace == null) return null;
        List<String> modules = optionalStringList(workspace, "modules", "workspace.modules");
        Map<String, WorkspaceDependency> wsDeps = parseWorkspaceDependencies(workspace, catalog);
        return new Workspace(modules, wsDeps);
    }

    static Map<String, WorkspaceDependency> parseWorkspaceDependencies(TomlTable workspace, LibraryCatalog catalog) {
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
     * "latest"}, …) or a {@code group:artifact:selector} coordinate. A bare version resolves the
     * short {@code name} to a {@code group:artifact} through the catalog, matching the {@code
     * [dependencies]} shorthand. Local-path and git-URL string forms are not accepted here: a shared
     * workspace dep must be a Maven coordinate (use the inline {@code git = "..."} table form for
     * git, or a {@code [workspace] modules} entry for a sibling). A versionless {@code group:artifact}
     * is refused too, because a shared entry exists to carry the version members pin against.
     */
    static WorkspaceDependency parseWorkspaceShorthand(
            String name, String value, String displayPath, LibraryCatalog catalog) {
        if (value.isBlank()) {
            throw new JkBuildParseException(displayPath + " has an empty value string");
        }
        if (ManifestDeps.isPathShorthand(value) || ManifestDeps.isGitUrlShorthand(value)) {
            throw new JkBuildParseException(displayPath + " string shorthand must be a version spec"
                    + " (e.g. \"1.2.3\") or a `group:artifact:version` coordinate; for a git source use the"
                    + " inline `{ git = \"...\" }` form, for a local sibling add it to `[workspace] modules`");
        }
        if (value.indexOf(':') >= 0) {
            Dependency gav = ManifestDeps.parseGavShorthand(name, value, displayPath);
            if (gav.isPlatformManaged()) {
                throw new JkBuildParseException(displayPath
                        + " — `"
                        + value
                        + "` has no version; a shared workspace dependency carries the version members"
                        + " pin against (`group:artifact:1.2.3`)");
            }
            return new WorkspaceDependency(gav.group(), gav.name(), gav.version(), null);
        }
        LibraryCatalog.Module mod = catalog.lookup(name)
                .orElseThrow(() ->
                        new JkBuildParseException(ManifestDeps.unknownLibraryMessage(displayPath, name, catalog)));
        return new WorkspaceDependency(mod.group(), mod.artifact(), VersionSelector.parse(value), null);
    }

    static WorkspaceDependency parseWorkspaceDepEntry(String name, TomlTable entry, LibraryCatalog catalog) {
        String displayPath = "workspace.dependencies." + name;
        DependencyEntryKeys.requireKnown(entry, displayPath, DependencyEntryKeys.WORKSPACE);
        boolean hasVersion = entry.contains("version");
        boolean hasGit = entry.contains("git");
        if (entry.contains("path")) {
            throw new JkBuildParseException(displayPath + " uses `path = \"...\"`; a shared workspace dependency"
                    + " is a Maven coordinate or a git source. A local sibling belongs in the root jk.toml's"
                    + " `[workspace] modules = [...]` list, where it is an ordinary workspace member; remove this"
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
            if (entry.contains(DependencyExclusions.KEY)) {
                throw new JkBuildParseException(displayPath + "." + DependencyExclusions.KEY
                        + " applies to a Maven coordinate (a git source has no POM subtree to prune)");
            }
            GitSource source = ManifestDeps.parseGitSource(entry, displayPath);
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
        return new WorkspaceDependency(
                group,
                artifact,
                VersionSelector.parse(versionRaw),
                null,
                DependencyExclusions.parseList(entry, displayPath));
    }

    /**
     * The optional {@code [application]} table. Presence marks the project as an application
     * ({@link JkBuild#isApplication}). {@code main} is required. {@code Optional.empty} when the
     * table is absent.
     */
    static Optional<JkBuild.Application> parseApplication(TomlTable root) {
        rejectFlattenedApplicationKeys(root);
        TomlTable application = root.getTable("application");
        if (application == null) return Optional.empty();
        // Same stance as the [m2]/[install] guards: a typo inside the right table must not parse
        // clean and do nothing — `asembly = true` silently building a thin jar is the identical
        // symptom the misplacement guard below exists for.
        for (String key : application.keySet()) {
            if (!APPLICATION_KEYS.contains(key)) {
                throw new JkBuildParseException("[application] unknown key `" + key + "` — expected one of: "
                        + String.join(", ", APPLICATION_KEYS));
            }
        }
        String main = application.getString("main");
        boolean assembly = artifactFlag(application, "assembly");
        boolean minified = artifactFlag(application, "minified");
        boolean nativeImage = artifactFlag(application, "native");
        String config = application.getString("config");
        if (main == null || main.isBlank()) {
            throw new JkBuildParseException("[application].main is required");
        }
        return Optional.of(new JkBuild.Application(main, assembly, minified, nativeImage, config));
    }

    /**
     * Every key that belongs to {@code [application]}. Written down so a misplacement is caught as
     * a class rather than one key at a time.
     */
    static final List<String> APPLICATION_KEYS = List.of("main", "assembly", "minified", "native", "config");

    /**
     * Reject an {@code [application]} key written at the top level.
     *
     * <p>Silence here is expensive and invisible. A top-level {@code assembly = true} that parsed
     * clean and did nothing would build a thin jar, and {@code jk install}'s artifact ladder —
     * native, then minified, then fat, then thin — would honestly install a thin-jar launcher
     * because no fat jar existed. Nothing in that chain is wrong except the key nobody read.
     * {@code minified} would stop the build, but by accident, reporting a type problem for what
     * is a wrong-table problem.
     *
     * <p>Scalars only. {@code [native]}, {@code [config]} and plugin tables like {@code [assembly]}
     * are legitimate top-level <em>tables</em> with their own meanings; it is the bare
     * {@code key = value} form that can only be a misplacement. Same shape as the {@code [m2]}
     * guard, which rejects the flattened {@code m2integration} / {@code m2install} by name.
     */
    private static void rejectFlattenedApplicationKeys(TomlTable root) {
        for (String key : APPLICATION_KEYS) {
            if (!root.contains(key) || root.isTable(key)) continue;
            // `native` and `config` are also real top-level TABLES with their own semantics
            // ([application] native = true declares the artifact; [native] enabled = true tunes
            // the build) — a bare scalar could be aiming at either, so the error names both.
            String alsoATable = key.equals("native") || key.equals("config")
                    ? ", or as the `[" + key + "]` table if its settings were the intent"
                    : "";
            throw new JkBuildParseException("`" + key + "` belongs in [application] — write it as"
                    + " `[application]` with `" + key + " = …`" + alsoATable + ", not at the top level");
        }
    }

    /**
     * One additive artifact switch. Artifacts stack — a thin jar always, {@code assembly} adds the
     * fat jar, {@code minified} adds the R8 jar and implies the fat one — so each key is a plain
     * boolean rather than a mode.
     */
    static boolean artifactFlag(TomlTable application, String key) {
        if (!application.contains(key)) return false;
        if (application.isBoolean(key)) return Boolean.TRUE.equals(application.getBoolean(key));
        String raw = application.isString(key) ? application.getString(key) : null;
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        // "none" is this key's own extra spelling for off; everything else is the jk-wide truth set.
        if (value.equals("none")) return false;
        Optional<Boolean> flag = EnvValues.parseBool(value);
        if (flag.isPresent()) return flag.get();
        if (value.equals("shrink") || value.equals("shrunk") || value.equals("r8")) {
            throw new JkBuildParseException("[application]." + key + " is a boolean, not \"" + raw
                    + "\" — artifacts are additive: set `minified = true` for an R8 jar (it builds"
                    + " the fat jar too)");
        }
        throw new JkBuildParseException(
                "[application]." + key + " must be true or false (got \"" + (raw == null ? "" : raw) + "\")");
    }

    /** Schema-validate each installed plugin's owned table into a {@link PluginConfig}. */
    static Map<String, PluginConfig> parsePluginTables(TomlTable root, List<PluginDescriptor> installed) {
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
    static Map<String, PluginConfig> ensureMinifiedPluginConfigured(
            JkBuild.@Nullable Application application,
            Map<String, PluginConfig> pluginConfigs,
            List<PluginDescriptor> installed) {
        boolean minified = application != null && application.minified();
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
    public static JkBuild withArtifactOverride(JkBuild build, JkBuildParser.ArtifactOverride override) {
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

    static Map<String, PluginConfig> ensureMinifiedPluginConfig(
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
    public static JkBuildParser.@Nullable ArtifactOverride parseArtifactOverride(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "fat", "true", "assembly" -> new JkBuildParser.ArtifactOverride(true, false);
            case "minified", "min" -> new JkBuildParser.ArtifactOverride(true, true);
            case "off", "false", "none", "thin" -> new JkBuildParser.ArtifactOverride(false, false);
            default -> throw new IllegalArgumentException("unknown artifact override: " + raw + " (want fat|minified)");
        };
    }
}
