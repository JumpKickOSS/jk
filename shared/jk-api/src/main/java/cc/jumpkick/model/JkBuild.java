// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
        BuildBlock build,
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
        @Nullable PomMetadata publish,
        /** {@code [image]}: the OCI image the module builds; {@link ImageTable#EMPTY} when the table is absent. */
        ImageTable image,
        /**
         * {@code [library]}: what a library ships beyond its thin jar — a fat {@code -all.jar}, with
         * the packages it relocates. {@code null} when the table is absent.
         */
        @Nullable Library library) {

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
        build = build == null ? BuildBlock.EMPTY : build;
        format = format == null ? FormatConfig.EMPTY : format;
        variants = variants == null ? Variants.EMPTY : variants;
        image = image == null ? ImageTable.EMPTY : image;
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
                BuildBlock.EMPTY,
                FormatConfig.EMPTY,
                Variants.EMPTY,
                null,
                null,
                ImageTable.EMPTY,
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
                BuildBlock.EMPTY,
                FormatConfig.EMPTY,
                Variants.EMPTY,
                null,
                null,
                ImageTable.EMPTY,
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

    public Optional<Library> libraryOpt() {
        return Optional.ofNullable(library);
    }

    /**
     * The package relocations the fat jar applies, source package to shaded package in
     * declaration order: {@code [application] relocate} or {@code [library] relocate}, whichever
     * table the module declares; empty for a fat jar that bundles classes under their own names.
     */
    public Map<String, String> relocate() {
        if (application != null) return application.relocate();
        return library == null ? Map.of() : library.relocate();
    }

    /** True when the module's fat jar moves packages: its consumers read the {@code -all.jar}, not the classes tree. */
    public boolean relocates() {
        return !relocate().isEmpty();
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
                publish,
                image,
                library);
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
                publish,
                image,
                library);
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
                new Application(app.main(), assembly, minified, app.nativeImage(), app.config(), app.relocate()),
                nativeConfig,
                pluginConfigs,
                build,
                format,
                variants,
                install,
                publish,
                image,
                library);
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
                publish,
                image,
                library);
    }

    /** This build with its {@code [build]} block replaced — the variant extra-src fold point. */
    public JkBuild withBuild(BuildBlock build) {
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
                publish,
                image,
                library);
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
                publish,
                image,
                library);
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

    /** True when a fat jar is requested: {@code [application] assembly} (implied by {@link #minified()}) or {@code [library] assembly}. */
    public boolean assembly() {
        return (application != null && application.assembly()) || (library != null && library.assembly());
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
                .publish(publish)
                .image(image)
                .library(library);
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
                .publish(publish)
                .image(image)
                .library(library);
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
        private BuildBlock build = BuildBlock.EMPTY;
        private FormatConfig format = FormatConfig.EMPTY;
        private Variants variants = Variants.EMPTY;
        private @Nullable Install install;
        private @Nullable PomMetadata publish;
        private ImageTable image = ImageTable.EMPTY;
        private @Nullable Library library;

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

        public Builder build(BuildBlock build) {
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

        /** The {@code [image]} table; {@code null} means none. */
        public Builder image(@Nullable ImageTable image) {
            this.image = image == null ? ImageTable.EMPTY : image;
            return this;
        }

        public Builder library(@Nullable Library library) {
            this.library = library;
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
                    publish,
                    image,
                    library);
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
                .publish(publish)
                .image(image)
                .library(library);
        for (PluginConfig config : pluginConfigs.values()) {
            b.pluginConfig(config);
        }
        return b.build();
    }

    /** This build carrying {@code image} as its {@code [image]} table — how a member takes the root's shared keys. */
    public JkBuild withImage(ImageTable image) {
        Objects.requireNonNull(image, "image");
        if (image.equals(this.image)) return this;
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
                publish,
                image,
                library);
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
                publish,
                image,
                library);
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
     * @param relocate {@code relocate}: package prefixes the fat jar moves, source package to
     *     shaded package in declaration order — every class under a source package is rewritten
     *     to the shaded one, in its own name, in every reference to it and in the service files
     *     that name it. Empty for a fat jar that bundles classes under their own names.
     */
    public record Application(
            @Nullable String main,
            boolean assembly,
            boolean minified,
            boolean nativeImage,
            @Nullable String config,
            Map<String, String> relocate) {

        public Application {
            if (main != null && main.isBlank()) main = null;
            if (config != null && config.isBlank()) config = null;
            // Artifacts are additive and a minified jar is built from the fat one, so asking for
            // -min.jar always yields -all.jar beside it. That is also what makes the pair
            // A/B-testable without a config change.
            if (minified) assembly = true;
            relocate = relocate == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(relocate));
        }

        /** Convenience: no package relocation. */
        public Application(
                @Nullable String main,
                boolean assembly,
                boolean minified,
                boolean nativeImage,
                @Nullable String config) {
            this(main, assembly, minified, nativeImage, config, Map.of());
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
     * {@code [library]} block: what a library ships beyond its thin jar. A library has no main and
     * is never run, but it may publish a fat jar — a shaded library moves the packages it bundles
     * so consumers see them under its own names. Relocation is a fact of the fat jar, so a table
     * that relocates implies {@code assembly}.
     *
     * @param assembly build a fat {@code -all.jar} beside the thin jar
     * @param relocate package prefixes the fat jar moves, source package to shaded package in
     *     declaration order, as {@link Application#relocate}
     */
    public record Library(boolean assembly, Map<String, String> relocate) {

        public Library {
            relocate = relocate == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(relocate));
            if (!relocate.isEmpty()) assembly = true;
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
