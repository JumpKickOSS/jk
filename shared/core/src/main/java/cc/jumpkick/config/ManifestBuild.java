// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static cc.jumpkick.config.JkBuildParser.*;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PlatformPolicy;
import cc.jumpkick.model.PluginDeclaration;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.manifest.PluginContributions;
import cc.jumpkick.plugin.manifest.PluginDescriptor;
import cc.jumpkick.plugin.manifest.PluginDescriptorStore;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.NullMarked;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

/**
 * Plugin declarations, unowned-table checks, platform contributions, [native], and [build].
 */
@NullMarked
public final class ManifestBuild {

    private ManifestBuild() {}

    /** Core top-level tables; anything else must be owned by an installed plugin. */
    static final Set<String> CORE_TABLES = coreTables();

    static Set<String> coreTables() {
        Set<String> out = new HashSet<>(Set.of(
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
                "jvm",
                "deny",
                "config",
                "forge",
                "kotlin-plugins"));
        for (Scope scope : Scope.values()) out.add(scope.tomlSection()); // [dependencies] + scoped spellings
        return Set.copyOf(out);
    }

    /**
     * Error on top-level tables neither core nor owned by an installed plugin. Suppressed while
     * any {@code [plugins]} declaration is still unresolved (unknown ownership pre-lock).
     */
    static void checkUnownedTables(
            TomlTable root, Path moduleDir, List<PluginDeclaration> plugins, List<PluginDescriptor> installed) {
        if (!plugins.isEmpty() && PluginDescriptorStore.hasUnresolved(moduleDir, plugins)) return;
        Set<String> owned = new HashSet<>(CORE_TABLES);
        for (PluginDescriptor m : installed) owned.add(m.table());
        for (String key : root.keySet()) {
            if (owned.contains(key)) continue;
            // Project identity keys (and Cargo-style inherit tables like group = { workspace = true }).
            if (ManifestProject.PROJECT_KEYS.contains(key)) continue;
            if (!(root.get(key) instanceof TomlTable) && !(root.get(key) instanceof org.tomlj.TomlArray)) continue;
            StringBuilder known = new StringBuilder();
            for (PluginDescriptor m : installed) {
                if (known.length() > 0) known.append(", ");
                known.append('[').append(m.table()).append(']');
            }
            if ("project".equals(key)) {
                throw new JkBuildParseException("[project] was removed — move its keys to the top level of jk.toml"
                        + " (e.g. name = \"…\", group = \"…\", version = \"…\")");
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

    static JkBuild.Dependencies withPlatformContributions(
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
    static Optional<JkBuild.NativeConfig> parseNativeConfig(TomlTable root) {
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
        String graal = ManifestProject.parseGraalSpec(native_);
        if (graal == null) graal = "graalvm";
        boolean always = Boolean.TRUE.equals(native_.getBoolean("always"));
        return Optional.of(new JkBuild.NativeConfig(mainClass, name, args, graal, always));
    }

    /**
     * Optional {@code [build]} and/or {@code [test]} tables. Either may appear alone: a lone
     * {@code [test] workers = 1} is enough for Mill-style serial opt-out without a {@code [build]}
     * block. Absent both → {@link JkBuild.Build#EMPTY}.
     */
    static JkBuild.Build parseBuild(TomlTable root) {
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
    static Map<String, String> parseTestEnv(TomlTable root) {
        TomlTable test = root.getTable("test");
        if (test == null) return Map.of();
        TomlTable env = test.getTable("env");
        if (env == null) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
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

    static List<JkBuild.ExtraResource> parseExtraResources(TomlTable build) {
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
    static List<JkBuild.KotlinPluginDecl> parseKotlinPlugins(TomlTable root) {
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

    static final Set<String> PLUGIN_RESERVED = Set.of("group", "name", "version", "path", "sha256", "coordinate");

    static List<PluginDeclaration> parsePlugins(TomlTable root) {
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
            Map<String, Object> config = new LinkedHashMap<>();
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
                        Collections.unmodifiableMap(config)));
            } catch (IllegalArgumentException e) {
                throw new JkBuildParseException("plugins." + alias + ": " + e.getMessage());
            }
        }
        return List.copyOf(result);
    }
}
