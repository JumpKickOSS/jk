// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Parsed contents of a project's {@code jk.toml}: one nested record per block, plus the derived
 * answers that read across blocks ({@link #nativeMode} reconciles {@code [application] native} with
 * {@code [native] enabled}; {@link #graal} falls back only when native is on). The {@code [project]}
 * block is its own top-level {@link Project}, as are {@link Layout}, {@link SourcesMode} and
 * {@link ProjectInherit} — the identity vocabulary is referenced far more widely than this record is
 * and had no reason to be reached through it.
 */
public record JkBuild(
        Project project,
        Dependencies dependencies,
        List<RepositorySpec> repositories,
        Profiles profiles,
        Features features,
        Workspace workspace,
        Map<String, String> manifest,
        List<PluginDeclaration> plugins,
        Optional<Application> application,
        Optional<NativeConfig> nativeConfig,
        Map<String, PluginConfig> pluginConfigs,
        Build build,
        FormatConfig format,
        Variants variants) {

    public JkBuild {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(dependencies, "dependencies");
        Objects.requireNonNull(repositories, "repositories");
        Objects.requireNonNull(profiles, "profiles");
        Objects.requireNonNull(features, "features");
        repositories = List.copyOf(repositories);
        // [manifest] custom attributes; Main-Class comes from [application].main (or PluginMain
        // for a plugin worker), not here.
        manifest = manifest == null || manifest.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(manifest));
        plugins = plugins == null ? List.of() : List.copyOf(plugins);
        application = application == null ? Optional.empty() : application;
        nativeConfig = nativeConfig == null ? Optional.empty() : nativeConfig;
        // Plugin-owned tables ([spring-boot], …), keyed by plugin id.
        pluginConfigs = pluginConfigs == null || pluginConfigs.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(pluginConfigs));
        build = build == null ? Build.EMPTY : build;
        format = format == null ? FormatConfig.EMPTY : format;
        variants = variants == null ? Variants.EMPTY : variants;
    }

    /** Project + deps only; anything richer uses {@link #builder(Project)}. */
    public JkBuild(Project project, Dependencies dependencies) {
        this(
                project,
                dependencies,
                List.of(),
                Profiles.empty(),
                Features.empty(),
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /** Project + deps + repos; anything richer uses {@link #builder(Project)}. */
    public JkBuild(Project project, Dependencies dependencies, List<RepositorySpec> repositories) {
        this(
                project,
                dependencies,
                repositories,
                Profiles.empty(),
                Features.empty(),
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /** {@code [application].main}, or {@code null} when {@code [application]} is absent or unset. */
    public String mainClass() {
        return application.map(Application::main).orElse(null);
    }

    /** True when a {@code main} class is set — the {@code [application]} table declares one. */
    public boolean isRunnable() {
        return mainClass() != null;
    }

    /** True when {@code [application]} is declared. */
    public boolean isApplication() {
        return application.isPresent();
    }

    /** The schema-validated config for the plugin-owned table {@code id}, when declared. */
    public Optional<PluginConfig> pluginConfig(String id) {
        return Optional.ofNullable(pluginConfigs.get(id));
    }

    /** This build with one plugin's config replaced — the variant-overlay substitution point. */
    public JkBuild withPluginConfig(PluginConfig config) {
        Map<String, PluginConfig> next = new LinkedHashMap<>(pluginConfigs);
        next.put(config.id(), config);
        return new JkBuild(
                project,
                dependencies,
                repositories,
                profiles,
                features,
                workspace,
                manifest,
                plugins,
                application,
                nativeConfig,
                next,
                build,
                format,
                variants);
    }

    /** This build without the plugin config {@code id} (no-op when absent). */
    public JkBuild withoutPluginConfig(String id) {
        if (id == null || !pluginConfigs.containsKey(id)) return this;
        Map<String, PluginConfig> next = new LinkedHashMap<>(pluginConfigs);
        next.remove(id);
        return new JkBuild(
                project,
                dependencies,
                repositories,
                profiles,
                features,
                workspace,
                manifest,
                plugins,
                application,
                nativeConfig,
                next,
                build,
                format,
                variants);
    }

    /**
     * Override the requested artifacts for this in-memory build (CLI {@code --fat} /
     * {@code --minified}). Does not rewrite {@code jk.toml}. The caller must ensure the shrink
     * plugin config is present when {@code minified} (see
     * {@code JkBuildParser.ensureShrinkForMinified}).
     */
    public JkBuild withArtifacts(boolean assembly, boolean minified) {
        Application app = application.orElse(new Application(null, false, false, false, null));
        if (app.assembly() == (assembly || minified) && app.minified() == minified) return this;
        return new JkBuild(
                project,
                dependencies,
                repositories,
                profiles,
                features,
                workspace,
                manifest,
                plugins,
                Optional.of(new Application(app.main(), assembly, minified, app.nativeImage(), app.config())),
                nativeConfig,
                pluginConfigs,
                build,
                format,
                variants);
    }

    /** This build with its {@code [plugins]} list replaced (user-config merge / tests). */
    public JkBuild withPlugins(List<PluginDeclaration> plugins) {
        return new JkBuild(
                project,
                dependencies,
                repositories,
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

    /** This build with its {@code [build]} block replaced — the variant extra-src fold point. */
    public JkBuild withBuild(Build build) {
        return new JkBuild(
                project,
                dependencies,
                repositories,
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

    /** This build with its dependencies replaced — the variant dependency-overlay fold point. */
    public JkBuild withDependencies(Dependencies dependencies) {
        return new JkBuild(
                project,
                dependencies,
                repositories,
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

    /** True when the {@code [spring-boot]} plugin table is declared. */
    public boolean isSpringBoot() {
        return pluginConfigs.containsKey(SPRING_BOOT_ID);
    }

    /** The built-in spring-boot plugin's id / table name. */
    public static final String SPRING_BOOT_ID = "spring-boot";

    /** True when the {@code [micronaut]} plugin table is declared. */
    public boolean isMicronaut() {
        return pluginConfigs.containsKey(MICRONAUT_ID);
    }

    /** The built-in micronaut plugin's id / table name. */
    public static final String MICRONAUT_ID = "micronaut";

    /** True when a fat jar is requested — implied by {@link #minified()}. */
    public boolean assembly() {
        return application.map(Application::assembly).orElse(false);
    }

    /** True when an R8-minified jar is requested alongside the fat jar. */
    public boolean minified() {
        return application.map(Application::minified).orElse(false);
    }

    /** {@code [native].graal} — the GraalVM spec {@code jk native} uses, or {@code null} if unset. */
    public String graal() {
        return nativeConfig.map(NativeConfig::graal).orElse(nativeMode() != NativeMode.DISABLED ? "graalvm" : null);
    }

    /**
     * From {@code [application].native} and {@code [native].enabled}: {@code native = true} →
     * {@link NativeMode#ALWAYS}; else absent {@code [native]} → {@link NativeMode#DISABLED};
     * present with no key or {@code enabled = true} → {@link NativeMode#SUPPORTED}; {@code
     * enabled = "always"} → {@link NativeMode#ALWAYS}; {@code enabled = false} → {@link
     * NativeMode#DISABLED}.
     */
    public NativeMode nativeMode() {
        if (application.map(Application::nativeImage).orElse(false)) {
            return NativeMode.ALWAYS;
        }
        return nativeConfig.map(NativeConfig::enabled).orElse(NativeMode.DISABLED);
    }

    /** True when {@code jk native} should build this module ({@link NativeMode} not DISABLED). */
    public boolean nativeImage() {
        return nativeMode() != NativeMode.DISABLED;
    }

    /**
     * True when a {@code [native]} table is present with {@code enabled = false} — an explicit
     * opt-out. Distinct from an absent table: the unique-main fallback may pick up table-less
     * modules, but must never pick up an explicitly disabled one (JK-2089).
     */
    public boolean nativeExplicitlyDisabled() {
        return nativeConfig.isPresent() && nativeMode() == NativeMode.DISABLED;
    }

    public static JkBuild of(Project project) {
        return builder(project).build();
    }

    /** Fluent builder; only {@code project} is required. */
    public static Builder builder(Project project) {
        return new Builder(project);
    }

    /**
     * Same build with a replacement {@link Project} (e.g. after resolving {@code version.workspace =
     * true} from the workspace root).
     */
    public JkBuild withProject(Project project) {
        Objects.requireNonNull(project, "project");
        if (project.equals(this.project)) return this;
        Builder b = builder(project)
                .dependencies(dependencies)
                .repositories(repositories)
                .profiles(profiles)
                .features(features)
                .workspace(workspace)
                .manifest(manifest)
                .plugins(plugins)
                .application(application.orElse(null))
                .nativeConfig(nativeConfig.orElse(null))
                .build(build)
                .format(format)
                .variants(variants);
        for (PluginConfig config : pluginConfigs.values()) {
            b.pluginConfig(config);
        }
        return b.build();
    }

    /** Mutable accumulator for {@link JkBuild}. */
    public static final class Builder {
        private final Project project;
        private Dependencies dependencies = Dependencies.empty();
        private List<RepositorySpec> repositories = List.of();
        private Profiles profiles = Profiles.empty();
        private Features features = Features.empty();
        private Workspace workspace;
        private Map<String, String> manifest = Map.of();
        private List<PluginDeclaration> plugins = List.of();
        private Optional<Application> application = Optional.empty();
        private Optional<NativeConfig> nativeConfig = Optional.empty();
        private final Map<String, PluginConfig> pluginConfigs = new LinkedHashMap<>();
        private Build build;
        private FormatConfig format;
        private Variants variants;

        private Builder(Project project) {
            this.project = project;
        }

        public Builder dependencies(Dependencies dependencies) {
            this.dependencies = dependencies;
            return this;
        }

        public Builder repositories(List<RepositorySpec> repositories) {
            this.repositories = repositories;
            return this;
        }

        public Builder profiles(Profiles profiles) {
            this.profiles = profiles;
            return this;
        }

        public Builder features(Features features) {
            this.features = features;
            return this;
        }

        public Builder workspace(Workspace workspace) {
            this.workspace = workspace;
            return this;
        }

        public Builder manifest(Map<String, String> manifest) {
            this.manifest = manifest;
            return this;
        }

        public Builder plugins(List<PluginDeclaration> plugins) {
            this.plugins = plugins;
            return this;
        }

        public Builder application(Application application) {
            this.application = Optional.ofNullable(application);
            return this;
        }

        public Builder nativeConfig(NativeConfig nativeConfig) {
            this.nativeConfig = Optional.ofNullable(nativeConfig);
            return this;
        }

        public Builder pluginConfig(PluginConfig config) {
            if (config != null) this.pluginConfigs.put(config.id(), config);
            return this;
        }

        public Builder build(Build build) {
            this.build = build;
            return this;
        }

        public Builder format(FormatConfig format) {
            this.format = format;
            return this;
        }

        public Builder variants(Variants variants) {
            this.variants = variants;
            return this;
        }

        public JkBuild build() {
            return new JkBuild(
                    project,
                    dependencies,
                    repositories,
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
    }

    /** Return a copy with the given custom jar-manifest attributes. */
    public JkBuild withManifest(Map<String, String> manifest) {
        return new JkBuild(
                project,
                dependencies,
                repositories,
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

    /** True iff this is a workspace root (has a non-empty {@code workspace} block). */
    public boolean isWorkspaceRoot() {
        return workspace != null && !workspace.isEmpty();
    }

    public Optional<Workspace> workspaceOpt() {
        return Optional.ofNullable(workspace);
    }

    /**
     * Resolved {@code [native].enabled} (or legacy {@code always}). See {@link JkBuild#nativeMode}.
     */
    public enum NativeMode {
        /** No {@code [native]} table, or {@code enabled = false}. */
        DISABLED,
        /**
         * {@code [native]} present (or {@code enabled = true}): {@code jk native} builds the image;
         * plain {@code jk build} does not.
         */
        SUPPORTED,
        /**
         * {@code enabled = "always"} (or legacy {@code always = true}): native-image on {@code jk
         * build}, {@code jk install}, and {@code jk native}.
         */
        ALWAYS;

        public boolean isEnabled() {
            return this != DISABLED;
        }
    }

    /**
     * {@code [application]} block. Presence marks an application; {@code main} is required in
     * {@code jk.toml}. Absent means library.
     *
     * @param assembly build a fat {@code -all.jar} beside the thin jar
     * @param minified build an R8-minified {@code -min.jar}; implies {@code assembly}
     * @param nativeImage {@code native = true}: native-image on {@code jk build} and {@code jk
     *     install}
     * @param config optional module-relative template copied to
     *     {@code $JK_CONFIG_DIR/<bin>/config.toml} on {@code jk install}
     */
    public record Application(String main, boolean assembly, boolean minified, boolean nativeImage, String config) {

        public Application {
            if (main != null && main.isBlank()) main = null;
            if (config != null && config.isBlank()) config = null;
            // Artifacts are additive and a minified jar is built from the fat one, so asking for
            // -min.jar always yields -all.jar beside it. That is also what makes the pair
            // A/B-testable without a config change.
            if (minified) assembly = true;
        }

        /** Convenience for importers: no minified artifact, no native image, no config template. */
        public Application(String main, boolean assembly) {
            this(main, assembly, false, false, null);
        }

        /** Convenience: no native image, no config template. */
        public Application(String main, boolean assembly, boolean minified) {
            this(main, assembly, minified, false, null);
        }

        /** Convenience: no config template. */
        public Application(String main, boolean assembly, boolean minified, boolean nativeImage) {
            this(main, assembly, minified, nativeImage, null);
        }
    }

    /**
     * {@code [native]} table for GraalVM native-image. {@link #enabled} is the resolved
     * {@code enabled} key (default {@link NativeMode#SUPPORTED} when the table is present with no
     * key). {@link #metadataRepository} selects the GraalVM reachability-metadata repository
     * release, in the same grammar as a dependency version and defaulting to {@link
     * #METADATA_REPOSITORY_DEFAULT} — {@code jk lock} resolves it and pins the answer, exactly as
     * it does for a floating dependency. See {@link JkBuild#nativeMode}.
     */
    public record NativeConfig(
            String mainClass,
            String name,
            List<String> args,
            String graal,
            NativeMode enabled,
            VersionSelector metadataRepository) {

        /** {@code metadata-repository} when the key is omitted: newest stable at lock time. */
        public static final VersionSelector METADATA_REPOSITORY_DEFAULT = VersionSelector.parseFloating("latest");

        public NativeConfig {
            args = args == null ? List.of() : List.copyOf(args);
            if (mainClass != null && mainClass.isBlank()) mainClass = null;
            name = executableBasename(name);
            if (graal != null && graal.isBlank()) graal = null;
            if (enabled == null) enabled = NativeMode.SUPPORTED;
            if (metadataRepository == null) metadataRepository = METADATA_REPOSITORY_DEFAULT;
        }

        /**
         * Logical native-image basename: {@code jk} and {@code jk.exe} are the same name. A
         * trailing {@code .exe} is Windows on-disk decoration, not part of {@code [native].name}.
         */
        public static String executableBasename(String name) {
            if (name == null || name.isBlank()) return null;
            if (name.length() > 4 && name.regionMatches(true, name.length() - 4, ".exe", 0, 4)) {
                String stripped = name.substring(0, name.length() - 4);
                if (!stripped.isBlank()) return stripped;
            }
            return name;
        }

        /** True when {@code enabled = "always"} (or legacy {@code always = true}). */
        public boolean always() {
            return enabled == NativeMode.ALWAYS;
        }
    }

    /**
     * Optional {@code [build]} block: order-only deps, test plugin jars, lint, Kotlin plugins,
     * KSP options, extra source roots, and per-module test worker pin — never on a classpath or
     * lockfile.
     */
    public record Build(
            List<String> orderAfter,
            List<String> testPluginJars,
            boolean lint,
            List<KotlinPluginDecl> kotlinPlugins,
            List<String> kspOptions,
            List<String> extraSrc,
            /**
             * {@code [test] extra-src}: module-relative source roots compiled with the test tier and
             * placed on every suite's compile classpath. The test-scoped twin of {@code extraSrc},
             * and the same spelling on purpose — one vocabulary, two scopes.
             *
             * <p>This is how a module publishes shared test helpers without shipping them: they
             * compile into the test classes output, so a sibling reaches them through an existing
             * {@code kind = "tests"} edge and nothing reaches a main jar. Gradle spells the same
             * fact as a {@code testFixtures} source set.
             */
            List<String> testExtraSrc,
            /** {@code [build] test-workers}: {@code null} = inherit CLI/auto; {@code 0} = auto; {@code 1} = serial. */
            Integer testWorkers,
            /**
             * {@code [test] serial-tags}: class-level JUnit tags whose classes never share the
             * sharded worker pool — they run in a single trailing worker while untagged classes
             * shard across {@code workers}. Lets a module keep {@code workers = 0} for its unit
             * tier while its nested-engine/integration classes stay serial.
             */
            List<String> testSerialTags,
            /**
             * {@code [resolve] platform}: how BOM managed pins constrain the graph. Default
             * {@link PlatformPolicy#ENFORCED}.
             */
            PlatformPolicy platformPolicy,
            /**
             * {@code [resolve] unmapped}: how bare fills for GAs the platform does NOT manage are
             * constrained. Default {@link UnmappedPolicy#MEDIATE}.
             */
            UnmappedPolicy unmappedPolicy,
            /**
             * {@code [test] env} — what every forked test JVM's environment gets, in the order the
             * manifest lists it. Test-scoped like {@code testPluginJars}, hence its home here.
             *
             * <p>A list rather than a map because the two things a module says about the environment
             * are different statements — {@link TestEnvDecl.Forward} names a variable it wants if the
             * caller has one, {@link TestEnvDecl.Set} states a value outright — and because order is
             * then a rule the manifest can express instead of one a reader has to memorise: later
             * wins, top to bottom.
             */
            List<TestEnvDecl> testEnv) {

        public static final Build EMPTY = new Build(
                List.of(),
                List.of(),
                true,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of(),
                PlatformPolicy.ENFORCED,
                UnmappedPolicy.MEDIATE,
                List.of());

        public Build {
            orderAfter = orderAfter == null ? List.of() : List.copyOf(orderAfter);
            testPluginJars = testPluginJars == null ? List.of() : List.copyOf(testPluginJars);
            kotlinPlugins = kotlinPlugins == null ? List.of() : List.copyOf(kotlinPlugins);
            kspOptions = kspOptions == null ? List.of() : List.copyOf(kspOptions);
            extraSrc = extraSrc == null ? List.of() : List.copyOf(new LinkedHashSet<>(extraSrc));
            if (testWorkers != null && testWorkers < 0) testWorkers = 0;
            testSerialTags = testSerialTags == null ? List.of() : List.copyOf(testSerialTags);
            platformPolicy = platformPolicy == null ? PlatformPolicy.ENFORCED : platformPolicy;
            unmappedPolicy = unmappedPolicy == null ? UnmappedPolicy.MEDIATE : unmappedPolicy;
            testEnv = testEnv == null ? List.of() : List.copyOf(testEnv);
        }

        /** Append {@code dirs} to {@code extra-src} (variant fold point). */
        public Build withExtraSrc(List<String> dirs) {
            if (dirs.isEmpty()) return this;
            var all = new ArrayList<>(extraSrc);
            all.addAll(dirs);
            return new Build(
                    orderAfter,
                    testPluginJars,
                    lint,
                    kotlinPlugins,
                    kspOptions,
                    all,
                    testExtraSrc,
                    testWorkers,
                    testSerialTags,
                    platformPolicy,
                    unmappedPolicy,
                    testEnv);
        }

        public Build withPlatformPolicy(PlatformPolicy policy) {
            return new Build(
                    orderAfter,
                    testPluginJars,
                    lint,
                    kotlinPlugins,
                    kspOptions,
                    extraSrc,
                    testExtraSrc,
                    testWorkers,
                    testSerialTags,
                    policy == null ? PlatformPolicy.ENFORCED : policy,
                    unmappedPolicy,
                    testEnv);
        }

        /**
         * Effective test-worker request for this module: module pin wins when set (hermetic
         * opt-out); otherwise the CLI/global value ({@code 0} = auto).
         */
        public int effectiveTestWorkers(int cliOrGlobal) {
            if (testWorkers != null) return testWorkers;
            return Math.max(0, cliOrGlobal);
        }

        /** {@code orderAfter} plus every {@code testPluginJars} module, de-duplicated. */
        public List<String> allOrderAfter() {
            if (testPluginJars.isEmpty()) return orderAfter;
            var all = new LinkedHashSet<>(orderAfter);
            all.addAll(testPluginJars);
            return List.copyOf(all);
        }
    }

    /**
     * {@code [[kotlin-plugins]]} entry: {@code group:artifact[:version]} (omit version to match
     * the project Kotlin version); {@code id} defaults to the artifact name.
     */
    /**
     * One entry of {@code [test] env}.
     *
     * <p>Sealed and matched exhaustively: the two arms differ in what an absent value means, which
     * is the one thing a reader of this manifest most needs to be sure of. A {@code default} arm
     * would let a new consumer inherit whichever answer it happened to fall through to.
     */
    public sealed interface TestEnvDecl {

        /** The variable this entry is about. */
        String name();

        /**
         * A bare name in the array: {@code "JK_WEB_JS_SKIP"}. Take the caller's value if there is
         * one; if there is not, the test JVM does not get the variable at all.
         *
         * <p>Absent, never empty. A suite asking {@code getenv("X") != null} must see what it would
         * see outside jk, so an unset forward cannot become {@code X=""}.
         */
        record Forward(String name) implements TestEnvDecl {}

        /**
         * A table entry in the array: {@code { TZ = "UTC" }}. The value is what the module says it
         * is, and may reference {@code ${target}}, {@code ${module}} or an environment variable —
         * an unset {@code ${VAR}} here is an error, because a value stated outright and then
         * silently emptied is how a build authenticates anonymously and calls it success.
         */
        record Set(String name, String value) implements TestEnvDecl {}
    }

    public record KotlinPluginDecl(String id, String coordinate, List<String> options) {
        public KotlinPluginDecl {
            Objects.requireNonNull(coordinate, "coordinate");
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    /**
     * {@code [format]} block: optional style preset, per-language overrides, and import-hygiene
     * toggles. Boolean fields are tri-state ({@code null} = use the built-in default).
     */
    public record FormatConfig(
            String style,
            String java,
            String kotlin,
            Boolean optimizeImports,
            Boolean importOrder,
            Boolean removeUnusedImports) {

        public static final FormatConfig EMPTY = new FormatConfig(null, null, null, null, null, null);

        public FormatConfig {
            if (style != null && style.isBlank()) style = null;
            if (java != null && java.isBlank()) java = null;
            if (kotlin != null && kotlin.isBlank()) kotlin = null;
        }
    }

    public record Dependencies(Map<Scope, List<Dependency>> byScope) {

        public Dependencies {
            Objects.requireNonNull(byScope, "byScope");
            EnumMap<Scope, List<Dependency>> copy = new EnumMap<>(Scope.class);
            byScope.forEach((scope, list) -> copy.put(scope, List.copyOf(list)));
            byScope = Map.copyOf(copy);
        }

        public static Dependencies empty() {
            return new Dependencies(Map.of());
        }

        public List<Dependency> of(Scope scope) {
            return byScope.getOrDefault(scope, List.of());
        }
    }
}
