// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.manifest;

import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.PluginConfig;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Evaluates declarative plugin contributions (platforms, compiler args, Kotlin plugins) against
 * one project. No plugin code runs; contributes only when the owned table is present and
 * optional conditions hold.
 */
public final class PluginContributions {

    private PluginContributions() {}

    /** A parse-time platform (BOM) injection: the module plus its exact version pin. */
    public record PlatformDep(String module, String version) {}

    /** A compile-time Kotlin compiler plugin: BTA id, jar coordinate pieces, plugin options. */
    public record KotlinPluginUse(String id, String group, String artifact, String version, List<String> options) {}

    /**
     * The platform dependencies every present plugin contributes — evaluated at parse time
     * (before resolution; the manifest loader already rejected classpath-has conditions here).
     */
    public static List<PlatformDep> platformDependencies(
            JkBuild.Project project, boolean nativeDeclared, Map<String, PluginConfig> pluginConfigs) {
        return platformDependencies(project, nativeDeclared, pluginConfigs, PluginTableRegistry.manifests());
    }

    /** As above against an explicit manifest set (the parser passes built-ins + resolved third-party). */
    public static List<PlatformDep> platformDependencies(
            JkBuild.Project project,
            boolean nativeDeclared,
            Map<String, PluginConfig> pluginConfigs,
            List<PluginDescriptor> manifests) {
        List<PlatformDep> out = new ArrayList<>();
        for (PluginDescriptor manifest : manifests) {
            PluginConfig config = pluginConfigs.get(manifest.id());
            if (config == null) continue;
            for (PluginDescriptor.PlatformDependency dep :
                    manifest.contributions().platformDependencies()) {
                if (!holds(dep.when(), config, project, nativeDeclared, null, manifest.id())) continue;
                String coordinate = Interpolation.resolve(dep.coordinate(), config, project, null);
                out.add(splitCoordinate(coordinate, manifest.id()));
            }
        }
        return out;
    }

    /** The javac args every present plugin contributes, conditions evaluated against {@code classpathModules}. */
    public static List<String> javacArgs(JkBuild build, Path moduleDir, Set<String> classpathModules) {
        return compilerArgs(build, moduleDir, classpathModules, PluginDescriptor.CompilerArgs::javac);
    }

    /** The kotlinc args every present plugin contributes. */
    public static List<String> kotlinArgs(JkBuild build, Path moduleDir, Set<String> classpathModules) {
        return compilerArgs(build, moduleDir, classpathModules, PluginDescriptor.CompilerArgs::kotlin);
    }

    /** The groovyc args every present plugin contributes (e.g. grails' {@code --parameters}). */
    public static List<String> groovyArgs(JkBuild build, Path moduleDir, Set<String> classpathModules) {
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
    public static List<SourceRoot> sourceRoots(JkBuild build, Path moduleDir) {
        List<SourceRoot> out = new ArrayList<>();
        for (PluginDescriptor manifest : PluginTableRegistry.manifestsFor(moduleDir, build.plugins())) {
            PluginConfig config = build.pluginConfig(manifest.id()).orElse(null);
            if (config == null) continue;
            for (PluginDescriptor.SourceRoot root : manifest.contributions().sourceRoots()) {
                if (!holds(
                        root.when(),
                        config,
                        build.project(),
                        build.nativeConfig().isPresent(),
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
    public static List<String> kspOptions(JkBuild build, Path moduleDir, Set<String> classpathModules) {
        return compilerArgs(build, moduleDir, classpathModules, PluginDescriptor.CompilerArgs::ksp);
    }

    /**
     * The Kotlin compiler plugins every present plugin contributes. {@code kotlinVersion} feeds
     * {@code ${kotlin.version}} so plugin jars stay lockstep with the compiler actually used.
     */
    public static List<KotlinPluginUse> kotlinPlugins(
            JkBuild build, Path moduleDir, String kotlinVersion, Set<String> classpathModules) {
        List<KotlinPluginUse> out = new ArrayList<>();
        for (PluginDescriptor manifest : PluginTableRegistry.manifestsFor(moduleDir, build.plugins())) {
            PluginConfig config = build.pluginConfigs().get(manifest.id());
            if (config == null) continue;
            for (PluginDescriptor.KotlinPlugin plugin : manifest.contributions().kotlinPlugins()) {
                if (!holds(
                        plugin.when(),
                        config,
                        build.project(),
                        build.nativeConfig().isPresent(),
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
            Path moduleDir,
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
                        build.nativeConfig().isPresent(),
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
                        build.nativeConfig().isPresent(),
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
     * spec ({@code group:artifact:version[:classifier]}, {@code transitive} = the runtime closure)
     * or a provisioned SDK component ({@code sdkComponent}/{@code sdkPath}).
     *
     * <p>{@code managedBy} / {@code with} mirror the manifest: BOM-aligned multi-root tool graphs.
     */
    public record StepDep(
            String artifact,
            String coordinateSpec,
            boolean transitive,
            String sdkComponent,
            String sdkPath,
            String managedBy,
            List<String> with) {

        public StepDep {
            with = with == null ? List.of() : List.copyOf(with);
        }

        public StepDep(String artifact, String coordinateSpec) {
            this(artifact, coordinateSpec, false, null, null, null, List.of());
        }

        public StepDep(
                String artifact, String coordinateSpec, boolean transitive, String sdkComponent, String sdkPath) {
            this(artifact, coordinateSpec, transitive, sdkComponent, sdkPath, null, List.of());
        }
    }

    /**
     * The active plugins' {@code [[contribute.step-dependency]]} entries with conditions evaluated
     * and coordinates interpolated — the engine fetches these into the cache (never into the
     * project's dependency graph) and hands them to the step worker by name.
     */
    public static List<StepDep> stepDependencies(JkBuild build, Path moduleDir) {
        List<StepDep> out = new ArrayList<>();
        for (PluginDescriptor manifest : PluginTableRegistry.manifestsFor(moduleDir, build.plugins())) {
            PluginConfig config = build.pluginConfig(manifest.id()).orElse(null);
            if (config == null) continue;
            for (PluginDescriptor.StepDependency sd : manifest.contributions().stepDependencies()) {
                if (!holds(
                        sd.when(), config, build.project(), build.nativeConfig().isPresent(), null, manifest.id())) {
                    continue;
                }
                if (sd.sdkComponent() != null) {
                    String component = Interpolation.resolve(sd.sdkComponent(), config, build.project(), null);
                    out.add(new StepDep(sd.artifact(), null, false, component, sd.sdkPath(), null, List.of()));
                    continue;
                }
                String coordinate = Interpolation.resolve(sd.coordinate(), config, build.project(), null);
                String[] parts = coordinate.split(":");
                if (parts.length < 3 || parts.length > 4) {
                    throw new JkBuildParseException("[" + manifest.id() + "] step-dependency coordinate must be"
                            + " \"group:artifact:version[:classifier]\" — got: " + coordinate);
                }
                String managedBy = sd.managedBy() == null
                        ? null
                        : Interpolation.resolve(sd.managedBy(), config, build.project(), null);
                List<String> with = new ArrayList<>();
                for (String w : sd.with()) {
                    String resolved = Interpolation.resolve(w, config, build.project(), null);
                    String[] wp = resolved.split(":");
                    if (wp.length < 3 || wp.length > 4) {
                        throw new JkBuildParseException("[" + manifest.id() + "] step-dependency with entry must be"
                                + " \"group:artifact:version[:classifier]\" — got: " + resolved);
                    }
                    with.add(resolved);
                }
                out.add(new StepDep(sd.artifact(), coordinate, sd.transitive(), null, null, managedBy, with));
            }
        }
        return out;
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
                        pc.when(), config, build.project(), build.nativeConfig().isPresent(), null, manifest.id())) {
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
                for (var entry : cc.jumpkick.config.WorkspaceLoader.loadModules(moduleDir, build)
                        .entrySet()) {
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
     * dependency graph) and hands them to the packager worker by name.
     */
    public static List<PackagerDep> packagerDependencies(JkBuild build, Path moduleDir) {
        List<PackagerDep> out = new ArrayList<>();
        for (PluginDescriptor manifest : PluginTableRegistry.manifestsFor(moduleDir, build.plugins())) {
            PluginConfig config = build.pluginConfig(manifest.id()).orElse(null);
            if (config == null) continue;
            for (PluginDescriptor.PackagerDependency pd :
                    manifest.contributions().packagerDependencies()) {
                if (!holds(
                        pd.when(), config, build.project(), build.nativeConfig().isPresent(), null, manifest.id())) {
                    continue;
                }
                String coordinate = Interpolation.resolve(pd.coordinate(), config, build.project(), null);
                PlatformDep split = splitCoordinate(coordinate, manifest.id());
                out.add(new PackagerDep(pd.artifact(), split.module(), split.version()));
            }
        }
        return out;
    }

    /** One predicate, or unconditional when {@code when} is null. */
    private static boolean holds(
            PluginDescriptor.Condition when,
            PluginConfig config,
            JkBuild.Project project,
            boolean nativeDeclared,
            Set<String> classpathModules,
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
