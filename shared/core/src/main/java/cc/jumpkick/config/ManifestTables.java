// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static cc.jumpkick.config.JkBuildParser.*;
import static cc.jumpkick.config.TomlTableReader.*;

import cc.jumpkick.host.ReleasePlatform;
import cc.jumpkick.library.LibraryCatalog;
import cc.jumpkick.model.BuildBlock;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.Feature;
import cc.jumpkick.model.Features;
import cc.jumpkick.model.GitSource;
import cc.jumpkick.model.InstallDestination;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PluginConfig;
import cc.jumpkick.model.PomMetadata;
import cc.jumpkick.model.Profile;
import cc.jumpkick.model.Profiles;
import cc.jumpkick.model.ReleaseSources;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.Variants;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.model.Workspace;
import cc.jumpkick.model.Workspace.WorkspaceDependency;
import cc.jumpkick.plugin.manifest.PluginDescriptor;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import cc.jumpkick.release.ReleaseTable;
import cc.jumpkick.release.ReleaseTag;
import cc.jumpkick.release.ReleaseTarget;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.util.Suggest;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlPosition;
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
        TomlTable table = optionalTable(root, "variants", "`variants`");
        if (table == null) return Variants.EMPTY;
        Map<String, PluginDescriptor> byTable = new LinkedHashMap<>();
        for (PluginDescriptor m : installed) byTable.put(m.table(), m);
        List<Variants.Dimension> dimensions = new ArrayList<>();
        for (String dim : table.keySet()) {
            TomlTable dimTable = tableAt(table, dim, "[variants." + dim + "] must be a table of named values");
            String defaultValue = null;
            Map<String, Variants.Value> values = new LinkedHashMap<>();
            for (String key : dimTable.keySet()) {
                if (key.equals("default")) {
                    defaultValue = optionalString(dimTable, "default", "[variants." + dim + "].default");
                    if (defaultValue == null || defaultValue.isBlank()) {
                        throw JkBuildParseException.at(
                                dimTable, "default", "[variants." + dim + "].default must be a value name");
                    }
                    continue;
                }
                TomlTable valueTable = tableAt(
                        dimTable,
                        key,
                        "[variants." + dim + "]." + key + " must be a table, or use `default = \"<value>\"`");
                values.put(
                        key, parseVariantValue("variants." + dim + "." + key, valueTable, workspace, catalog, byTable));
            }
            try {
                dimensions.add(new Variants.Dimension(dim, defaultValue, values));
            } catch (IllegalArgumentException e) {
                // The one value a dimension's own check refuses is its default.
                throw JkBuildParseException.at(dimTable, "default", Objects.toString(e.getMessage()));
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
                TomlTable scopeTable =
                        tableAt(valueTable, key, "[" + where + "." + key + "] must be a dependency table");
                List<Dependency> parsed = ManifestDeps.parseScopeTable(
                        scopeTable, new ArrayList<>(scopeTable.keySet()), scope, workspace, catalog);
                if (!parsed.isEmpty()) deps.put(scope, parsed);
                continue;
            }
            PluginDescriptor plugin = pluginsByTable.get(key);
            if (plugin != null) {
                TomlTable overlay = tableAt(
                        valueTable,
                        key,
                        "[" + where + "." + key + "] must be a table of [" + plugin.table() + "] key overlays");
                pluginOverlays.put(
                        plugin.table(), PluginTableRegistry.validateOverlay(plugin, where + "." + key, overlay));
                continue;
            }
            List<String> settable = new ArrayList<>();
            settable.add("extra-src");
            for (Scope s : Scope.values()) settable.add(s.tomlSection());
            settable.addAll(pluginsByTable.keySet());
            String hint = Suggest.closest(key, settable)
                    .map(name -> " did you mean " + name + "?")
                    .orElse(" use extra-src, a dependency table or one of " + pluginsByTable.keySet());
            throw JkBuildParseException.at(
                    valueTable, key, "[" + where + "]." + key + ": a variant cannot set this;" + hint);
        }
        return new Variants.Value(extraSrc, deps, pluginOverlays);
    }

    /** The table at {@code key}, which the caller read from {@code parent}'s keys; refused at the key otherwise. */
    private static TomlTable tableAt(TomlTable parent, String key, String refusal) {
        if (parent.get(List.of(key)) instanceof TomlTable t) return t;
        throw JkBuildParseException.at(parent, key, refusal);
    }

    static final List<String> FORMAT_KEYS = List.of(
            "style",
            "java",
            "kotlin",
            "optimize-imports",
            "import-order",
            "remove-unused-imports",
            "enforce",
            "ratchet-from",
            "exclude");

    static JkBuild.FormatConfig parseFormat(TomlTable root) {
        if (root.contains("format") && !root.isTable("format")) {
            throw new JkBuildParseException("`format` must be a table; use [format]");
        }
        TomlTable format = root.getTable("format");
        if (format == null) return JkBuild.FormatConfig.EMPTY;
        rejectUnknownKeys(format, FORMAT_KEYS, "[format]");
        return new JkBuild.FormatConfig(
                optionalString(format, "style", "format.style"),
                optionalString(format, "java", "format.java"),
                optionalString(format, "kotlin", "format.kotlin"),
                optionalBool(format, "optimize-imports", "format.optimize-imports"),
                optionalBool(format, "import-order", "format.import-order"),
                optionalBool(format, "remove-unused-imports", "format.remove-unused-imports"),
                Boolean.TRUE.equals(optionalBool(format, "enforce", "format.enforce")),
                optionalString(format, "ratchet-from", "format.ratchet-from"),
                format.contains("exclude") ? requireStringList(format, "exclude", "[format].exclude") : List.of());
    }

    /**
     * {@code [install]} — what installing this module produces besides its jar and POM.
     *
     * <p>Absent for every ordinary target, which is the point: a library, an executable, a native
     * binary, a script and an external jar are complete shapes already and declare nothing. {@code
     * destination} is read into the project (see {@link ManifestProject}); the keys here are {@code
     * product-lib} and {@code product-bin}, which are jk installing jk — see {@link JkBuild.Install}.
     */
    static Optional<JkBuild.Install> parseInstall(TomlTable root) {
        if (root.contains("install") && !root.isTable("install")) {
            throw new JkBuildParseException(
                    "`install` must be a table; use [install] with a " + InstallDestination.KEY + " key");
        }
        TomlTable install = root.getTable("install");
        if (install == null) return Optional.empty();
        for (String key : install.keySet()) {
            if (!InstallDestination.KEY.equals(key) && !"product-lib".equals(key) && !"product-bin".equals(key)) {
                throw new JkBuildParseException("[install] unknown key `" + key + "` — expected "
                        + InstallDestination.KEY + ", product-lib or product-bin");
            }
        }
        String productLib = optionalString(install, "product-lib", "install.product-lib");
        String productBin = optionalString(install, "product-bin", "install.product-bin");
        // The destination is not configurable: EngineInstall hardcodes the jk-engine home, the
        // pointer name and the jar naming, so any other value would be freshness-checked and
        // installed under jk-engine/ while announcing a directory nothing wrote to. (A client-io
        // test pins this literal to EngineInstall.BIN_NAME.)
        if (productLib != null && !productLib.equals("jk-engine")) {
            throw new JkBuildParseException("[install] product-lib must be \"jk-engine\"");
        }
        // The PATH client's name is not configurable either: EngineInstall writes `jk` (and its
        // `jkx` twin) under <home>/bin, and that is the one name LauncherName refuses to every
        // other install.
        if (productBin != null && !productBin.equals("jk")) {
            throw new JkBuildParseException("[install] product-bin must be \"jk\"");
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
            throw new JkBuildParseException("`dokka` must be a table; use [dokka] with version and format");
        }
        TomlTable table = root.getTable("dokka");
        if (table == null) return Optional.empty();
        rejectUnknownKeys(table, DOKKA_KEYS, "[dokka]");
        String version = optionalString(table, "version", "dokka.version");
        VersionSelector selector = BuildBlock.Dokka.DEFAULT.version();
        if (version != null) {
            try {
                selector = VersionSelector.parse(version);
            } catch (IllegalArgumentException e) {
                throw new JkBuildParseException("[dokka] version: " + e.getMessage());
            }
            selector = ManifestSelectors.checked(selector, table, "version", "[dokka] version");
        }
        String format = optionalString(table, "format", "dokka.format");
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

    /** The top-level table naming the extra release source sets of a multi-release jar. */
    static final String MULTI_RELEASE = "multi-release";

    /**
     * {@code [multi-release]} — each key a Java release, each value the source root (or roots) compiled
     * at that release into {@code META-INF/versions/<N>/}; see {@link ReleaseSources}.
     */
    static List<ReleaseSources> parseMultiRelease(TomlTable root) {
        if (root.contains(MULTI_RELEASE) && !root.isTable(MULTI_RELEASE)) {
            throw new JkBuildParseException(
                    "`multi-release` must be a table: [multi-release] 21 = \"src/main/java21\"");
        }
        TomlTable table = root.getTable(MULTI_RELEASE);
        if (table == null) return List.of();
        List<ReleaseSources> out = new ArrayList<>();
        for (String key : table.keySet()) {
            int release;
            try {
                release = Integer.parseInt(key);
            } catch (NumberFormatException e) {
                throw new JkBuildParseException("[multi-release] key `" + key
                        + "` is not a Java release: key each source set by its release, 21 = \"src/main/java21\"");
            }
            if (release < ReleaseSources.FLOOR) {
                throw new JkBuildParseException(
                        "[multi-release] " + key + ": release must be " + ReleaseSources.FLOOR + " or later");
            }
            Object raw = table.get(List.of(key));
            List<String> src = new ArrayList<>();
            String where = "[multi-release] " + key;
            if (raw instanceof String s) {
                src.add(s);
            } else if (raw instanceof TomlArray arr) {
                for (int i = 0; i < arr.size(); i++) {
                    if (!(arr.get(i) instanceof String s)) {
                        throw new JkBuildParseException(where + " must be a source root or an array of them");
                    }
                    src.add(s);
                }
            } else {
                throw new JkBuildParseException(where + " must be a source root or an array of them");
            }
            for (String s : src) {
                if (s.isBlank()) throw new JkBuildParseException(where + " names an empty source root");
            }
            if (src.isEmpty()) throw new JkBuildParseException(where + " names no source root");
            out.add(new ReleaseSources(release, src));
        }
        return out;
    }

    static final List<String> BUILD_INFO_KEYS = List.of("file", "time");

    /**
     * {@code [build-info]} — the git build-info resources the jar carries; see {@link
     * BuildBlock.BuildInfo}. An empty table is the whole battery at its defaults.
     */
    static Optional<BuildBlock.BuildInfo> parseBuildInfo(TomlTable root) {
        if (root.contains(TaskNames.BUILD_INFO) && !root.isTable(TaskNames.BUILD_INFO)) {
            throw new JkBuildParseException(
                    "`build-info` must be a table; use [build-info] with optional file and time");
        }
        TomlTable table = root.getTable(TaskNames.BUILD_INFO);
        if (table == null) return Optional.empty();
        rejectUnknownKeys(table, BUILD_INFO_KEYS, "[build-info]");
        String file = optionalString(table, "file", "build-info.file");
        if (file != null && (file.isBlank() || file.startsWith("/") || file.contains(".."))) {
            throw new JkBuildParseException(
                    "[build-info] file must be a relative path in the jar, e.g. git.properties");
        }
        String time = optionalString(table, "time", "build-info.time");
        boolean buildTime =
                switch (time == null ? "commit" : time.trim().toLowerCase(Locale.ROOT)) {
                    case "commit" -> false;
                    case "build" -> true;
                    default -> throw new JkBuildParseException("[build-info] time must be \"commit\" or \"build\"");
                };
        return Optional.of(
                new BuildBlock.BuildInfo(file == null ? BuildBlock.BuildInfo.DEFAULT_FILE : file, buildTime));
    }

    /** The {@code [war]} table. */
    static final String WAR = "war";

    static final List<String> WAR_KEYS = List.of("name", "webapp");

    /** {@code [war]}: present (even empty) means the module packages a war. */
    static Optional<BuildBlock.War> parseWar(TomlTable root) {
        if (root.contains(WAR) && !root.isTable(WAR)) {
            throw new JkBuildParseException("`war` must be a table; use [war] with optional name and webapp");
        }
        TomlTable table = root.getTable(WAR);
        if (table == null) return Optional.empty();
        rejectUnknownKeys(table, WAR_KEYS, "[war]");
        String name = optionalString(table, "name", "war.name");
        if (name != null && (name.contains("/") || name.contains("\\") || name.endsWith(".war"))) {
            throw new JkBuildParseException("[war] name is the file name without .war, such as \"ROOT\"");
        }
        String webapp = optionalString(table, "webapp", "war.webapp");
        if (webapp != null && (webapp.startsWith("/") || webapp.contains(".."))) {
            throw new JkBuildParseException("[war] webapp must be a directory inside the module, e.g. src/main/webapp");
        }
        return Optional.of(new BuildBlock.War(name, webapp == null ? BuildBlock.War.DEFAULT_WEBAPP : webapp));
    }

    static final List<String> PUBLISH_KEYS = List.of(
            "name",
            "url",
            "licenses",
            "developers",
            "scm",
            "repository",
            "release",
            "sign",
            "key-file",
            "skip",
            "sbom",
            "provenance",
            "hold");

    /** Keys that would hold a secret: refused with where a secret does belong. */
    static final List<String> PUBLISH_SECRET_KEYS =
            List.of("password", "token", "passphrase", "key-passphrase", "user", "username");

    static final List<String> RELEASE_KEYS = List.of("tag", "files", "publish", "platforms");
    static final List<String> RELEASE_FILE_KEYS = List.of("path", "pattern");

    /**
     * {@code [release]} — the tag template a release carries ({@code {version}} standing for the
     * released version; {@link ReleaseTag#DEFAULT_TEMPLATE} when absent) and the files that carry
     * the declared version besides {@code jk.toml}: {@code files = [{ path, pattern }]}, the
     * pattern's one group being the version. {@code publish} fixes who publishes a release.
     */
    static ReleaseTable parseRelease(TomlTable root) {
        if (root.contains("release") && !root.isTable("release")) {
            throw new JkBuildParseException("`release` must be a table; use [release] with tag");
        }
        TomlTable release = root.getTable("release");
        if (release == null) return ReleaseTable.DEFAULT;
        rejectUnknownKeys(release, RELEASE_KEYS, "[release]");
        String tag = optionalString(release, "tag", "release.tag");
        if (tag == null) {
            tag = ReleaseTag.DEFAULT_TEMPLATE;
        } else if (!tag.contains("{version}")) {
            throw new JkBuildParseException("[release] tag must contain {version}, such as \"v{version}\"");
        }
        Map<String, String> files = new LinkedHashMap<>();
        for (TomlTable t : tables(release, "files", "release.files")) {
            rejectUnknownKeys(t, RELEASE_FILE_KEYS, "[release] files");
            String path = optionalString(t, "path", "release.files.path");
            String pattern = optionalString(t, "pattern", "release.files.pattern");
            if (path == null || path.isBlank() || pattern == null) {
                throw new JkBuildParseException("[release] files: every entry needs a path and a pattern");
            }
            try {
                if (Pattern.compile(pattern).matcher("").groupCount() != 1) {
                    throw new JkBuildParseException(
                            "[release] files: the pattern for " + path + " must have exactly one group, the version");
                }
            } catch (PatternSyntaxException e) {
                throw new JkBuildParseException("[release] files: the pattern for " + path + " is not a regular"
                        + " expression: " + e.getDescription());
            }
            files.put(path, pattern);
        }
        String publish = optionalString(release, "publish", "release.publish");
        if (publish != null && !ReleaseTable.PUBLISH_MODES.contains(publish)) {
            throw new JkBuildParseException(
                    "[release] publish must be \"ci\", \"local\" or \"none\", got `" + publish + "`");
        }
        List<String> platforms = platforms(release);
        return new ReleaseTable(tag, files, publish == null ? "" : publish, platforms);
    }

    /** {@code [release] platforms}: each a {@link ReleasePlatform#KNOWN} name, once. */
    private static List<String> platforms(TomlTable release) {
        if (!release.contains("platforms")) return List.of();
        if (!(release.get("platforms") instanceof TomlArray arr) || arr.isEmpty()) {
            throw new JkBuildParseException("[release] platforms must list platforms, e.g. [\"linux-x86_64\"]");
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            if (!(arr.get(i) instanceof String name) || !ReleasePlatform.KNOWN.contains(name)) {
                throw new JkBuildParseException("[release] platforms: `" + arr.get(i) + "` is not one of "
                        + String.join(", ", ReleasePlatform.KNOWN));
            }
            if (out.contains(name)) throw new JkBuildParseException("[release] platforms: " + name + " twice");
            out.add(name);
        }
        return List.copyOf(out);
    }

    static final List<String> PUBLISH_LICENSE_KEYS = List.of("name", "url");
    static final List<String> PUBLISH_DEVELOPER_KEYS = List.of("id", "name", "email");
    static final List<String> PUBLISH_SCM_KEYS = List.of("url", "connection", "developer-connection");

    /**
     * {@code [publish]} — the POM metadata a release carries. Every key is optional here; what a
     * repository requires is the repository's rule, and a publish to Central names the
     * missing ones before it signs anything.
     */
    static Optional<PomMetadata> parsePublish(TomlTable root) {
        if (root.contains("publish") && !root.isTable("publish")) {
            throw new JkBuildParseException(
                    "`publish` must be a table; use [publish] with url, licenses, developers, scm");
        }
        TomlTable publish = root.getTable("publish");
        if (publish == null) return Optional.empty();
        for (String secret : PUBLISH_SECRET_KEYS) {
            if (publish.contains(secret)) {
                throw new JkBuildParseException(
                        secret.contains("passphrase")
                                ? "[publish] " + secret + ": no secrets in jk.toml; set JK_GPG_PASSPHRASE"
                                : "[publish] " + secret
                                        + ": no secrets in jk.toml; use jk repo login or JK_REPO_<ID>_*");
            }
        }
        rejectUnknownKeys(publish, PUBLISH_KEYS, "[publish]");
        boolean sign = !Boolean.FALSE.equals(optionalBool(publish, "sign", "[publish] sign"));
        boolean sbom = !Boolean.FALSE.equals(optionalBool(publish, "sbom", "[publish] sbom"));
        boolean provenance = !Boolean.FALSE.equals(optionalBool(publish, "provenance", "[publish] provenance"));
        boolean hold = Boolean.TRUE.equals(optionalBool(publish, "hold", "[publish] hold"));
        boolean skip = Boolean.TRUE.equals(optionalBool(publish, "skip", "[publish] skip"));
        String release = optionalString(publish, "release", "publish.release");
        String releaseRefusal = release == null ? null : ReleaseTarget.refusal(release);
        if (releaseRefusal != null) throw new JkBuildParseException("[publish] release: " + releaseRefusal);
        List<PomMetadata.License> licenses = new ArrayList<>();
        for (TomlTable t : tables(publish, "licenses", "publish.licenses")) {
            rejectUnknownKeys(t, PUBLISH_LICENSE_KEYS, "[publish] licenses");
            String name = optionalString(t, "name", "publish.licenses.name");
            if (name == null || name.isBlank()) {
                throw new JkBuildParseException("[publish] licenses: each license needs a name, e.g. Apache-2.0");
            }
            licenses.add(new PomMetadata.License(name, optionalString(t, "url", "publish.licenses.url")));
        }
        List<PomMetadata.Developer> developers = new ArrayList<>();
        for (TomlTable t : tables(publish, "developers", "publish.developers")) {
            rejectUnknownKeys(t, PUBLISH_DEVELOPER_KEYS, "[publish] developers");
            String id = optionalString(t, "id", "publish.developers.id");
            if (id == null || id.isBlank()) {
                throw new JkBuildParseException("[publish] developers: every developer needs an id");
            }
            developers.add(new PomMetadata.Developer(
                    id,
                    optionalString(t, "name", "publish.developers.name"),
                    optionalString(t, "email", "publish.developers.email")));
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
                    optionalString(t, "url", "publish.scm.url"),
                    optionalString(t, "connection", "publish.scm.connection"),
                    optionalString(t, "developer-connection", "publish.scm.developer-connection"));
        }
        return Optional.of(new PomMetadata(
                optionalString(publish, "name", "publish.name"),
                null,
                optionalString(publish, "url", "publish.url"),
                licenses,
                developers,
                scm,
                optionalString(publish, "repository", "publish.repository"),
                release,
                sign,
                optionalString(publish, "key-file", "publish.key-file"),
                skip,
                sbom,
                provenance,
                hold));
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

    /** A {@code [config]} table's keys are {@link JkConfigLoader#KEYS}. */
    static void checkConfig(TomlTable root) {
        if (root.contains("config") && !root.isTable("config")) {
            throw new JkBuildParseException("`config` must be a table — use [config]");
        }
        TomlTable table = root.getTable("config");
        if (table != null) rejectUnknownKeys(table, JkConfigLoader.KEYS, "[config]");
    }

    /**
     * Parse the optional top-level {@code [manifest]} table — string-valued custom jar-manifest
     * attributes (e.g. {@code "Implementation-Title"}). {@code Main-Class} is intentionally
     * <em>not</em> read here; it derives from {@code main}.
     */
    static Map<String, String> parseManifest(TomlParseResult root) {
        TomlTable table = optionalTable(root, "manifest", "`manifest`");
        if (table == null) return Map.of();
        Map<String, String> attrs = new LinkedHashMap<>();
        for (String key : table.keySet()) {
            String value = Objects.requireNonNull(optionalString(table, key, "manifest." + key));
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
                optionalTable(root, "repositories", "`repositories`"),
                RepositoryToml.VarPolicy.DEFER,
                RepositoryToml.OnBad.REJECT);
    }

    /**
     * Optional object-store config on a {@code [repositories.<name>]} table for {@code s3://}/{@code
     * gs://} backends: {@code region}, {@code endpoint}, {@code access-key}, {@code secret-key},
     * {@code session-token}. All support {@code ${ENV}} interpolation (so keys aren't committed
     * literally); any unset field falls back to the AWS environment / default chain.
     */
    static Profiles parseProfiles(TomlTable root) {
        TomlTable profiles = optionalTable(root, "profiles", "`profiles`");
        if (profiles == null) return Profiles.empty();
        Map<String, Profile> byName = new LinkedHashMap<>();
        for (String name : profiles.keySet()) {
            TomlTable body = profiles.getTable(List.of(name));
            if (body == null) {
                throw new JkBuildParseException("profiles." + name + " must be a table");
            }
            String inherits = optionalString(body, "inherits", "profiles." + name + ".inherits");
            List<String> javacArgs = optionalStringList(body, "javac", "profiles." + name + ".javac");
            List<String> jvmArgs = jvmArgsList(body, "profiles." + name);
            Map<String, String> env = new LinkedHashMap<>();
            if (body.contains("env")) {
                if (!body.isTable("env")) {
                    throw new JkBuildParseException(
                            "profiles." + name + ".env must be a table: env = { NAME = \"value\" }");
                }
                TomlTable envTable = Objects.requireNonNull(body.getTable("env"));
                for (String key : envTable.keySet()) {
                    Object v = envTable.get(List.of(key));
                    if (!(v instanceof String) && !(v instanceof Boolean) && !(v instanceof Long)) {
                        throw new JkBuildParseException("profiles." + name + ".env." + key + " must be a string");
                    }
                    env.put(key, String.valueOf(v));
                }
            }
            byName.put(name, new Profile(name, inherits, javacArgs, jvmArgs, env));
        }
        return new Profiles(byName);
    }

    static Features parseFeatures(TomlTable root) {
        TomlTable features = optionalTable(root, "features", "`features`");
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

    /**
     * Every name a {@code [features]} {@code deps} list holds is a dependency handle this manifest
     * declares, workspace edges included. The lock leaves out a name it cannot find, because the
     * workspace merge drops sibling edges before the solve, so a typo is caught here or nowhere.
     */
    static void checkFeatureDeps(TomlParseResult root, Features features, JkBuild.Dependencies deps) {
        if (features.isEmpty()) return;
        Set<String> declared = new HashSet<>();
        for (List<Dependency> scoped : deps.byScope().values()) for (Dependency d : scoped) declared.add(d.library());
        for (Feature feature : features.byName().values()) {
            for (String name : feature.deps()) {
                if (declared.contains(name)) continue;
                TomlPosition at = root.inputPositionOf(List.of("features", feature.name(), "deps"));
                throw new JkBuildParseException((at == null ? "" : "line " + at.line() + ": ") + "features."
                        + feature.name() + ".deps: no dependency named `" + name + "`; declare it with"
                        + " `optional = true`");
            }
        }
    }

    static final List<String> WORKSPACE_KEYS = List.of("modules", "dependencies");

    static @Nullable Workspace parseWorkspace(TomlTable root, LibraryCatalog catalog) {
        TomlTable workspace = optionalTable(root, "workspace", "`workspace`");
        if (workspace == null) return null;
        rejectUnknownKeys(workspace, WORKSPACE_KEYS, "[workspace]");
        List<String> modules = optionalStringList(workspace, "modules", "workspace.modules");
        Map<String, WorkspaceDependency> wsDeps = parseWorkspaceDependencies(workspace, catalog);
        return new Workspace(modules, wsDeps);
    }

    static Map<String, WorkspaceDependency> parseWorkspaceDependencies(TomlTable workspace, LibraryCatalog catalog) {
        TomlTable wsDeps = optionalTable(workspace, "dependencies", "workspace.dependencies");
        if (wsDeps == null) return Map.of();
        Map<String, WorkspaceDependency> out = new LinkedHashMap<>();
        for (String name : wsDeps.keySet()) {
            Object value = wsDeps.get(List.of(name));
            String displayPath = "workspace.dependencies." + name;
            if (value instanceof String shorthand) {
                out.put(name, parseWorkspaceShorthand(name, shorthand, displayPath, catalog, wsDeps));
            } else if (value instanceof TomlTable entry) {
                out.put(name, parseWorkspaceDepEntry(name, entry, catalog));
            } else {
                throw new JkBuildParseException(
                        displayPath + " must be a version such as \"1.2.3\" or an inline table");
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
            String name, String value, String displayPath, LibraryCatalog catalog, TomlTable table) {
        if (value.isBlank()) {
            throw new JkBuildParseException(displayPath + " has an empty value string");
        }
        if (ManifestDeps.isPathShorthand(value)) {
            throw new JkBuildParseException(displayPath + ": a local module belongs in `[workspace] modules`");
        }
        if (ManifestDeps.isGitUrlShorthand(value)) {
            throw new JkBuildParseException(displayPath + ": write a git source as `{ git = \"...\" }`");
        }
        if (value.indexOf(':') >= 0) {
            Dependency gav = ManifestDeps.parseGavShorthand(name, value, displayPath, table);
            if (gav.isPlatformManaged()) {
                throw new JkBuildParseException(
                        displayPath + ": `" + value + "` has no version; write `group:artifact:1.2.3`");
            }
            return new WorkspaceDependency(gav.group(), gav.name(), gav.version(), null);
        }
        LibraryCatalog.Module mod = catalog.lookup(name)
                .orElseThrow(() ->
                        new JkBuildParseException(ManifestDeps.unknownLibraryMessage(displayPath, name, catalog)));
        return new WorkspaceDependency(
                mod.group(),
                mod.artifact(),
                ManifestSelectors.checked(VersionSelector.parse(value), table, name, displayPath),
                null);
    }

    static WorkspaceDependency parseWorkspaceDepEntry(String name, TomlTable entry, LibraryCatalog catalog) {
        String displayPath = "workspace.dependencies." + name;
        DependencyEntryKeys.requireKnown(entry, displayPath, DependencyEntryKeys.WORKSPACE);
        boolean hasVersion = entry.contains("version");
        boolean hasGit = entry.contains("git");
        if (entry.contains("path")) {
            throw new JkBuildParseException(displayPath + ": a local module belongs in `[workspace] modules`,"
                    + " not [workspace.dependencies]");
        }
        int sourceCount = (hasVersion ? 1 : 0) + (hasGit ? 1 : 0);
        if (sourceCount == 0) {
            throw new JkBuildParseException(displayPath + " must set exactly one of `version` or `git`");
        }
        if (sourceCount > 1) {
            throw new JkBuildParseException(displayPath + " sets both `version` and `git`; pick one");
        }
        if (hasGit) {
            // Git workspace deps still carry an explicit coordinate: the shared coordinate is what
            // sibling modules pin against, and it must be known at parse time.
            String gitGroup = optionalString(entry, "group", displayPath + ".group");
            if (gitGroup == null || gitGroup.isBlank()) {
                throw new JkBuildParseException(displayPath + " with `git` must set a `group`");
            }
            String gitArtifact = optionalString(entry, "name", displayPath + ".name");
            if (gitArtifact == null) gitArtifact = name;
            if (gitArtifact.isBlank()) {
                throw new JkBuildParseException(displayPath + ".name must not be blank");
            }
            if (entry.contains(DependencyExclusions.KEY)) {
                throw new JkBuildParseException(displayPath + "." + DependencyExclusions.KEY
                        + " applies only to a Maven coordinate, not a git source");
            }
            GitSource source = ManifestDeps.parseGitSource(entry, displayPath);
            return new WorkspaceDependency(gitGroup, gitArtifact, null, source);
        }
        // Version dep: group/name may be explicit or resolved from the catalog by the short name,
        // exactly like a [dependencies] entry.
        String groupExplicit = optionalString(entry, "group", displayPath + ".group");
        String artifactExplicit = optionalString(entry, "name", displayPath + ".name");
        LibraryCatalog.Module catalogHit =
                (groupExplicit == null) ? catalog.lookup(name).orElse(null) : null;
        String group = groupExplicit != null ? groupExplicit : (catalogHit != null ? catalogHit.group() : null);
        String artifact =
                artifactExplicit != null ? artifactExplicit : (catalogHit != null ? catalogHit.artifact() : name);
        if (group == null || group.isBlank()) {
            throw new JkBuildParseException(displayPath + " needs a `group` or a catalog short name");
        }
        if (artifact.isBlank()) {
            throw new JkBuildParseException(displayPath + ".name must not be blank");
        }
        String versionRaw = optionalString(entry, "version", displayPath + ".version");
        if (versionRaw == null || versionRaw.isBlank()) {
            throw new JkBuildParseException(displayPath + ".version must not be blank");
        }
        return new WorkspaceDependency(
                group,
                artifact,
                ManifestSelectors.checked(
                        VersionSelector.parse(versionRaw), entry, "version", displayPath + ".version"),
                null,
                DependencyExclusions.parseList(entry, displayPath));
    }

    /**
     * The optional {@code [application]} table. Presence marks the project as an application
     * ({@link JkBuild#isApplication}). {@code main} is required. {@code Optional.empty} when the
     * table is absent. {@code libDirImplied}: a plugin's table gives the module the lib-dir layout
     * unless the table writes {@code lib-dir} itself ({@link ManifestLibDir#parse}).
     */
    static Optional<JkBuild.Application> parseApplication(TomlTable root, boolean libDirImplied) {
        rejectFlattenedApplicationKeys(root);
        TomlTable application = optionalTable(root, "application", "`application`");
        if (application == null) return Optional.empty();
        // Same stance as the [m2]/[install] guards: a typo inside the right table must not parse
        // clean and do nothing — `asembly = true` silently building a thin jar is the identical
        // symptom the misplacement guard below exists for.
        rejectUnknownKeys(application, APPLICATION_KEYS, "[application]");
        String main = optionalString(application, "main", "[application].main");
        boolean assembly = artifactFlag(application, "assembly");
        boolean minified = artifactFlag(application, "minified");
        boolean nativeImage = artifactFlag(application, "native");
        String config = optionalString(application, "config", "[application].config");
        if (main == null || main.isBlank()) {
            throw new JkBuildParseException("[application].main is required");
        }
        return Optional.of(new JkBuild.Application(
                main,
                assembly,
                minified,
                nativeImage,
                config,
                parseRelocate(application, "[application]"),
                jvmArgsList(application, "application"),
                ManifestLibDir.parse(application, libDirImplied)));
    }

    static void rejectLibrary(TomlTable root) {
        if (!root.contains("library")) return;
        if (root.isTable("library")) {
            TomlTable library = Objects.requireNonNull(root.getTable("library"));
            for (String key : List.of("relocate", "assembly")) {
                if (library.contains(key)) {
                    throw new JkBuildParseException(
                            "library." + key + ": library jars ship thin; use [application] to relocate");
                }
            }
        }
        throw new JkBuildParseException("[library]: library jars ship thin; relocation is for [application]");
    }

    /**
     * {@code relocate}: a table of package prefix to shaded package prefix, declaration order kept.
     * Both sides are dotted package names; an empty side or a non-string value is a parse error
     * rather than a rule that silently moves nothing. {@code owner} names the table for the message.
     */
    private static Map<String, String> parseRelocate(TomlTable table0, String owner) {
        if (!table0.contains("relocate")) return Map.of();
        if (!table0.isTable("relocate")) {
            throw new JkBuildParseException(
                    owner + ".relocate must be a table of `\"from.package\" =" + " \"to.package\"` entries");
        }
        TomlTable table = Objects.requireNonNull(table0.getTable("relocate"));
        Map<String, String> relocate = new LinkedHashMap<>();
        for (String from : table.keySet()) {
            Object to = table.get(List.of(from));
            if (!(to instanceof String shaded) || shaded.isBlank() || from.isBlank()) {
                throw new JkBuildParseException(owner + ".relocate." + from
                        + " must name the shaded package as a string, e.g. \"com.example.shaded." + from + "\"");
            }
            relocate.put(from, shaded);
        }
        return relocate;
    }

    /**
     * Every key that belongs to {@code [application]}. Written down so a misplacement is caught as
     * a class rather than one key at a time.
     */
    static final List<String> APPLICATION_KEYS = List.of(
            "main",
            "assembly",
            "minified",
            "native",
            "config",
            "relocate",
            "jvm-args",
            ManifestLibDir.LIB_DIR,
            ManifestLibDir.LIB_VERSIONS,
            ManifestLibDir.LIB_EXCLUDE);

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
     * guard, which rejects the flattened {@code m2integration} by name.
     */
    static void rejectFlattenedApplicationKeys(TomlTable root) {
        for (String key : APPLICATION_KEYS) {
            if (!root.contains(key) || root.isTable(key)) continue;
            // `native` and `config` are also real top-level TABLES with their own semantics
            // ([application] native = true declares the artifact; [native] enabled = true tunes
            // the build) — a bare scalar could be aiming at either, so the error names both.
            String alsoATable = key.equals("native") || key.equals("config") ? ", or use the [" + key + "] table" : "";
            throw new JkBuildParseException("top-level `" + key + "` belongs under [application]" + alsoATable);
        }
    }

    /**
     * One additive artifact switch. Artifacts stack — a thin jar always, {@code assembly} adds the
     * fat jar, {@code minified} adds the R8 jar and implies the fat one — so each key is a plain
     * boolean rather than a mode.
     */
    /** Whether {@code [application] native} asks for a native image (the table read as written). */
    static boolean nativeAsked(TomlTable root) {
        return root.get(List.of("application")) instanceof TomlTable application && artifactFlag(application, "native");
    }

    static boolean artifactFlag(TomlTable application, String key) {
        return artifactFlag(application, key, "[application]");
    }

    /** As {@link #artifactFlag(TomlTable, String)}; {@code owner} names the table a rejection quotes. */
    static boolean artifactFlag(TomlTable application, String key, String owner) {
        if (!application.contains(key)) return false;
        if (application.isBoolean(key)) return Boolean.TRUE.equals(application.getBoolean(key));
        String raw = application.isString(key) ? application.getString(key) : null;
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        // "none" is this key's own extra spelling for off; everything else is the jk-wide truth set.
        if (value.equals("none")) return false;
        Optional<Boolean> flag = EnvValues.parseBool(value);
        if (flag.isPresent()) return flag.get();
        if (value.equals("shrink") || value.equals("shrunk") || value.equals("r8")) {
            throw new JkBuildParseException(
                    owner + "." + key + " is a boolean, not \"" + raw + "\"; set `minified = true` for an R8 jar");
        }
        throw new JkBuildParseException(
                owner + "." + key + " must be true or false (got \"" + (raw == null ? "" : raw) + "\")");
    }

    /** Schema-validate each installed plugin's owned table into a {@link PluginConfig}. */
    static Map<String, PluginConfig> parsePluginTables(TomlTable root, List<PluginDescriptor> installed) {
        Map<String, PluginConfig> out = new LinkedHashMap<>();
        for (PluginDescriptor manifest : installed) {
            TomlTable table = root.getTable(manifest.table());
            if (table == null) continue;
            out.put(
                    manifest.id(),
                    PluginTableRegistry.validate(
                            manifest, table, JkBuildParseException.positionOf(root, manifest.table())));
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
                throw new JkBuildParseException("[minified] needs `minified = true` under [application]");
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
            throw new JkBuildParseException("minified = true: the minified plugin is not installed");
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
