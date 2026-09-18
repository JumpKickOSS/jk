// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.manifest;

import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PluginConfig;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.VersionSelector;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Evaluates declarative plugin contributions (platforms, compiler args, Kotlin plugins) against
 * one project. No plugin code runs; contributes only when the owned table is present and
 * optional conditions hold.
 */
public final class PluginContributions {

    private PluginContributions() {}

    /** A parse-time platform (BOM) injection: the module plus its exact version pin. */
    public record PlatformDep(String module, String version) {}

    /** A platform BOM a plugin's table implies: the module, its version and the table that brought it. */
    public record ImpliedPlatform(String module, String version, String table) {}

    /** A compile-time Kotlin compiler plugin: BTA id, jar coordinate pieces, plugin options. */
    public record KotlinPluginUse(String id, String group, String artifact, String version, List<String> options) {}

    /**
     * The platform dependencies every present plugin contributes — evaluated at parse time
     * (before resolution; the manifest loader already rejected classpath-has conditions here).
     */
    public static List<ImpliedPlatform> platformDependencies(
            Project project, boolean nativeDeclared, Map<String, PluginConfig> pluginConfigs) {
        return platformDependencies(project, nativeDeclared, pluginConfigs, PluginTableRegistry.manifests());
    }

    /** As above against an explicit manifest set (the parser passes built-ins + resolved third-party). */
    public static List<ImpliedPlatform> platformDependencies(
            Project project,
            boolean nativeDeclared,
            Map<String, PluginConfig> pluginConfigs,
            List<PluginDescriptor> manifests) {
        List<ImpliedPlatform> out = new ArrayList<>();
        for (PluginDescriptor manifest : manifests) {
            PluginConfig config = pluginConfigs.get(manifest.id());
            if (config == null) continue;
            for (PluginDescriptor.PlatformDependency dep :
                    manifest.contributions().platformDependencies()) {
                if (!holds(dep.when(), config, project, nativeDeclared, null, manifest.id())) continue;
                String coordinate = Interpolation.resolve(dep.coordinate(), config, project, null);
                PlatformDep split = splitCoordinate(coordinate, manifest.id());
                out.add(new ImpliedPlatform(split.module(), split.version(), manifest.table()));
            }
        }
        return out;
    }

    /** The javac args every present plugin contributes, conditions evaluated against {@code classpathModules}. */
    public static List<String> javacArgs(JkBuild build, @Nullable Path moduleDir, Set<String> classpathModules) {
        return compilerArgs(build, moduleDir, classpathModules, PluginDescriptor.CompilerArgs::javac);
    }

    /** The kotlinc args every present plugin contributes. */
    public static List<String> kotlinArgs(JkBuild build, @Nullable Path moduleDir, Set<String> classpathModules) {
        return compilerArgs(build, moduleDir, classpathModules, PluginDescriptor.CompilerArgs::kotlin);
    }

    /** The groovyc args every present plugin contributes (e.g. grails' {@code --parameters}). */
    public static List<String> groovyArgs(JkBuild build, @Nullable Path moduleDir, Set<String> classpathModules) {
        return compilerArgs(build, moduleDir, classpathModules, PluginDescriptor.CompilerArgs::groovy);
    }

    /** One resolved plugin-contributed module input root (relative dir + resource/source kind). */
    public record SourceRoot(String dir, boolean resource) {}

    /**
     * The active plugins' {@code [[contribute.source-roots]]} entries with conditions evaluated —
     * module-relative dirs that join the module's roots (compile inputs, resource copy, IDE/BSP,
     * fingerprints) exactly like the conventional layout dirs. Evaluated before resolution
     * (classpath-has was rejected at manifest load).
     */
    public static List<SourceRoot> sourceRoots(JkBuild build, @Nullable Path moduleDir) {
        List<SourceRoot> out = new ArrayList<>();
        for (PluginDescriptor manifest : PluginTableRegistry.manifestsFor(moduleDir, build.plugins())) {
            PluginConfig config = build.pluginConfig(manifest.id()).orElse(null);
            if (config == null) continue;
            for (PluginDescriptor.SourceRoot root : manifest.contributions().sourceRoots()) {
                if (!holds(
                        root.when(),
                        config,
                        build.project(),
                        build.nativeConfigOpt().isPresent(),
                        null,
                        manifest.id())) {
                    continue;
                }
                out.add(new SourceRoot(root.dir(), root.resource()));
            }
        }
        return out;
    }

    /**
     * The KSP processor options ({@code key=value}) every present plugin contributes — handed to
     * the KSP round as {@code -processor-options} (Hilt's superclass-validation toggle et al.).
     */
    public static List<String> kspOptions(JkBuild build, @Nullable Path moduleDir, Set<String> classpathModules) {
        return compilerArgs(build, moduleDir, classpathModules, PluginDescriptor.CompilerArgs::ksp);
    }

    /**
     * The Kotlin compiler plugins every present plugin contributes. {@code kotlinVersion} feeds
     * {@code ${kotlin.version}} so plugin jars stay lockstep with the compiler actually used.
     */
    public static List<KotlinPluginUse> kotlinPlugins(
            JkBuild build, @Nullable Path moduleDir, String kotlinVersion, Set<String> classpathModules) {
        List<KotlinPluginUse> out = new ArrayList<>();
        for (PluginDescriptor manifest : PluginTableRegistry.manifestsFor(moduleDir, build.plugins())) {
            PluginConfig config = build.pluginConfigs().get(manifest.id());
            if (config == null) continue;
            for (PluginDescriptor.KotlinPlugin plugin : manifest.contributions().kotlinPlugins()) {
                if (!holds(
                        plugin.when(),
                        config,
                        build.project(),
                        build.nativeConfigOpt().isPresent(),
                        classpathModules,
                        manifest.id())) {
                    continue;
                }
                String coordinate = Interpolation.resolve(plugin.coordinate(), config, build.project(), kotlinVersion);
                PlatformDep c = splitCoordinate(coordinate, manifest.id());
                int colon = c.module().indexOf(':');
                List<String> options = new ArrayList<>(plugin.options().size());
                for (String opt : plugin.options()) {
                    options.add(Interpolation.resolve(opt, config, build.project(), kotlinVersion));
                }
                out.add(new KotlinPluginUse(
                        plugin.id(),
                        c.module().substring(0, colon),
                        c.module().substring(colon + 1),
                        c.version(),
                        options));
            }
        }
        return out;
    }

    private static List<String> compilerArgs(
            JkBuild build,
            @Nullable Path moduleDir,
            Set<String> classpathModules,
            Function<PluginDescriptor.CompilerArgs, List<String>> lane) {
        List<String> out = new ArrayList<>();
        for (PluginDescriptor manifest : PluginTableRegistry.manifestsFor(moduleDir, build.plugins())) {
            PluginConfig config = build.pluginConfigs().get(manifest.id());
            if (config == null) continue;
            for (PluginDescriptor.CompilerArgs args : manifest.contributions().compilerArgs()) {
                if (!holds(
                        args.when(),
                        config,
                        build.project(),
                        build.nativeConfigOpt().isPresent(),
                        classpathModules,
                        manifest.id())) {
                    continue;
                }
                for (String arg : lane.apply(args)) {
                    out.add(Interpolation.resolve(arg, config, build.project(), null));
                }
            }
        }
        return out;
    }

    /**
     * The {@code native-image} arguments the active plugins contribute, in declaration order.
     *
     * <p>Mostly class-initialization policy: which types may be initialized while the image is
     * built. Reachability metadata does not express it and static analysis cannot infer it, so
     * without this a framework application fails one class at a time and the user reassembles a
     * list their framework's own build plugin already knows.
     *
     * <p>The caller places these before {@code [native] args} so a user can override.
     */
    public static List<String> nativeArgs(JkBuild build, Path moduleDir) {
        List<String> out = new ArrayList<>();
        for (PluginDescriptor manifest : PluginTableRegistry.manifestsFor(moduleDir, build.plugins())) {
            PluginConfig config = build.pluginConfigs().get(manifest.id());
            if (config == null) continue;
            for (PluginDescriptor.NativeArgs contributed :
                    manifest.contributions().nativeArgs()) {
                if (!holds(
                        contributed.when(),
                        config,
                        build.project(),
                        build.nativeConfigOpt().isPresent(),
                        null,
                        manifest.id())) {
                    continue;
                }
                for (String arg : contributed.args()) {
                    out.add(Interpolation.resolve(arg, config, build.project(), null));
                }
            }
        }
        return out;
    }

    /** One resolved packager-dependency: fetch {@code module:version}, hand it over as {@code artifact}. */
    public record PackagerDep(String artifact, String module, String version) {}

    /**
     * One resolved step-dependency, handed to the step as {@code artifact}: a Maven coordinate
     * spec ({@code group:artifact:version[:classifier]}, {@code transitive} = the runtime closure),
     * a provisioned SDK component ({@code sdkComponent}/{@code sdkPath}), or a file at an http(s)
     * {@code url} the engine fetches once into the store.
     *
     * <p>{@code managedBy} / {@code with} mirror the manifest: BOM-aligned multi-root tool graphs.
     * {@code forSteps} names the steps or packagers that read the tool; empty means all of them.
     */
    public record StepDep(
            String artifact,
            @Nullable String coordinateSpec,
            boolean transitive,
            @Nullable String sdkComponent,
            @Nullable String sdkPath,
            @Nullable String managedBy,
            List<String> with,
            List<String> forSteps,
            @Nullable String url) {

        public StepDep {
            with = with == null ? List.of() : List.copyOf(with);
            forSteps = forSteps == null ? List.of() : List.copyOf(forSteps);
        }

        public StepDep(String artifact, @Nullable String coordinateSpec) {
            this(artifact, coordinateSpec, false, null, null, null, List.of(), List.of(), null);
        }

        public StepDep(
                String artifact,
                @Nullable String coordinateSpec,
                boolean transitive,
                @Nullable String sdkComponent,
                @Nullable String sdkPath) {
            this(artifact, coordinateSpec, transitive, sdkComponent, sdkPath, null, List.of(), List.of(), null);
        }

        /** A file at {@code url}, handed to the steps {@code forSteps} name as {@code artifact}. */
        public static StepDep atUrl(String artifact, String url, List<String> forSteps) {
            return new StepDep(artifact, null, false, null, null, null, List.of(), forSteps, url);
        }

        /** True when {@code consumer} — a step or packager name — receives this tool. */
        public boolean reaches(String consumer) {
            return forSteps.isEmpty() || forSteps.contains(consumer);
        }
    }

    /**
     * The active plugins' {@code [[contribute.step-dependency]]} entries with conditions evaluated
     * and coordinates interpolated — the engine fetches these into the cache (never into the
     * project's dependency graph) and hands them to the step worker by name. This lane, and only
     * this lane, is rendered into step and packager action keys. {@code platformPins} is the
     * lock's {@link cc.jumpkick.lock.Lockfile#platformPins()} — see {@link #toolConfig}.
     */
    public static List<StepDep> stepDependencies(
            JkBuild build, @Nullable Path moduleDir, Map<String, String> platformPins) {
        return toolDependencies(
                build, moduleDir, platformPins, "step-dependency", PluginDescriptor.Contributions::stepDependencies);
    }

    /**
     * The active plugins' {@code [[contribute.command-dependency]]} entries with conditions
     * evaluated and coordinates interpolated — tools only plugin commands read. The engine fetches
     * these when a command runs; they join no step's or packager's action key and are never
     * provisioned by a build.
     */
    public static List<StepDep> commandDependencies(
            JkBuild build, @Nullable Path moduleDir, Map<String, String> platformPins) {
        return toolDependencies(
                build,
                moduleDir,
                platformPins,
                "command-dependency",
                PluginDescriptor.Contributions::commandDependencies);
    }

    private static List<StepDep> toolDependencies(
            JkBuild build,
            @Nullable Path moduleDir,
            Map<String, String> platformPins,
            String kind,
            Function<PluginDescriptor.Contributions, List<PluginDescriptor.StepDependency>> lane) {
        List<StepDep> out = new ArrayList<>();
        for (PluginDescriptor manifest : PluginTableRegistry.manifestsFor(moduleDir, build.plugins())) {
            PluginConfig declared = build.pluginConfig(manifest.id()).orElse(null);
            if (declared == null) continue;
            PluginConfig config = toolConfig(manifest, declared, build, platformPins);
            for (PluginDescriptor.StepDependency sd : lane.apply(manifest.contributions())) {
                if (!holds(
                        sd.when(),
                        config,
                        build.project(),
                        build.nativeConfigOpt().isPresent(),
                        null,
                        manifest.id())) {
                    continue;
                }
                if (!sd.perEntry()) {
                    StepDep dep = toolDependency(sd, config, build.project(), manifest.id(), kind, null);
                    if (dep != null) out.add(dep);
                    continue;
                }
                // per-entry: the one declaration, once per [<table>.<name>] entry in its scope; an
                // entry that leaves a key the coordinate names unset declares no such tool, unless
                // the key inherits and the table sets it.
                for (var entry : config.entries().entrySet()) {
                    Interpolation.Entry scope =
                            new Interpolation.Entry(entry.getKey(), entryValues(manifest, config, entry.getValue()));
                    if (!Interpolation.entryProvides(sd.coordinate(), scope)) continue;
                    if (!Interpolation.entryProvides(sd.url(), scope)) continue;
                    StepDep dep = toolDependency(sd, config, build.project(), manifest.id(), kind, scope);
                    if (dep != null) out.add(dep);
                }
            }
        }
        return out;
    }

    /**
     * An entry's values, with the table's value under every entry-schema key marked {@code
     * inherit} that the entry leaves unset: the run's own release over the table's shared one.
     */
    static Map<String, Object> entryValues(PluginDescriptor manifest, PluginConfig config, Map<String, Object> own) {
        Map<String, PluginDescriptor.SchemaKey> keys =
                manifest.entrySchema() == null ? null : manifest.subSchemas().get(manifest.entrySchema());
        if (keys == null) return own;
        Map<String, Object> out = new LinkedHashMap<>();
        for (PluginDescriptor.SchemaKey key : keys.values()) {
            Object shared = config.values().get(key.name());
            if (key.inherit() && !own.containsKey(key.name()) && shared != null) out.put(key.name(), shared);
        }
        out.putAll(own);
        return out;
    }

    /**
     * One declaration resolved against the table (and, for a per-entry tool, one entry); null for
     * a {@code url} entry whose value is not an http(s) URL — a module file, or a key left unset —
     * and for a {@code coordinate} over a key left unset or an empty list. A coordinate that is one
     * {@code ${config.<key>}} or {@code ${entry.<key>}} over a string-list key is every coordinate
     * of the list: the first the root, the rest {@code with} it in the same closure.
     */
    private static @Nullable StepDep toolDependency(
            PluginDescriptor.StepDependency sd,
            PluginConfig config,
            Project project,
            String pluginId,
            String kind,
            Interpolation.@Nullable Entry entry) {
        String artifact = Interpolation.resolve(sd.artifact(), config, project, null, entry);
        List<String> forSteps = new ArrayList<>(sd.forSteps().size());
        for (String step : sd.forSteps()) forSteps.add(Interpolation.resolve(step, config, project, null, entry));
        if (sd.url() != null) {
            if (!Interpolation.configProvides(sd.url(), config)) return null;
            String url = Interpolation.resolve(sd.url(), config, project, null, entry);
            return isHttpUrl(url) ? StepDep.atUrl(artifact, url, forSteps) : null;
        }
        if (sd.sdkComponent() != null) {
            String component = Interpolation.resolve(sd.sdkComponent(), config, project, null, entry);
            return new StepDep(artifact, null, false, component, sd.sdkPath(), null, List.of(), forSteps, null);
        }
        String template = Objects.requireNonNull(sd.coordinate(), "coordinate");
        if (!Interpolation.configProvides(template, config)) return null;
        List<String> roots = Interpolation.resolveAll(template, config, project, null, entry);
        if (roots.isEmpty()) return null;
        for (String root : roots) {
            String[] parts = root.split(":");
            if (parts.length < 3 || parts.length > 4) {
                throw new JkBuildParseException("[" + pluginId + "] " + kind + " coordinate must be"
                        + " \"group:artifact:version[:classifier]\" — got: " + root);
            }
        }
        String coordinate = roots.getFirst();
        String managedBy =
                sd.managedBy() == null ? null : Interpolation.resolve(sd.managedBy(), config, project, null, entry);
        List<String> with = new ArrayList<>(roots.subList(1, roots.size()));
        for (String w : sd.with()) {
            String resolved = Interpolation.resolve(w, config, project, null, entry);
            String[] wp = resolved.split(":");
            if (wp.length < 3 || wp.length > 4) {
                throw new JkBuildParseException("[" + pluginId + "] " + kind + " with entry must be"
                        + " \"group:artifact:version[:classifier]\" — got: " + resolved);
            }
            with.add(resolved);
        }
        return new StepDep(artifact, coordinate, sd.transitive(), null, null, managedBy, with, forSteps, null);
    }

    /** True for an {@code http://} or {@code https://} value. */
    public static boolean isHttpUrl(String value) {
        return value.startsWith("https://") || value.startsWith("http://");
    }

    /**
     * The active plugins' {@code [[contribute.provided-classpath]]} entries with conditions
     * evaluated: names of declared step-dependency artifacts whose resolved paths join the
     * module's COMPILE classpath (PROVIDED posture — compile-only, never runtime/packaging).
     */
    public static List<String> providedClasspath(JkBuild build, Path moduleDir) {
        List<String> out = new ArrayList<>();
        for (PluginDescriptor manifest : PluginTableRegistry.manifestsFor(moduleDir, build.plugins())) {
            PluginConfig config = build.pluginConfig(manifest.id()).orElse(null);
            if (config == null) continue;
            for (PluginDescriptor.ProvidedClasspath pc :
                    manifest.contributions().providedClasspath()) {
                if (!holds(
                        pc.when(),
                        config,
                        build.project(),
                        build.nativeConfigOpt().isPresent(),
                        null,
                        manifest.id())) {
                    continue;
                }
                out.add(pc.dependency());
            }
        }
        return out;
    }

    /**
     * The {@code [contribute.resolution] jvm-environment} for resolve/lock ({@code "android"} or
     * {@code "standard-jvm"}) — the GMM environment KMP runtime variants select.
     *
     * <p>For a single module: the active plugin that declares one wins; two conflicting
     * declarations are a config error. Default {@code standard-jvm}.
     *
     * <p>For a <strong>workspace root</strong> (often no {@code [android]} on the aggregator
     * itself): if <em>any</em> member module selects {@code android}, the unified workspace graph
     * uses {@code android}. Otherwise AndroidX multiplatform roots resolve to {@code -jvmstubs}
     * and PubGrub cannot align suites that expect the android line (Now in Android).
     */
    public static String jvmEnvironment(JkBuild build, Path moduleDir) {
        String selected = jvmEnvironmentLocal(build, moduleDir);
        if ("android".equals(selected)) return selected;
        if (build.isWorkspaceRoot()) {
            try {
                for (var entry : WorkspaceLoader.loadModules(moduleDir, build).entrySet()) {
                    if ("android".equals(jvmEnvironmentLocal(entry.getValue(), entry.getKey()))) {
                        return "android";
                    }
                }
            } catch (IOException ignored) {
                // Fall through to the root's selection / default.
            }
        }
        return selected;
    }

    /** Plugin-local environment for one module only (no workspace walk). */
    private static String jvmEnvironmentLocal(JkBuild build, Path moduleDir) {
        String selected = null;
        String selectedBy = null;
        for (PluginDescriptor manifest : PluginTableRegistry.manifestsFor(moduleDir, build.plugins())) {
            if (build.pluginConfig(manifest.id()).isEmpty()) continue;
            String env = manifest.contributions().jvmEnvironment();
            if (env == null) continue;
            if (selected != null && !selected.equals(env)) {
                throw new IllegalStateException("[" + selectedBy + "] and [" + manifest.id()
                        + "] declare conflicting resolution jvm-environments (" + selected + " vs " + env + ")");
            }
            selected = env;
            selectedBy = manifest.id();
        }
        return selected == null ? "standard-jvm" : selected;
    }

    /**
     * The active plugins' {@code [[contribute.packager-dependency]]} entries with conditions
     * evaluated and coordinates interpolated — the engine fetches these (never into the project's
     * dependency graph) and hands them to the packager worker by name. {@code platformPins} is
     * the lock's {@link cc.jumpkick.lock.Lockfile#platformPins()} — see {@link #toolConfig}.
     */
    public static List<PackagerDep> packagerDependencies(
            JkBuild build, Path moduleDir, Map<String, String> platformPins) {
        List<PackagerDep> out = new ArrayList<>();
        for (PluginDescriptor manifest : PluginTableRegistry.manifestsFor(moduleDir, build.plugins())) {
            PluginConfig declared = build.pluginConfig(manifest.id()).orElse(null);
            if (declared == null) continue;
            PluginConfig config = toolConfig(manifest, declared, build, platformPins);
            for (PluginDescriptor.PackagerDependency pd :
                    manifest.contributions().packagerDependencies()) {
                if (!holds(
                        pd.when(),
                        config,
                        build.project(),
                        build.nativeConfigOpt().isPresent(),
                        null,
                        manifest.id())) {
                    continue;
                }
                String coordinate = Interpolation.resolve(pd.coordinate(), config, build.project(), null);
                PlatformDep split = splitCoordinate(coordinate, manifest.id());
                out.add(new PackagerDep(pd.artifact(), split.module(), split.version()));
            }
        }
        return out;
    }

    /**
     * The plugin's table as a tool coordinate reads it. A key that selects the plugin's platform
     * line — the {@code ${config.<key>}} version segment of a {@code
     * [[contribute.platform-dependency]]} — holds a selector ({@code =4.1.1}, {@code ^4},
     * {@code latest}); no such string is a fetchable version, and the version that matters is
     * the one the lock resolved the platform to. That locked version replaces the selector here,
     * so the loader a packager fetches is the Boot the lock pinned. Without a pin for the platform
     * (no lock yet, or a BOM that manages nothing locked) the selector's anchor version stands in.
     */
    private static PluginConfig toolConfig(
            PluginDescriptor manifest, PluginConfig config, JkBuild build, Map<String, String> platformPins) {
        Map<String, Object> values = null;
        for (PluginDescriptor.PlatformDependency dep : manifest.contributions().platformDependencies()) {
            if (!holds(
                    dep.when(), config, build.project(), build.nativeConfigOpt().isPresent(), null, manifest.id())) {
                continue;
            }
            String[] parts = dep.coordinate().split(":");
            if (parts.length != 3) continue;
            String key = configKey(parts[2]);
            if (key == null || !(config.values().get(key) instanceof String selector)) continue;
            String pinned = platformPins.get(parts[0] + ":" + parts[1]);
            String version = pinned != null ? pinned : anchor(selector);
            if (version.equals(selector)) continue;
            if (values == null) values = new LinkedHashMap<>(config.values());
            values.put(key, version);
        }
        return values == null ? config : new PluginConfig(config.id(), values);
    }

    /** The {@code key} of a segment that is exactly {@code ${config.<key>}}, else null. */
    private static @Nullable String configKey(String segment) {
        String open = "${config.";
        if (!segment.startsWith(open) || !segment.endsWith("}")) return null;
        return segment.substring(open.length(), segment.length() - 1);
    }

    /**
     * The version a selector is anchored on ({@code =4.1.1} → {@code 4.1.1}, {@code ^4} →
     * {@code 4}); {@code latest} and the other anchorless forms are returned as written.
     */
    private static String anchor(String selector) {
        return switch (VersionSelector.parse(selector)) {
            case VersionSelector.Exact e -> e.version();
            case VersionSelector.Caret c -> c.version();
            case VersionSelector.Tilde t -> t.version();
            default -> selector;
        };
    }

    /** One predicate, or unconditional when {@code when} is null. */
    private static boolean holds(
            PluginDescriptor.@Nullable Condition when,
            PluginConfig config,
            Project project,
            boolean nativeDeclared,
            @Nullable Set<String> classpathModules,
            String pluginId) {
        if (when == null) return true;
        return switch (when) {
            case PluginDescriptor.Condition.ClasspathHas c -> {
                if (classpathModules == null) {
                    throw new JkBuildParseException("[" + pluginId + "] contribution uses classpath-has on a"
                            + " path evaluated before resolution");
                }
                yield classpathModules.contains(c.module());
            }
            case PluginDescriptor.Condition.ConfigEquals c ->
                c.equals().equals(String.valueOf(config.values().get(c.key())));
            case PluginDescriptor.Condition.NativeDeclared ignored -> nativeDeclared;
            case PluginDescriptor.Condition.KotlinProject ignored -> project.isKotlin();
        };
    }

    private static PlatformDep splitCoordinate(String coordinate, String pluginId) {
        String[] parts = coordinate.split(":");
        if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
            throw new JkBuildParseException("[" + pluginId + "] contribution coordinate must be"
                    + " \"group:artifact:version\" — got: " + coordinate);
        }
        return new PlatformDep(parts[0] + ":" + parts[1], parts[2]);
    }
}
