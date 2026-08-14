// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static cc.jumpkick.config.JkBuildParser.*;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.library.LibraryCatalog;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.Feature;
import cc.jumpkick.model.Features;
import cc.jumpkick.model.GitSource;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.ObjectStoreConfig;
import cc.jumpkick.model.Profile;
import cc.jumpkick.model.Profiles;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.Variants;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.model.Workspace;
import cc.jumpkick.model.Workspace.WorkspaceDependency;
import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.manifest.PluginDescriptor;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.NullMarked;
import org.tomlj.Toml;
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

    static Variants.Value parseVariantValue(
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
                List<Dependency> parsed = ManifestDeps.parseScopeTable(
                        scopeTable, new ArrayList<>(scopeTable.keySet()), scope, workspace, catalog);
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
     * <em>not</em> read here; it derives from {@code project.main}.
     */
    static Map<String, String> parseManifest(TomlParseResult root) {
        TomlTable table = root.getTable("manifest");
        if (table == null) return Map.of();
        Map<String, String> attrs = new LinkedHashMap<>();
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

    static List<RepositorySpec> parseRepositories(TomlTable root) {
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
                credential = RepositoryToml.credential(t, UnaryOperator.identity());
                objectStore = RepositoryToml.objectStore(t, UnaryOperator.identity());
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
    static Profiles parseProfiles(TomlTable root) {
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
    public static JkBuildParser.TestTomlTags parseTestTags(Path buildFile) {
        if (buildFile == null || !Files.isRegularFile(buildFile)) {
            return JkBuildParser.TestTomlTags.EMPTY;
        }
        try {
            String toml = Files.readString(buildFile);
            TomlParseResult result = Toml.parse(toml);
            if (result.hasErrors()) return JkBuildParser.TestTomlTags.EMPTY;
            TomlTable test = result.getTable("test");
            if (test == null) return JkBuildParser.TestTomlTags.EMPTY;
            return new JkBuildParser.TestTomlTags(
                    optionalStringList(test, "include-tags", "test.include-tags"),
                    optionalStringList(test, "exclude-tags", "test.exclude-tags"));
        } catch (Exception e) {
            return JkBuildParser.TestTomlTags.EMPTY;
        }
    }

    static Features parseFeatures(TomlTable root) {
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

    static Workspace parseWorkspace(TomlTable root, LibraryCatalog catalog) {
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
     * "latest"}, …). The short {@code name} is resolved to a {@code group:artifact} through the
     * bundled catalog, matching the {@code [dependencies]} shorthand. Local-path and git-URL string
     * forms are not accepted here — a shared workspace dep must be a Maven coordinate (use the inline
     * {@code git = "..."} table form for git, or a {@code [workspace] modules} entry for a sibling).
     */
    static WorkspaceDependency parseWorkspaceShorthand(
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
                .orElseThrow(() ->
                        new JkBuildParseException(ManifestDeps.unknownLibraryMessage(displayPath, name, catalog)));
        return new WorkspaceDependency(mod.group(), mod.artifact(), VersionSelector.parseFloating(value), null);
    }

    static WorkspaceDependency parseWorkspaceDepEntry(String name, TomlTable entry, LibraryCatalog catalog) {
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
        return new WorkspaceDependency(group, artifact, VersionSelector.parseFloating(versionRaw), null);
    }

    /**
     * The optional {@code [application]} table. Its mere presence marks the project as an
     * application ({@link JkBuild#isApplication}) — {@code Optional.empty} when absent, never a
     * defaulted-fields sentinel, so presence and "declared but empty" stay distinguishable.
     */
    static Optional<JkBuild.Application> parseApplication(TomlTable root) {
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
    static boolean artifactFlag(TomlTable application, String key) {
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
    public static JkBuildParser.ArtifactOverride parseArtifactOverride(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "fat", "true", "assembly" -> new JkBuildParser.ArtifactOverride(true, false);
            case "minified", "min" -> new JkBuildParser.ArtifactOverride(true, true);
            case "off", "false", "none", "thin" -> new JkBuildParser.ArtifactOverride(false, false);
            default -> throw new IllegalArgumentException("unknown artifact override: " + raw + " (want fat|minified)");
        };
    }
}
