// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static cc.jumpkick.config.JkBuildParser.*;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PlatformPolicy;
import cc.jumpkick.model.PluginConfig;
import cc.jumpkick.model.PluginDeclaration;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.ToolchainSpec;
import cc.jumpkick.model.UnmappedPolicy;
import cc.jumpkick.model.VersionSelector;
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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
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
                "kotlin-plugins",
                "m2",
                "install"));
        for (Scope scope : Scope.values()) out.add(scope.tomlSection()); // [dependencies] + scoped spellings
        return Set.copyOf(out);
    }

    /**
     * Before the parse captures its manifest set: give the engine's lazy fetcher one chance to
     * install the built-in owning each referenced-but-unowned top-level table (cold store, first
     * use). No-op outside the engine. Returns table → failure detail for fetches that failed, so
     * {@link #checkUnownedTables} can surface the real cause.
     */
    static Map<String, String> ensureBuiltInTables(TomlTable root) {
        Map<String, String> failures = new LinkedHashMap<>();
        for (String key : root.keySet()) {
            if (CORE_TABLES.contains(key) || ManifestProject.PROJECT_KEYS.contains(key)) continue;
            if (!(root.get(key) instanceof TomlTable) && !(root.get(key) instanceof TomlArray)) continue;
            if (PluginTableRegistry.byTable(key).isPresent()) continue;
            String detail = PluginTableRegistry.tryFetchMissingBuiltIn(key);
            if (detail != null) failures.put(key, detail);
        }
        return failures;
    }

    /**
     * Error on top-level tables neither core nor owned by an installed plugin. Suppressed while
     * any {@code [plugins]} declaration is still unresolved (unknown ownership pre-lock).
     */
    static void checkUnownedTables(
            TomlTable root,
            @Nullable Path moduleDir,
            List<PluginDeclaration> plugins,
            List<PluginDescriptor> installed,
            Map<String, String> builtInFetchFailures) {
        if (!plugins.isEmpty() && PluginDescriptorStore.hasUnresolved(moduleDir, plugins)) return;
        Set<String> owned = new HashSet<>(CORE_TABLES);
        for (PluginDescriptor m : installed) owned.add(m.table());
        for (String key : root.keySet()) {
            if (owned.contains(key)) continue;
            // Project identity keys (and Cargo-style inherit tables like group = { workspace = true }).
            if (ManifestProject.PROJECT_KEYS.contains(key)) continue;
            if (!(root.get(key) instanceof TomlTable) && !(root.get(key) instanceof TomlArray)) continue;
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
                // The plugin was renamed; steer pre-rename projects the same way the
                // `assembly = "shrink"` migration message does.
                throw new JkBuildParseException(
                        "[shrink] was renamed — use a [minified] table (and `assembly = \"minified\"`)");
            }
            String fetchDetail = builtInFetchFailures.get(key);
            throw new JkBuildParseException("[" + key + "] is not owned by any installed plugin — add it under"
                    + " [plugins] (plugin tables installed here: " + (known.length() == 0 ? "none" : known) + ")"
                    + (fetchDetail == null ? "" : "; fetching the built-in plugin failed: " + fetchDetail));
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
    static JkBuild reapplyPlatformContributions(@Nullable Path moduleDir, JkBuild module) {
        try {
            List<PluginDescriptor> manifests = PluginTableRegistry.manifestsFor(moduleDir, module.plugins());
            return module.withDependencies(withPlatformContributions(
                    module.dependencies(),
                    module.project(),
                    module.nativeConfigOpt().isPresent(),
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
            Project project,
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
     * Optional {@code [native]}: empty when the table is absent. Presence defaults to {@link
     * JkBuild.NativeMode#SUPPORTED} ({@code enabled = true}). {@code enabled = false} keeps the
     * table but disables native builds; {@code enabled = "always"} (or legacy {@code always =
     * true}) auto-runs native-image on {@code jk build}. Default {@code graal} is {@code
     * "graalvm"}.
     */
    static Optional<JkBuild.NativeConfig> parseNativeConfig(TomlTable root) {
        TomlTable native_ = root.getTable("native");
        if (native_ == null) return Optional.empty();
        if (native_.contains("main-class")) {
            throw new JkBuildParseException("[native].main-class was renamed — use main");
        }
        String mainClass = native_.getString("main");
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
        ToolchainSpec graalSpec = ManifestProject.parseGraalToolchain(native_);
        // A bare [native] contrives its Graal from the project's own major (jdk, else java); the
        // vendor is left open, so whichever GraalVM distribution resolves is the one recorded.
        String graal = graalSpec.resolverSpec();
        if (graal.isEmpty()) graal = "graalvm";
        JkBuild.NativeMode enabled = parseNativeEnabled(native_);
        VersionSelector metadata = parseMetadataRepository(native_);
        return Optional.of(new JkBuild.NativeConfig(
                mainClass,
                name,
                args,
                graal,
                enabled,
                metadata == null ? JkBuild.NativeConfig.METADATA_REPOSITORY_DEFAULT : metadata,
                graalSpec));
    }

    /**
     * {@code [native].metadata-repository} — the GraalVM reachability-metadata repository release,
     * in the dependency version grammar. Null (key omitted) leaves {@link
     * JkBuild.NativeConfig#METADATA_REPOSITORY_DEFAULT} in place. Bare versions float like a
     * dependency's ({@code "1.1"} is a caret floor); write {@code "=1.1.4"} to nail one release.
     */
    private static @Nullable VersionSelector parseMetadataRepository(TomlTable native_) {
        String raw = native_.getString("metadata-repository");
        if (raw == null) return null;
        if (raw.isBlank()) {
            throw new JkBuildParseException("[native].metadata-repository must not be blank");
        }
        try {
            return VersionSelector.parseFloating(raw);
        } catch (IllegalArgumentException e) {
            throw new JkBuildParseException("[native].metadata-repository: " + e.getMessage());
        }
    }

    /**
     * Resolve {@code [native].enabled}: boolean true/false, string {@code "always"}, or omit for
     * enabled-true. Legacy {@code always = true} maps to {@link JkBuild.NativeMode#ALWAYS} when
     * {@code enabled} is absent.
     */
    static JkBuild.NativeMode parseNativeEnabled(TomlTable native_) {
        if (native_.contains("enabled")) {
            Object raw = native_.get("enabled");
            if (raw instanceof Boolean b) {
                return b ? JkBuild.NativeMode.SUPPORTED : JkBuild.NativeMode.DISABLED;
            }
            if (raw instanceof String s) {
                return switch (s.trim().toLowerCase(Locale.ROOT)) {
                    case "always" -> JkBuild.NativeMode.ALWAYS;
                    case "true", "yes", "on" -> JkBuild.NativeMode.SUPPORTED;
                    case "false", "no", "off" -> JkBuild.NativeMode.DISABLED;
                    default ->
                        throw new JkBuildParseException(
                                "[native].enabled must be true, false, or \"always\" (got \"" + s + "\")");
                };
            }
            throw new JkBuildParseException("[native].enabled must be true, false, or \"always\"");
        }
        // Legacy always = true (pre-enabled key).
        if (Boolean.TRUE.equals(native_.getBoolean("always"))) {
            return JkBuild.NativeMode.ALWAYS;
        }
        // [native] present with no enabled key → enabled = true.
        return JkBuild.NativeMode.SUPPORTED;
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
        // the slow/integration tests the config meant to exclude with no signal.
        if (test != null) {
            if (test.contains("default-exclude-tags")) {
                throw new JkBuildParseException("[test].default-exclude-tags was renamed to exclude-tags "
                        + "(profiles and --exclude-tags replace it per run; `exclude-tags = []` clears)");
            }
            if (test.contains("include-tag") || test.contains("exclude-tag")) {
                throw new JkBuildParseException("[test] tag keys are plural: include-tags / exclude-tags");
            }
            if (test.contains("gate-suite")) {
                throw new JkBuildParseException("[test] gate suite list is plural: gate-suites");
            }
        }
        PlatformPolicy platformPolicy = PlatformPolicy.ENFORCED;
        UnmappedPolicy unmappedPolicy = UnmappedPolicy.MEDIATE;
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
                unmappedPolicy = UnmappedPolicy.parse(raw);
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
                    List.of(),
                    null,
                    null,
                    List.of(),
                    platformPolicy,
                    unmappedPolicy,
                    List.of());
        }

        List<String> orderAfter = new ArrayList<>();
        List<String> testPluginJars = new ArrayList<>();
        boolean lint = true;
        List<String> kspOptions = new ArrayList<>();
        List<String> extraSrc = new ArrayList<>();
        List<String> testExtraSrc = new ArrayList<>();
        String fixtures = null;
        Integer testWorkers = null;
        List<String> testSerialTags = new ArrayList<>();

        if (build != null) {
            if (build.contains("extra-resources")) {
                throw new JkBuildParseException("[build].extra-resources is not a setting — a plugin"
                        + " worker ships its own jk-plugin.toml at the jar root (module-root"
                        + " jk-plugin.toml is copied there automatically; src/main/resources/"
                        + "jk-plugin.toml already is). Modules cannot pull files from other modules.");
            }
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
            // [build] test-workers — pin within-module test JVMs (1 = serial; 0 = same as omitting: the
            // build's auto share).
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
            // [test] extra-src — extra test-tier sources compiled into test classes. A single file
            // is legal where a directory would over-reach (clients/cli compiling one IntelliJ
            // parser type). Shared helpers a sibling consumes belong in [test] fixtures.
            TomlArray tes = test.getArray("extra-src");
            if (tes != null) {
                for (int i = 0; i < tes.size(); i++) {
                    Object val = tes.get(i);
                    if (!(val instanceof String str) || str.isBlank())
                        throw new JkBuildParseException("[test].extra-src must be an array of directory or file paths");
                    testExtraSrc.add(str);
                }
            }
            // [test] fixtures — a source root compiled to target/test-fixtures/classes, never an
            // artifact. `true` means src/fixtures/java; a string names the root.
            if (test.contains("fixtures")) {
                Object raw = test.get("fixtures");
                if (raw instanceof Boolean flag) {
                    fixtures = flag ? JkBuild.Build.DEFAULT_FIXTURES : null;
                } else if (raw instanceof String path) {
                    if (path.isBlank()) {
                        throw new JkBuildParseException(
                                "[test].fixtures must be `true` or a non-empty module-relative directory");
                    }
                    fixtures = path;
                } else {
                    throw new JkBuildParseException(
                            "[test].fixtures must be `true` or a non-empty module-relative directory");
                }
            }
            // [test] serial-tags — class-level tags that never share the sharded worker pool.
            TomlArray st = test.getArray("serial-tags");
            if (st != null) {
                for (int i = 0; i < st.size(); i++) {
                    Object val = st.get(i);
                    if (!(val instanceof String s))
                        throw new JkBuildParseException("[test].serial-tags must be an array of tag strings");
                    if (!s.isBlank()) testSerialTags.add(s);
                }
            }
        }
        return new JkBuild.Build(
                orderAfter,
                testPluginJars,
                lint,
                List.of(),
                kspOptions,
                extraSrc,
                List.copyOf(testExtraSrc),
                fixtures,
                testWorkers,
                testSerialTags,
                platformPolicy,
                unmappedPolicy,
                List.of());
    }

    /**
     * {@code [test] env} — environment variables for each forked test JVM.
     *
     * <pre>
     * [test]
     * env = { JK_HOME = "${target}/test-jk-home", JK_HTTP_ENABLED = "false" }
     * </pre>
     *
     * Values are stored exactly as written. {@code ${target}}, {@code ${module}} and {@code ${VAR}}
     * are all expanded later, by {@link TestEnvValues}, because what they expand to depends on the
     * consumer: the forked JVM wants real directories and real values, the run-tests cache key wants
     * portable tokens and a digest. {@code [test] env} is one of the few positions where
     * {@code ${VAR}} is legal at all — see {@link Interpolation}.
     */
    /**
     * {@code [test] env} — an array whose element shape says which of the two statements it is.
     *
     * <pre>{@code
     * env = [
     *   "JK_WEB_JS_SKIP",                       # forward the caller's, if set
     *   { TZ = "UTC", LANG = "C" },             # set these outright
     * ]
     * }</pre>
     *
     * <p>A bare string is a name and nothing else: it may not carry {@code =} or {@code ${…}}.
     * Both are rejected rather than interpreted, because both are how the same idea is spelled in
     * the two neighbouring formats a reader is likely arriving from — a {@code .env} file and a
     * {@code docker run -e} flag — and quietly accepting either would make {@code "TZ=UTC"} a
     * variable literally named {@code TZ=UTC}.
     */
    static List<JkBuild.TestEnvDecl> parseTestEnv(TomlTable root) {
        TomlTable test = root.getTable("test");
        if (test == null) return List.of();
        Object raw = test.get(List.of("env"));
        if (raw == null) return List.of();
        if (!(raw instanceof TomlArray arr)) {
            throw new JkBuildParseException("[test] env must be an array — a bare name to forward the"
                    + " caller's value, or a table to set one: env = [\"CI\", { TZ = \"UTC\" }]");
        }
        List<JkBuild.TestEnvDecl> out = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            Object element = arr.get(i);
            String where = "[test].env[" + i + "]";
            if (element instanceof String name) {
                out.add(new JkBuild.TestEnvDecl.Forward(forwardName(name, where)));
            } else if (element instanceof TomlTable table) {
                for (String key : table.keySet()) {
                    out.add(new JkBuild.TestEnvDecl.Set(key, scalar(table.get(List.of(key)), where + "." + key)));
                }
            } else {
                throw new JkBuildParseException(
                        where + " must be a name to forward (a string) or a table of values to set");
            }
        }
        return List.copyOf(out);
    }

    /** A forwarded name is a name: no value half, no reference. */
    private static String forwardName(String raw, String where) {
        String name = raw.trim();
        if (name.isEmpty()) throw new JkBuildParseException(where + " is an empty environment variable name");
        if (name.indexOf('=') >= 0) {
            throw new JkBuildParseException(where + " (\"" + raw + "\") looks like NAME=value. A bare string"
                    + " forwards the caller's value; to set one, use a table: { "
                    + name.substring(0, name.indexOf('=')) + " = \""
                    + name.substring(name.indexOf('=') + 1) + "\" }");
        }
        if (name.indexOf('$') >= 0) {
            throw new JkBuildParseException(where + " (\"" + raw + "\") is a variable name, not a value —"
                    + " ${…} is expanded in the table form, not here");
        }
        return name;
    }

    private static String scalar(@Nullable Object value, String where) {
        if (value instanceof String s) return s;
        if (value instanceof Boolean || value instanceof Long || value instanceof Double) {
            return String.valueOf(value);
        }
        throw new JkBuildParseException(where + " must be a string (or a bare boolean/number)");
    }

    /**
     * {@code [[kotlin-plugins]]}: {@code coordinate} is {@code group:artifact[:version]} (omitted)
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
