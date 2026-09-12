// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static cc.jumpkick.config.JkBuildParser.*;

import cc.jumpkick.model.DebugInfo;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.EnvConfig;
import cc.jumpkick.model.EnvDecl;
import cc.jumpkick.model.JavacConfig;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PlatformPolicy;
import cc.jumpkick.model.PluginConfig;
import cc.jumpkick.model.PluginDeclaration;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.Sidecar;
import cc.jumpkick.model.ToolchainSpec;
import cc.jumpkick.model.UnmappedPolicy;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.plugin.manifest.PluginContributions;
import cc.jumpkick.plugin.manifest.PluginDescriptor;
import cc.jumpkick.plugin.manifest.PluginDescriptorStore;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
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
                "dev",
                "format",
                "resolve",
                "variants",
                "jvm",
                "deny",
                "audit",
                "config",
                "forge",
                "kotlin-plugins",
                "javac",
                "env",
                "m2",
                "install",
                "guards"));
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

    /** The keys {@code [native]} may carry. */
    public static final List<String> NATIVE_KEYS =
            List.of("enabled", "main", "name", "args", "graal", "graal-vendor", "graal-version", "metadata-repository");

    /**
     * Optional {@code [native]}: empty when the table is absent. Presence defaults to {@link
     * JkBuild.NativeMode#SUPPORTED} ({@code enabled = true}). {@code enabled = false} keeps the
     * table but disables native builds; {@code enabled = "always"} auto-runs native-image on {@code jk build}. Default {@code graal} is {@code
     * "graalvm"}.
     */
    static Optional<JkBuild.NativeConfig> parseNativeConfig(TomlTable root) {
        TomlTable native_ = root.getTable("native");
        if (native_ == null) return Optional.empty();
        for (String key : native_.keySet()) {
            if (!NATIVE_KEYS.contains(key)) {
                throw new JkBuildParseException(
                        "[native] unknown key `" + key + "` — expected one of: " + String.join(", ", NATIVE_KEYS));
            }
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
     * enabled-true.
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
        ResolvePolicies policies = resolvePolicies(resolve);
        if (build == null && test == null && resolve == null) return JkBuild.Build.EMPTY;
        if (build == null && test == null) {
            return new JkBuild.Build(
                    List.of(),
                    List.of(),
                    true,
                    DebugInfo.FULL,
                    List.of(),
                    List.of(),
                    JavacConfig.EMPTY,
                    List.of(),
                    List.of(),
                    null,
                    null,
                    List.of(),
                    policies.platform(),
                    policies.unmapped(),
                    List.of(),
                    List.of(),
                    List.of(),
                    EnvConfig.EMPTY);
        }
        ManifestBuildTable.Settings s = ManifestBuildTable.read(build, test);
        return new JkBuild.Build(
                s.orderAfter,
                s.testPluginJars,
                s.lint,
                s.debug,
                List.of(),
                s.kspOptions,
                JavacConfig.EMPTY,
                s.extraSrc,
                List.copyOf(s.testExtraSrc),
                s.fixtures,
                s.testWorkers,
                s.testSerialTags,
                policies.platform(),
                policies.unmapped(),
                List.of(),
                List.of(),
                List.of(),
                EnvConfig.EMPTY);
    }

    /** The two {@code [resolve]} policies, at their defaults when the table or key is absent. */
    private record ResolvePolicies(PlatformPolicy platform, UnmappedPolicy unmapped) {}

    private static ResolvePolicies resolvePolicies(@Nullable TomlTable resolve) {
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
        return new ResolvePolicies(platformPolicy, unmappedPolicy);
    }

    /** The keys one {@code [dev.sidecars.<name>]} table may carry; the schema names exactly these. */
    public static final List<String> SIDECAR_KEYS =
            List.of("command", "cwd", "env", "ready", "ready-pattern", "ready-timeout", "front-door", "restart");

    /** The keys {@code [dev]} itself may carry. */
    public static final List<String> DEV_KEYS = List.of("sidecars");

    /**
     * {@code [dev.sidecars]} — one table per sidecar, keyed by name, in manifest order:
     *
     * <pre>
     * [dev.sidecars]
     * web = { command = "npm run dev", cwd = "../web", ready = "http://localhost:5173" }
     * </pre>
     *
     * {@code command} is a string split like a shell would (quotes group, no expansion, no shell)
     * or an array. {@code ready-timeout} is a duration — {@code "90s"}, {@code "2m"}, or a bare
     * number of seconds. Unknown keys fail the parse: {@code redy = …} would otherwise leave a
     * sidecar silently unprobed.
     */
    static List<Sidecar> parseDevSidecars(TomlTable root) {
        Object rawDev = root.get(List.of("dev"));
        if (rawDev == null) return List.of();
        if (!(rawDev instanceof TomlTable dev)) {
            throw new JkBuildParseException("[dev] must be a table: [dev.sidecars] web = { command = \"…\" }");
        }
        for (String key : dev.keySet()) {
            if (!DEV_KEYS.contains(key)) {
                throw new JkBuildParseException("[dev] unknown key `" + key + "` — expected sidecars");
            }
        }
        Object rawSidecars = dev.get(List.of("sidecars"));
        if (rawSidecars == null) return List.of();
        if (!(rawSidecars instanceof TomlTable sidecars)) {
            throw new JkBuildParseException(
                    "[dev.sidecars] must be a table keyed by sidecar name, not an array: [dev.sidecars] web = {"
                            + " command = \"…\" }");
        }
        List<Sidecar> out = new ArrayList<>();
        for (String name : sidecars.keySet()) {
            String where = "[dev.sidecars." + name + "]";
            if (!(sidecars.get(List.of(name)) instanceof TomlTable table)) {
                throw new JkBuildParseException(where + " must be a table: { command = \"…\" }");
            }
            out.add(parseSidecar(name, table, where));
        }
        return List.copyOf(out);
    }

    private static Sidecar parseSidecar(String name, TomlTable table, String where) {
        for (String key : table.keySet()) {
            if (!SIDECAR_KEYS.contains(key)) {
                throw new JkBuildParseException(
                        where + " unknown key `" + key + "` — expected one of: " + String.join(", ", SIDECAR_KEYS));
            }
        }
        Object rawCommand = table.get(List.of("command"));
        List<String> command;
        if (rawCommand instanceof String s) {
            try {
                command = ShellWords.split(s);
            } catch (IllegalArgumentException e) {
                throw new JkBuildParseException(where + ".command: " + e.getMessage());
            }
        } else if (rawCommand instanceof TomlArray arr) {
            command = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) command.add(scalar(arr.get(i), where + ".command[" + i + "]"));
        } else {
            throw new JkBuildParseException(
                    where + " needs command = \"npm run dev\" or command = [\"npm\", \"run\", \"dev\"]");
        }
        if (command.isEmpty()) throw new JkBuildParseException(where + " command is empty");
        String cwd = table.contains("cwd") ? scalar(table.get(List.of("cwd")), where + ".cwd") : ".";
        Map<String, String> env = new LinkedHashMap<>();
        if (table.contains("env")) {
            if (!(table.get(List.of("env")) instanceof TomlTable envTable)) {
                throw new JkBuildParseException(where + ".env must be a table: env = { PORT = \"5173\" }");
            }
            for (String key : envTable.keySet()) {
                if (!(envTable.get(List.of(key)) instanceof String value)) {
                    throw new JkBuildParseException(
                            where + ".env." + key + " must be a string: env = { " + key + " = \"…\" }");
                }
                env.put(key, value);
            }
        }
        String ready = table.contains("ready") ? scalar(table.get(List.of("ready")), where + ".ready") : null;
        String pattern = table.contains("ready-pattern")
                ? scalar(table.get(List.of("ready-pattern")), where + ".ready-pattern")
                : null;
        if (ready != null && pattern != null) {
            throw new JkBuildParseException(where + " sets both ready and ready-pattern — a sidecar has one probe");
        }
        if (pattern != null) {
            try {
                Pattern.compile(pattern);
            } catch (PatternSyntaxException e) {
                throw new JkBuildParseException(where + ".ready-pattern is not a regex: " + e.getDescription());
            }
        }
        long timeout = table.contains("ready-timeout")
                ? durationMillis(table.get(List.of("ready-timeout")), where + ".ready-timeout")
                : Sidecar.DEFAULT_READY_TIMEOUT_MILLIS;
        boolean frontDoor = false;
        if (table.contains("front-door")) {
            if (!(table.get(List.of("front-door")) instanceof Boolean b)) {
                throw new JkBuildParseException(where + ".front-door must be true or false");
            }
            frontDoor = b;
        }
        Sidecar.Restart restart = Sidecar.Restart.NEVER;
        if (table.contains("restart")) {
            try {
                restart = Sidecar.Restart.parse(scalar(table.get(List.of("restart")), where + ".restart"));
            } catch (IllegalArgumentException e) {
                throw new JkBuildParseException(where + ".restart " + e.getMessage());
            }
        }
        return new Sidecar(name, command, cwd, env, ready, pattern, timeout, frontDoor, restart);
    }

    private static final Pattern DURATION = Pattern.compile("(\\d+)\\s*(ms|s|m)?");

    /** {@code "90s"}, {@code "2m"}, {@code "1500ms"}, or a bare number of seconds. */
    static long durationMillis(@Nullable Object value, String where) {
        if (value instanceof Long seconds && seconds > 0) return seconds * 1000;
        if (value instanceof String s) {
            Matcher m = DURATION.matcher(s.trim());
            if (m.matches()) {
                long n = Long.parseLong(m.group(1));
                String unit = m.group(2) == null ? "s" : m.group(2);
                long millis =
                        switch (unit) {
                            case "ms" -> n;
                            case "m" -> n * 60_000;
                            default -> n * 1000;
                        };
                if (millis > 0) return millis;
            }
        }
        throw new JkBuildParseException(
                where + " must be a positive duration like \"60s\", \"2m\", \"500ms\", or seconds");
    }

    /**
     * {@code [test] env} — environment for each forked test JVM, an array whose element shape says
     * which of the two statements it is.
     *
     * <pre>{@code
     * env = [
     *   "JK_WEB_JS_SKIP",                       # forward the caller's, if set
     *   { TZ = "UTC", LANG = "C" },             # set these outright
     * ]
     * }</pre>
     *
     * <p>Values are stored exactly as written; {@code ${target}}, {@code ${module}} and {@code
     * ${VAR}} are expanded later by {@link TestEnvValues}, because the forked JVM wants real values
     * while the run-tests cache key wants portable tokens. This is one of the few positions where
     * {@code ${VAR}} is legal at all — see {@link Interpolation}.
     *
     * <p>A bare string is a name and nothing else: it may not carry {@code =} or {@code ${…}}.
     * Both are rejected rather than interpreted, because both are how the same idea is spelled in
     * the two neighbouring formats a reader is likely arriving from — a {@code .env} file and a
     * {@code docker run -e} flag — and quietly accepting either would make {@code "TZ=UTC"} a
     * variable literally named {@code TZ=UTC}.
     */
    static List<EnvDecl> parseTestEnv(TomlTable root) {
        TomlTable test = root.getTable("test");
        if (test == null) return List.of();
        return parseEnvDecls(test.get(List.of("env")), "[test]", "env");
    }

    /** The keys {@code [env]} may carry. */
    public static final List<String> ENV_KEYS = List.of("inherit", "vars");

    /**
     * {@code [env]} — what this module's workers may take from the environment beyond jk's
     * allow-list (docs/user/build.md). Unknown keys fail the parse: a variable written straight into
     * the table is refused with the {@code vars} spelling, so a typo cannot silently become a knob.
     *
     * <pre>
     * [env]
     * vars = ["DOCKER_HOST", { TZ = "UTC" }]   # forward a name, or set a value — the [test] env shape
     * inherit = true                            # the engine's whole environment; say why beside it
     * </pre>
     */
    static EnvConfig parseEnv(TomlTable root) {
        Object raw = root.get(List.of("env"));
        if (raw == null) return EnvConfig.EMPTY;
        if (!(raw instanceof TomlTable env)) {
            throw new JkBuildParseException("[env] must be a table: [env] vars = [\"CI\", { TZ = \"UTC\" }]");
        }
        for (String key : env.keySet()) {
            if (!ENV_KEYS.contains(key)) {
                throw new JkBuildParseException("[env] unknown key `" + key + "` — expected one of: "
                        + String.join(", ", ENV_KEYS) + ". To hand workers a variable, list it under vars: vars = [{ "
                        + key + " = \"…\" }]");
            }
        }
        boolean inherit = false;
        Object rawInherit = env.get(List.of("inherit"));
        if (rawInherit != null) {
            if (!(rawInherit instanceof Boolean b)) {
                throw new JkBuildParseException("[env] inherit must be true or false");
            }
            inherit = b;
        }
        return new EnvConfig(inherit, parseEnvDecls(env.get(List.of("vars")), "[env]", "vars"));
    }

    /**
     * The one parser for an environment array — {@code [test] env} and {@code [env] vars} share
     * the shape: a bare name forwards, a table sets. {@code table} and {@code key} only spell the
     * position in messages.
     */
    private static List<EnvDecl> parseEnvDecls(@Nullable Object raw, String table, String key) {
        if (raw == null) return List.of();
        if (!(raw instanceof TomlArray arr)) {
            throw new JkBuildParseException(table + " " + key + " must be an array — a bare name to forward the"
                    + " caller's value, or a table to set one: " + key + " = [\"CI\", { TZ = \"UTC\" }]");
        }
        List<EnvDecl> out = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            Object element = arr.get(i);
            String where = table + "." + key + "[" + i + "]";
            if (element instanceof String name) {
                out.add(new EnvDecl.Forward(forwardName(name, where)));
            } else if (element instanceof TomlTable values) {
                for (String name : values.keySet()) {
                    out.add(new EnvDecl.Set(name, scalar(values.get(List.of(name)), where + "." + name)));
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

    /** The keys {@code [javac]} may carry. */
    public static final List<String> JAVAC_KEYS = List.of("plugins", "args", "test");

    /** The keys {@code [javac.test]} may carry: the same shape, one level only. */
    public static final List<String> JAVAC_TEST_KEYS = List.of("plugins", "args");

    /** The keys one {@code [javac.plugins.<Name>]} table may carry. */
    public static final List<String> JAVAC_PLUGIN_KEYS = List.of("options");

    /**
     * {@code [javac]} — which javac plugins compile-main and compile-test invoke, and verbatim args:
     *
     * <pre>
     * [javac]
     * plugins = { ErrorProne = { options = ["-Xep:NullAway:ERROR"] } }
     * args    = ["-Xlint:all"]
     * </pre>
     *
     * A plugin's key is its registered javac name, passed through as {@code -Xplugin:<key>}; its
     * jar is a {@code [processor-dependencies]} entry. Unknown keys fail the parse.
     *
     * <p>{@code [javac.test]} carries the same two keys and, when present, replaces the table for
     * compile-test — an empty one turns the plugins off for the suite.
     */
    static JavacConfig parseJavac(TomlTable root) {
        Object raw = root.get(List.of("javac"));
        if (raw == null) return JavacConfig.EMPTY;
        if (!(raw instanceof TomlTable javac)) {
            throw new JkBuildParseException(
                    "[javac] must be a table: [javac] plugins = { ErrorProne = { options = […] } }");
        }
        JavacConfig test = null;
        Object rawTest = javac.get(List.of("test"));
        if (rawTest != null) {
            if (!(rawTest instanceof TomlTable table)) {
                throw new JkBuildParseException("[javac.test] must be a table: [javac.test] plugins = { … }");
            }
            test = parseJavacTable(table, "[javac.test]", JAVAC_TEST_KEYS, null);
        }
        return parseJavacTable(javac, "[javac]", JAVAC_KEYS, test);
    }

    private static JavacConfig parseJavacTable(
            TomlTable javac, String at, List<String> known, @Nullable JavacConfig test) {
        for (String key : javac.keySet()) {
            if (!known.contains(key)) {
                throw new JkBuildParseException(
                        at + " unknown key `" + key + "` — expected one of: " + String.join(", ", known));
            }
        }
        Map<String, List<String>> plugins = new LinkedHashMap<>();
        Object rawPlugins = javac.get(List.of("plugins"));
        if (rawPlugins != null) {
            if (!(rawPlugins instanceof TomlTable table)) {
                throw new JkBuildParseException(at + ".plugins must be a table keyed by plugin name: plugins = {"
                        + " ErrorProne = { options = […] } }");
            }
            for (String name : table.keySet()) {
                String where = at.substring(0, at.length() - 1) + ".plugins." + name + "]";
                if (!(table.get(List.of(name)) instanceof TomlTable plugin)) {
                    throw new JkBuildParseException(where + " must be a table: { options = […] }");
                }
                for (String key : plugin.keySet()) {
                    if (!JAVAC_PLUGIN_KEYS.contains(key)) {
                        throw new JkBuildParseException(where + " unknown key `" + key + "` — expected one of: "
                                + String.join(", ", JAVAC_PLUGIN_KEYS));
                    }
                }
                plugins.put(name, stringArray(plugin.get(List.of("options")), where + ".options"));
            }
        }
        List<String> args = stringArray(javac.get(List.of("args")), at + ".args");
        return new JavacConfig(plugins, args, test);
    }

    /** {@code raw} as an array of strings; absent is empty. */
    private static List<String> stringArray(@Nullable Object raw, String where) {
        if (raw == null) return List.of();
        if (!(raw instanceof TomlArray arr)) throw new JkBuildParseException(where + " must be an array of strings");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            if (!(arr.get(i) instanceof String s)) {
                throw new JkBuildParseException(where + " must be an array of strings");
            }
            out.add(s);
        }
        return out;
    }

    /** The keys {@code [audit]} may carry. */
    public static final List<String> AUDIT_KEYS = List.of("ignore");

    /** The keys one {@code [audit] ignore} entry may carry. */
    public static final List<String> AUDIT_IGNORE_KEYS = List.of("id", "reason", "until");

    /**
     * {@code [audit] ignore} — advisories the audit reports without gating on:
     *
     * <pre>
     * [audit]
     * ignore = [
     *   { id = "GHSA-xxxx-xxxx-xxxx", reason = "test-only dependency; not reachable", until = "2026-12-31" },
     * ]
     * </pre>
     *
     * Every entry needs its {@code reason}; {@code until} is optional and is an ISO date
     * ({@code YYYY-MM-DD}, quoted or a bare TOML date). Unknown keys fail the parse, so a misspelt
     * {@code untill} cannot silently turn a dated ignore into a permanent one.
     */
    static List<JkBuild.AuditIgnore> parseAuditIgnores(TomlTable root) {
        Object raw = root.get(List.of("audit"));
        if (raw == null) return List.of();
        if (!(raw instanceof TomlTable audit)) {
            throw new JkBuildParseException(
                    "[audit] must be a table: [audit] ignore = [{ id = \"GHSA-…\", reason = \"…\" }]");
        }
        for (String key : audit.keySet()) {
            if (!AUDIT_KEYS.contains(key)) {
                throw new JkBuildParseException(
                        "[audit] unknown key `" + key + "` — expected one of: " + String.join(", ", AUDIT_KEYS));
            }
        }
        Object rawIgnore = audit.get(List.of("ignore"));
        if (rawIgnore == null) return List.of();
        if (!(rawIgnore instanceof TomlArray entries)) {
            throw new JkBuildParseException(
                    "[audit].ignore must be an array of tables: ignore = [{ id = \"GHSA-…\", reason = \"…\" }]");
        }
        List<JkBuild.AuditIgnore> out = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            String where = "[audit].ignore[" + i + "]";
            if (!(entries.get(i) instanceof TomlTable entry)) {
                throw new JkBuildParseException(where + " must be a table: { id = \"GHSA-…\", reason = \"…\" }");
            }
            out.add(parseAuditIgnore(entry, where));
        }
        return List.copyOf(out);
    }

    private static JkBuild.AuditIgnore parseAuditIgnore(TomlTable entry, String where) {
        for (String key : entry.keySet()) {
            if (!AUDIT_IGNORE_KEYS.contains(key)) {
                throw new JkBuildParseException(where + " unknown key `" + key + "` — expected one of: "
                        + String.join(", ", AUDIT_IGNORE_KEYS));
            }
        }
        if (!(entry.get(List.of("id")) instanceof String id) || id.isBlank()) {
            throw new JkBuildParseException(where + " needs id = \"GHSA-…\" (the advisory id the audit reports)");
        }
        String at = "[audit].ignore " + id.trim();
        if (!(entry.get(List.of("reason")) instanceof String reason) || reason.isBlank()) {
            throw new JkBuildParseException(at + " needs a reason — an ignore without one cannot be reviewed");
        }
        LocalDate until = null;
        Object rawUntil = entry.get(List.of("until"));
        if (rawUntil instanceof LocalDate date) {
            until = date;
        } else if (rawUntil instanceof String text) {
            try {
                until = LocalDate.parse(text.trim());
            } catch (DateTimeParseException e) {
                throw new JkBuildParseException(at + " until must be an ISO date (YYYY-MM-DD), got `" + text + "`");
            }
        } else if (rawUntil != null) {
            throw new JkBuildParseException(at + " until must be an ISO date (YYYY-MM-DD)");
        }
        return new JkBuild.AuditIgnore(id.trim(), reason.trim(), until);
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
