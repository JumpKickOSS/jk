// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

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
        @Nullable Workspace workspace,
        Map<String, String> manifest,
        List<PluginDeclaration> plugins,
        @Nullable Application application,
        @Nullable NativeConfig nativeConfig,
        Map<String, PluginConfig> pluginConfigs,
        Build build,
        FormatConfig format,
        Variants variants,
        /**
         * {@code [install]}: what installing this module produces beyond the jar and POM. Absent
         * for every ordinary target — a library, an executable, a native binary, a script, an
         * external jar — which is the point: those five shapes are complete as they are.
         */
        @Nullable Install install,
        /**
         * {@code [publish]}: the POM metadata a release carries — name, url, licenses, developers,
         * scm. {@code null} when the manifest (and its workspace root) declares none.
         */
        @Nullable PomMetadata publish) {

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
                Map.of(),
                List.of(),
                null,
                null,
                Map.of(),
                Build.EMPTY,
                FormatConfig.EMPTY,
                Variants.EMPTY,
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
                Map.of(),
                List.of(),
                null,
                null,
                Map.of(),
                Build.EMPTY,
                FormatConfig.EMPTY,
                Variants.EMPTY,
                null,
                null);
    }

    /** {@code [application].main}, or {@code null} when {@code [application]} is absent or unset. */
    public @Nullable String mainClass() {
        return application == null ? null : application.main();
    }

    /** True when a {@code main} class is set — the {@code [application]} table declares one. */
    public boolean isRunnable() {
        return mainClass() != null;
    }

    /** True when {@code [application]} is declared. */
    public boolean isApplication() {
        return application != null;
    }

    public Optional<Application> applicationOpt() {
        return Optional.ofNullable(application);
    }

    public Optional<NativeConfig> nativeConfigOpt() {
        return Optional.ofNullable(nativeConfig);
    }

    public Optional<Install> installOpt() {
        return Optional.ofNullable(install);
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
                variants,
                install,
                publish);
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
                variants,
                install,
                publish);
    }

    /**
     * Override the requested artifacts for this in-memory build (CLI {@code --fat} /
     * {@code --minified}). Does not rewrite {@code jk.toml}. The caller must ensure the shrink
     * plugin config is present when {@code minified} (see
     * {@code JkBuildParser.ensureShrinkForMinified}).
     */
    public JkBuild withArtifacts(boolean assembly, boolean minified) {
        Application app = application != null ? application : new Application(null, false, false, false, null);
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
                new Application(app.main(), assembly, minified, app.nativeImage(), app.config()),
                nativeConfig,
                pluginConfigs,
                build,
                format,
                variants,
                install,
                publish);
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
                variants,
                install,
                publish);
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
                variants,
                install,
                publish);
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
                variants,
                install,
                publish);
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
        return application != null && application.assembly();
    }

    /** True when an R8-minified jar is requested alongside the fat jar. */
    public boolean minified() {
        return application != null && application.minified();
    }

    /** {@code [native].graal} — the GraalVM spec {@code jk native} uses, or {@code null} if unset. */
    public @Nullable String graal() {
        if (nativeConfig != null && nativeConfig.graal() != null) return nativeConfig.graal();
        return nativeMode() != NativeMode.DISABLED ? "graalvm" : null;
    }

    /**
     * From {@code [application].native} and {@code [native].enabled}: {@code native = true} →
     * {@link NativeMode#ALWAYS}; else absent {@code [native]} → {@link NativeMode#DISABLED};
     * present with no key or {@code enabled = true} → {@link NativeMode#SUPPORTED}; {@code
     * enabled = "always"} → {@link NativeMode#ALWAYS}; {@code enabled = false} → {@link
     * NativeMode#DISABLED}.
     */
    public NativeMode nativeMode() {
        if (application != null && application.nativeImage()) {
            return NativeMode.ALWAYS;
        }
        return nativeConfig == null ? NativeMode.DISABLED : nativeConfig.enabled();
    }

    /** True when {@code jk native} should build this module ({@link NativeMode} not DISABLED). */
    public boolean nativeImage() {
        return nativeMode() != NativeMode.DISABLED;
    }

    /**
     * True when a {@code [native]} table is present with {@code enabled = false} — an explicit
     * opt-out. Distinct from an absent table: the unique-main fallback may pick up table-less
     * modules, but must never pick up an explicitly disabled one.
     */
    public boolean nativeExplicitlyDisabled() {
        return nativeConfig != null && nativeMode() == NativeMode.DISABLED;
    }

    public static JkBuild of(Project project) {
        return builder(project).build();
    }

    /** Fluent builder; only {@code project} is required. */
    public static Builder builder(Project project) {
        return new Builder(project);
    }

    /**
     * Same build with a replacement {@link Profiles} table — how a workspace member ends up
     * carrying the root's profiles (see {@code WorkspaceResolve}).
     */
    public JkBuild withProfiles(Profiles profiles) {
        Objects.requireNonNull(profiles, "profiles");
        if (profiles.equals(this.profiles)) return this;
        Builder b = builder(project)
                .dependencies(dependencies)
                .repositories(repositories)
                .profiles(profiles)
                .features(features)
                .workspace(workspace)
                .manifest(manifest)
                .plugins(plugins)
                .application(application)
                .nativeConfig(nativeConfig)
                .build(build)
                .format(format)
                .variants(variants)
                .install(install)
                .publish(publish);
        for (PluginConfig config : pluginConfigs.values()) {
            b.pluginConfig(config);
        }
        return b.build();
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
                .application(application)
                .nativeConfig(nativeConfig)
                .build(build)
                .format(format)
                .variants(variants)
                .install(install)
                .publish(publish);
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
        private @Nullable Workspace workspace;
        private Map<String, String> manifest = Map.of();
        private List<PluginDeclaration> plugins = List.of();
        private @Nullable Application application;
        private @Nullable NativeConfig nativeConfig;
        private final Map<String, PluginConfig> pluginConfigs = new LinkedHashMap<>();
        private Build build = Build.EMPTY;
        private FormatConfig format = FormatConfig.EMPTY;
        private Variants variants = Variants.EMPTY;
        private @Nullable Install install;
        private @Nullable PomMetadata publish;

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

        public Builder workspace(@Nullable Workspace workspace) {
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

        public Builder application(@Nullable Application application) {
            this.application = application;
            return this;
        }

        public Builder nativeConfig(@Nullable NativeConfig nativeConfig) {
            this.nativeConfig = nativeConfig;
            return this;
        }

        public Builder pluginConfig(@Nullable PluginConfig config) {
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

        public Builder install(@Nullable Install install) {
            this.install = install;
            return this;
        }

        public Builder publish(@Nullable PomMetadata publish) {
            this.publish = publish;
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
                    variants,
                    install,
                    publish);
        }
    }

    /** The {@code [publish]} table, or {@link PomMetadata#EMPTY} when none is declared. */
    public PomMetadata pomMetadata() {
        return publish == null ? PomMetadata.EMPTY : publish;
    }

    /** This build carrying {@code publish} as its {@code [publish]} table — how a member takes the root's. */
    public JkBuild withPublish(@Nullable PomMetadata publish) {
        if (Objects.equals(publish, this.publish)) return this;
        Builder b = builder(project)
                .dependencies(dependencies)
                .repositories(repositories)
                .profiles(profiles)
                .features(features)
                .workspace(workspace)
                .manifest(manifest)
                .plugins(plugins)
                .application(application)
                .nativeConfig(nativeConfig)
                .build(build)
                .format(format)
                .variants(variants)
                .install(install)
                .publish(publish);
        for (PluginConfig config : pluginConfigs.values()) {
            b.pluginConfig(config);
        }
        return b.build();
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
                variants,
                install,
                publish);
    }

    /** True iff this is a workspace root (has a non-empty {@code workspace} block). */
    public boolean isWorkspaceRoot() {
        return workspace != null && !workspace.isEmpty();
    }

    /** Module paths from the {@code [workspace]} block; empty when this is not a workspace root. */
    public List<String> workspaceModules() {
        return workspace == null ? List.of() : workspace.modules();
    }

    /** Workspace dependency aliases; empty when this is not a workspace root. */
    public Map<String, Workspace.WorkspaceDependency> workspaceDependencies() {
        return workspace == null ? Map.of() : workspace.dependencies();
    }

    public Optional<Workspace> workspaceOpt() {
        return Optional.ofNullable(workspace);
    }

    /**
     * Resolved {@code [native].enabled}. See {@link JkBuild#nativeMode}.
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
         * {@code enabled = "always"}: native-image on {@code jk
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
     *     {@code <home>/config/<bin>/config.toml} on {@code jk install}
     */
    public record Application(
            @Nullable String main,
            boolean assembly,
            boolean minified,
            boolean nativeImage,
            @Nullable String config) {

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
            @Nullable String mainClass,
            @Nullable String name,
            List<String> args,
            @Nullable String graal,
            NativeMode enabled,
            VersionSelector metadataRepository,
            ToolchainSpec graalSpec) {

        /** {@code metadata-repository} when the key is omitted: newest stable at lock time. */
        public static final VersionSelector METADATA_REPOSITORY_DEFAULT = VersionSelector.parse("latest");

        public NativeConfig {
            args = args == null ? List.of() : List.copyOf(args);
            if (mainClass != null && mainClass.isBlank()) mainClass = null;
            name = executableBasename(name);
            if (graal != null && graal.isBlank()) graal = null;
            if (enabled == null) enabled = NativeMode.SUPPORTED;
            if (metadataRepository == null) metadataRepository = METADATA_REPOSITORY_DEFAULT;
            if (graalSpec == null) graalSpec = ToolchainSpec.NONE;
        }

        /**
         * The pre-{@link ToolchainSpec} arity: {@code graal} alone says what to resolve, never
         * whether the author pinned it, so the spec reads as undeclared.
         */
        public NativeConfig(
                @Nullable String mainClass,
                @Nullable String name,
                List<String> args,
                @Nullable String graal,
                NativeMode enabled,
                @Nullable VersionSelector metadataRepository) {
            this(
                    mainClass,
                    name,
                    args,
                    graal,
                    enabled,
                    metadataRepository == null ? METADATA_REPOSITORY_DEFAULT : metadataRepository,
                    ToolchainSpec.NONE);
        }

        /**
         * Logical native-image basename: {@code jk} and {@code jk.exe} are the same name. A
         * trailing {@code .exe} is Windows on-disk decoration, not part of {@code [native].name}.
         */
        public static @Nullable String executableBasename(@Nullable String name) {
            if (name == null || name.isBlank()) return null;
            if (name.length() > 4 && name.regionMatches(true, name.length() - 4, ".exe", 0, 4)) {
                String stripped = name.substring(0, name.length() - 4);
                if (!stripped.isBlank()) return stripped;
            }
            return name;
        }

        /** True when {@code enabled = "always"}. */
        public boolean always() {
            return enabled == NativeMode.ALWAYS;
        }
    }

    /**
     * {@code [install]} — what installing this module produces besides its jar and POM.
     *
     * <p>{@code productLib} names a directory under jk's own product library
     * ({@code ~/.jk/lib/<name>/}) that the packaged artifact is materialized into, with
     * that directory's {@code <name>.toml} stamped to name it by sha and a downgrade refused. It
     * exists because jk installs itself: the engine's real install output is a jar in jk's product
     * layout, not the coordinate in {@code repos/jk-local}, and until this was declared the only
     * record of that fact was a path-pattern match in the CLI — which meant the engine's freshness
     * check asked about the wrong artifact and a missing engine install read as "already done".
     *
     * <p>{@code productBin} names the PATH client under jk's own {@code bin/} that the module's
     * native binary replaces — the one name every other install is refused there, because a tool
     * launcher called {@code jk} would truncate the product. The previous client is parked beside
     * it so the process running the install keeps its inode.
     *
     * <p>Nothing else in the tree sets either, and nothing else should need to: a project that
     * installs into a user's product layout is jk installing jk.
     */
    public record Install(
            @Nullable String productLib, @Nullable String productBin) {
        public Install {
            if (productLib != null && productLib.isBlank()) productLib = null;
            if (productBin != null && productBin.isBlank()) productBin = null;
        }
    }

    /**
     * Optional {@code [build]} block: order-only deps, test plugin jars, lint, debug info, Kotlin
     * plugins, KSP options, javac plugins, extra source roots, and per-module test worker pin —
     * never on a classpath or lockfile.
     */
    public record Build(
            List<String> orderAfter,
            List<String> testPluginJars,
            boolean lint,
            /** {@code [build] debug}: the debug information every javac compile writes. Default {@link DebugInfo#FULL}. */
            DebugInfo debug,
            List<KotlinPluginDecl> kotlinPlugins,
            List<String> kspOptions,
            /** {@code [javac]}: the javac plugins compile-main and compile-test invoke, and verbatim args. */
            JavacConfig javac,
            List<String> extraSrc,
            /**
             * {@code [test] extra-src}: extra module-relative source roots (or single files) compiled
             * with the test tier into test classes. The test-scoped twin of {@code extraSrc}. Use
             * {@link #fixtures} for a sibling-consumed helper source set.
             */
            List<String> testExtraSrc,
            /**
             * {@code [test] fixtures}: module-relative source root compiled by
             * {@code compile-test-fixtures} into a directory that is never an artifact. {@code null}
             * means none. Boolean {@code true} in the manifest stores {@link #DEFAULT_FIXTURES}.
             */
            @Nullable String fixtures,
            /** {@code [build] test-workers}: {@code null} = inherit CLI/auto; {@code 0} = auto; {@code 1} = serial. */
            @Nullable Integer testWorkers,
            /**
             * {@code [test] serial-tags}: class-level JUnit tags whose classes never share the
             * sharded worker pool — they run in a single trailing worker while untagged classes
             * shard across {@code workers}. Lets a module keep {@code workers = 0} for its unit
             * tier while its nested-engine/integration classes stay serial.
             */
            List<String> testSerialTags,
            /** {@code [test] include-tags}: the baseline tags a bare {@code jk test} runs; empty means every tag. */
            List<String> testIncludeTags,
            /** {@code [test] exclude-tags}: the baseline tags a bare {@code jk test} leaves out. */
            List<String> testExcludeTags,
            /**
             * {@code [test] assertions}: whether every forked test JVM runs with {@code -ea}, as
             * Surefire's and Gradle's do. Default {@code true}; {@code false} runs the suite with Java
             * and Kotlin {@code assert} statements disabled.
             */
            boolean testAssertions,
            /**
             * {@code [test] coverage}: whether every test run of this module is a coverage run —
             * the JaCoCo agent on each forked test JVM and the module's report written — without
             * {@code --coverage} on the command line. Default {@code false}.
             */
            boolean testCoverage,
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
             * are different statements — {@link EnvDecl.Forward} names a variable it wants if the
             * caller has one, {@link EnvDecl.Set} states a value outright — and because order is
             * then a rule the manifest can express instead of one a reader has to memorise: later
             * wins, top to bottom.
             */
            List<EnvDecl> testEnv,
            /**
             * {@code [test] tools} — the external executables the suite shells out to ({@code node},
             * {@code git}, {@code protoc}), by the name the tests invoke them under. Each one's
             * identity — where it resolves on the PATH the test JVM gets, and what its
             * {@code --version} says — is a run-tests input, so upgrading the tool re-runs the tests
             * that depend on it. Test-scoped like {@code testEnv}, hence its home here.
             */
            List<String> testTools,
            /**
             * {@code [dev.sidecars]} — processes {@code jk dev} runs beside the application (a
             * frontend dev server, a docs server), in manifest order. Dev-only: {@code jk run},
             * {@code jk build}, and {@code jk test} never read it, and nothing here enters an action
             * key. Not a build input, like {@code [test]}, hence its home here.
             */
            List<Sidecar> devSidecars,
            /**
             * {@code [dev] ready} / {@code ready-pattern} / {@code ready-timeout} — the probe that
             * says the application itself is listening under {@code jk dev}; null means none, and
             * the app counts as ready once forked. Dev-only, like {@code devSidecars}.
             */
            @Nullable DevReady devReady,
            /**
             * {@code [audit] ignore} — advisories {@code jk audit} reports but does not gate on,
             * each with its reason and an optional expiry date. Read by the audit alone; never an
             * action-key input.
             */
            List<AuditIgnore> auditIgnores,
            /**
             * {@code [env]} — what this module's workers may take from the environment beyond the
             * allow-list, and whether they inherit all of it. Per module, like {@code [test]}.
             */
            EnvConfig env) {

        /** Default {@code [test] fixtures = true} root — {@code src/fixtures/java}. */
        public static final String DEFAULT_FIXTURES = "src/fixtures/java";

        public static final Build EMPTY = new Build(
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
                List.of(),
                List.of(),
                true,
                false,
                PlatformPolicy.ENFORCED,
                UnmappedPolicy.MEDIATE,
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of(),
                EnvConfig.EMPTY);

        public Build {
            orderAfter = orderAfter == null ? List.of() : List.copyOf(orderAfter);
            testPluginJars = testPluginJars == null ? List.of() : List.copyOf(testPluginJars);
            debug = debug == null ? DebugInfo.FULL : debug;
            kotlinPlugins = kotlinPlugins == null ? List.of() : List.copyOf(kotlinPlugins);
            kspOptions = kspOptions == null ? List.of() : List.copyOf(kspOptions);
            javac = javac == null ? JavacConfig.EMPTY : javac;
            extraSrc = extraSrc == null ? List.of() : List.copyOf(new LinkedHashSet<>(extraSrc));
            testExtraSrc = testExtraSrc == null ? List.of() : List.copyOf(testExtraSrc);
            if (fixtures != null && fixtures.isBlank()) fixtures = null;
            if (testWorkers != null && testWorkers < 0) testWorkers = 0;
            testSerialTags = testSerialTags == null ? List.of() : List.copyOf(testSerialTags);
            testIncludeTags = testIncludeTags == null ? List.of() : List.copyOf(testIncludeTags);
            testExcludeTags = testExcludeTags == null ? List.of() : List.copyOf(testExcludeTags);
            platformPolicy = platformPolicy == null ? PlatformPolicy.ENFORCED : platformPolicy;
            unmappedPolicy = unmappedPolicy == null ? UnmappedPolicy.MEDIATE : unmappedPolicy;
            testEnv = testEnv == null ? List.of() : List.copyOf(testEnv);
            testTools = testTools == null ? List.of() : List.copyOf(testTools);
            devSidecars = devSidecars == null ? List.of() : List.copyOf(devSidecars);
            auditIgnores = auditIgnores == null ? List.of() : List.copyOf(auditIgnores);
            env = env == null ? EnvConfig.EMPTY : env;
        }

        /**
         * What every forked test JVM's environment is declared to get, in precedence order:
         * {@code [env] vars} for every worker of the module, then {@code [test] env} on top.
         */
        public List<EnvDecl> testEnvDecls() {
            if (env.vars().isEmpty()) return testEnv;
            List<EnvDecl> all = new ArrayList<>(env.vars());
            all.addAll(testEnv);
            return List.copyOf(all);
        }

        /** True when this module declares a fixtures source root. */
        public boolean hasFixtures() {
            return fixtures != null;
        }

        /** Append {@code dirs} to {@code extra-src} (variant fold point). */
        public Build withExtraSrc(List<String> dirs) {
            if (dirs.isEmpty()) return this;
            var all = new ArrayList<>(extraSrc);
            all.addAll(dirs);
            return with(f -> f.extraSrc = all);
        }

        /** Append {@code dirs} to {@code [test] extra-src}. */
        public Build withTestExtraSrc(List<String> dirs) {
            if (dirs.isEmpty()) return this;
            var all = new ArrayList<>(testExtraSrc);
            all.addAll(dirs);
            return with(f -> f.testExtraSrc = all);
        }

        public Build withPlatformPolicy(PlatformPolicy policy) {
            return with(f -> f.platformPolicy = policy == null ? PlatformPolicy.ENFORCED : policy);
        }

        /** The same block with {@code [[kotlin-plugins]]} set. */
        public Build withKotlinPlugins(List<KotlinPluginDecl> plugins) {
            return with(f -> f.kotlinPlugins = plugins);
        }

        /** The same block with {@code [test] include-tags} / {@code exclude-tags} set. */
        public Build withTestTags(List<String> includeTags, List<String> excludeTags) {
            return with(f -> {
                f.testIncludeTags = includeTags;
                f.testExcludeTags = excludeTags;
            });
        }

        /** The same block with {@code [test] env} set. */
        public Build withTestEnv(List<EnvDecl> decls) {
            return with(f -> f.testEnv = decls);
        }

        /** The same block with {@code [test] tools} set. */
        public Build withTestTools(List<String> tools) {
            return with(f -> f.testTools = tools);
        }

        /** The same block with {@code [dev.sidecars]} set. */
        public Build withDevSidecars(List<Sidecar> sidecars) {
            return with(f -> f.devSidecars = sidecars);
        }

        /** The same block with the {@code [dev]} probe of the application set. */
        public Build withDevReady(@Nullable DevReady ready) {
            return with(f -> f.devReady = ready);
        }

        /** The same block with {@code [javac]} set. */
        public Build withJavac(JavacConfig config) {
            return with(f -> f.javac = config);
        }

        /** The same block with {@code [env]} set. */
        public Build withEnv(EnvConfig config) {
            return with(f -> f.env = config);
        }

        /** The same block with {@code [audit] ignore} set. */
        public Build withAuditIgnores(List<AuditIgnore> ignores) {
            return with(f -> f.auditIgnores = ignores);
        }

        /** One component changed, the rest copied — the one spelling of the copy every {@code with*} shares. */
        private Build with(Consumer<Fields> change) {
            Fields f = new Fields(this);
            change.accept(f);
            return f.build();
        }

        /** The components, mutable for the length of one {@link #with}. */
        private static final class Fields {
            List<String> orderAfter;
            List<String> testPluginJars;
            boolean lint;
            DebugInfo debug;
            List<KotlinPluginDecl> kotlinPlugins;
            List<String> kspOptions;
            JavacConfig javac;
            List<String> extraSrc;
            List<String> testExtraSrc;

            @Nullable
            String fixtures;

            @Nullable
            Integer testWorkers;

            List<String> testSerialTags;
            List<String> testIncludeTags, testExcludeTags;
            boolean testAssertions;
            boolean testCoverage;
            PlatformPolicy platformPolicy;
            UnmappedPolicy unmappedPolicy;
            List<EnvDecl> testEnv;
            List<String> testTools;
            List<Sidecar> devSidecars;

            @Nullable
            DevReady devReady;

            List<AuditIgnore> auditIgnores;
            EnvConfig env;

            Fields(Build b) {
                orderAfter = b.orderAfter;
                testPluginJars = b.testPluginJars;
                lint = b.lint;
                debug = b.debug;
                kotlinPlugins = b.kotlinPlugins;
                kspOptions = b.kspOptions;
                javac = b.javac;
                extraSrc = b.extraSrc;
                testExtraSrc = b.testExtraSrc;
                fixtures = b.fixtures;
                testWorkers = b.testWorkers;
                testSerialTags = b.testSerialTags;
                testIncludeTags = b.testIncludeTags;
                testExcludeTags = b.testExcludeTags;
                testAssertions = b.testAssertions;
                testCoverage = b.testCoverage;
                platformPolicy = b.platformPolicy;
                unmappedPolicy = b.unmappedPolicy;
                testEnv = b.testEnv;
                testTools = b.testTools;
                devSidecars = b.devSidecars;
                devReady = b.devReady;
                auditIgnores = b.auditIgnores;
                env = b.env;
            }

            Build build() {
                return new Build(
                        orderAfter,
                        testPluginJars,
                        lint,
                        debug,
                        kotlinPlugins,
                        kspOptions,
                        javac,
                        extraSrc,
                        testExtraSrc,
                        fixtures,
                        testWorkers,
                        testSerialTags,
                        testIncludeTags,
                        testExcludeTags,
                        testAssertions,
                        testCoverage,
                        platformPolicy,
                        unmappedPolicy,
                        testEnv,
                        testTools,
                        devSidecars,
                        devReady,
                        auditIgnores,
                        env);
            }
        }

        /**
         * Effective test-worker request for this module: a positive module pin wins (hermetic
         * opt-out); otherwise the request's value — the build's resolved auto share, or {@code 0}
         * for a single-project build's own auto. A pin of {@code 0} is the same as no pin: it says
         * "auto", and auto is the share, not the whole machine — a module must not escape the
         * budget the rest of the build is sharing by spelling the default out loud.
         */
        public int effectiveTestWorkers(int cliOrGlobal) {
            if (testWorkers != null && testWorkers > 0) return testWorkers;
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
    /**
     * One {@code [audit] ignore} entry: the advisory {@code id} ({@code GHSA-…}, {@code CVE-…}) the
     * audit reports without gating on, why, and — when {@code until} is set — the last day that
     * holds. From the day after, the entry is expired: the finding gates again and the report says
     * so.
     */
    public record AuditIgnore(
            String id, String reason, @Nullable LocalDate until) {
        /** True once {@code today} is past {@code until}; an entry without a date never expires. */
        public boolean expiredOn(LocalDate today) {
            return until != null && today.isAfter(until);
        }
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
            @Nullable String style,
            @Nullable String java,
            @Nullable String kotlin,
            @Nullable Boolean optimizeImports,
            @Nullable Boolean importOrder,
            @Nullable Boolean removeUnusedImports) {

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
