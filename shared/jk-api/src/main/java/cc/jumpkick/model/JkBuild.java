// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Parsed contents of a project's {@code jk.toml}. */
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
        Application app = application.orElse(new Application(null, false, false, false));
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
                Optional.of(new Application(app.main(), assembly, minified, app.nativeImage())),
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
     * Resolved source-tree convention for exporters (Maven vs Mill-like). Not a {@code jk.toml}
     * field — {@code src/main/{java,kotlin,scala,groovy,resources}} decides at the module dir.
     */
    public enum Layout {
        SIMPLE,
        TRADITIONAL
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

    /** When a sources JAR is produced ({@code sources}): never / publish only / always. */
    public enum SourcesMode {
        DISABLED,
        /** Assembled during {@code jk publish} only. */
        PUBLISH,
        /** Built by {@code jk build} and uploaded by {@code jk publish}. */
        ALWAYS;

        public boolean publishSources() {
            return this != DISABLED;
        }
    }

    /**
     * Sentinel for string fields declared with {@code <field>.workspace = true} until
     * {@link Project#resolveFromWorkspaceRoot} runs. Never a publishable group/version.
     */
    public static final String VERSION_FROM_WORKSPACE = "__jk.workspace__";

    /**
     * Root-level project keys that may use Cargo-style {@code field.workspace = true}. {@code name} is
     * intentionally excluded — every module keeps its own artifact id.
     */
    public enum ProjectInherit {
        GROUP,
        VERSION,
        JDK,
        JAVA,
        KOTLIN,
        GROOVY,
        SOURCES,
        DESCRIPTION,
        M2INSTALL
    }

    public record Project(
            String group,
            String name,
            String version,
            String jdk,
            int java,
            VersionSelector kotlin,
            VersionSelector groovy,
            SourcesMode sourcesMode,
            String description,
            boolean m2install,
            Set<ProjectInherit> workspaceInherits) {

        public Project {
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(version, "version");
            if (group.isBlank()) throw new IllegalArgumentException("group must not be blank");
            if (name.isBlank()) throw new IllegalArgumentException("name must not be blank");
            if (version.isBlank()) throw new IllegalArgumentException("version must not be blank");
            if (java < 0) {
                throw new IllegalArgumentException("java must be non-negative");
            }
            if (jdk != null && jdk.isBlank()) jdk = null;
            if (sourcesMode == null) sourcesMode = SourcesMode.DISABLED;
            if (description != null && description.isBlank()) description = null;
            workspaceInherits =
                    workspaceInherits == null || workspaceInherits.isEmpty() ? Set.of() : Set.copyOf(workspaceInherits);
        }

        /** Back-compat: no workspace inheritance flags. */
        public Project(
                String group,
                String name,
                String version,
                String jdk,
                int java,
                VersionSelector kotlin,
                VersionSelector groovy,
                SourcesMode sourcesMode,
                String description,
                boolean m2install) {
            this(group, name, version, jdk, java, kotlin, groovy, sourcesMode, description, m2install, Set.of());
        }

        /** True when any project identity field still needs workspace-root resolution. */
        public boolean inheritsFromWorkspace() {
            return !workspaceInherits.isEmpty();
        }

        public boolean inherits(ProjectInherit field) {
            return workspaceInherits.contains(field);
        }

        /**
         * True when group/version still need a workspace root (cannot be used as a standalone
         * project).
         */
        public boolean requiresWorkspaceRoot() {
            return inherits(ProjectInherit.GROUP)
                    || inherits(ProjectInherit.VERSION)
                    || VERSION_FROM_WORKSPACE.equals(group)
                    || VERSION_FROM_WORKSPACE.equals(version);
        }

        /**
         * Drop inheritance flags for optional fields (everything except {@link ProjectInherit#GROUP}
         * and {@link ProjectInherit#VERSION}). Used for standalone projects that omitted {@code java}
         * / {@code jdk} / … — those stay at local defaults rather than requiring a workspace.
         */
        public Project droppingOptionalInherits() {
            if (workspaceInherits.isEmpty()) return this;
            EnumSet<ProjectInherit> next = EnumSet.copyOf(workspaceInherits);
            next.remove(ProjectInherit.JDK);
            next.remove(ProjectInherit.JAVA);
            next.remove(ProjectInherit.KOTLIN);
            next.remove(ProjectInherit.GROOVY);
            next.remove(ProjectInherit.SOURCES);
            next.remove(ProjectInherit.DESCRIPTION);
            next.remove(ProjectInherit.M2INSTALL);
            if (next.equals(workspaceInherits)) return this;
            return new Project(
                    group, name, version, jdk, java, kotlin, groovy, sourcesMode, description, m2install, next);
        }

        /** True when this project declared {@code version.workspace = true} and is not yet resolved. */
        public boolean inheritsVersionFromWorkspace() {
            return inherits(ProjectInherit.VERSION) || VERSION_FROM_WORKSPACE.equals(version);
        }

        /**
         * Fill every {@code *.workspace = true} field from {@code root}. Throws if the root still has
         * pending inheritance for a requested field, or lacks a concrete value where required.
         */
        public Project resolveFromWorkspaceRoot(Project root) {
            Objects.requireNonNull(root, "root");
            if (workspaceInherits.isEmpty()) return this;
            if (root.inheritsFromWorkspace()) {
                throw new IllegalArgumentException("workspace root still has unresolved *.workspace inheritance");
            }
            String g = inherits(ProjectInherit.GROUP) ? requireRoot(root.group(), "group") : group;
            String v = inherits(ProjectInherit.VERSION) ? requireRoot(root.version(), "version") : version;
            if (VERSION_FROM_WORKSPACE.equals(v) && !inherits(ProjectInherit.VERSION)) {
                v = requireRoot(root.version(), "version");
            }
            String j = inherits(ProjectInherit.JDK) ? root.jdk() : jdk;
            int ja = inherits(ProjectInherit.JAVA) ? root.java() : java;
            VersionSelector kt = inherits(ProjectInherit.KOTLIN) ? root.kotlin() : kotlin;
            VersionSelector gr = inherits(ProjectInherit.GROOVY) ? root.groovy() : groovy;
            SourcesMode src = inherits(ProjectInherit.SOURCES) ? root.sourcesMode() : sourcesMode;
            String desc = inherits(ProjectInherit.DESCRIPTION) ? root.description() : description;
            boolean m2 = inherits(ProjectInherit.M2INSTALL) ? root.m2install() : m2install;
            return new Project(g, name, v, j, ja, kt, gr, src, desc, m2, Set.of());
        }

        private static String requireRoot(String value, String field) {
            if (value == null || value.isBlank() || VERSION_FROM_WORKSPACE.equals(value)) {
                throw new IllegalArgumentException(
                        "module inherits " + field + " from the workspace, but the root has no concrete " + field);
            }
            return value;
        }

        /** Same project with a concrete {@code version} (workspace inheritance resolution). */
        public Project withVersion(String newVersion) {
            Objects.requireNonNull(newVersion, "version");
            if (newVersion.isBlank()) throw new IllegalArgumentException("version must not be blank");
            if (newVersion.equals(this.version) && !inherits(ProjectInherit.VERSION)) return this;
            EnumSet<ProjectInherit> next = workspaceInherits.isEmpty()
                    ? EnumSet.noneOf(ProjectInherit.class)
                    : EnumSet.copyOf(workspaceInherits);
            next.remove(ProjectInherit.VERSION);
            return new Project(
                    group, name, newVersion, jdk, java, kotlin, groovy, sourcesMode, description, m2install, next);
        }

        /** Library project — bare-major {@code jdk} (0 → unset). */
        public Project(String group, String name, String version, int jdk) {
            this(group, name, version, majorSpec(jdk), jdk, null, null, null, null, false, Set.of());
        }

        /** A bare-major int as a jdk spec string ({@code 25} → {@code "25"}); 0/negative → unset. */
        private static String majorSpec(int major) {
            return major > 0 ? Integer.toString(major) : null;
        }

        /** Fluent builder; only group/name/version are required. */
        public static Builder builder(String group, String name, String version) {
            return new Builder(group, name, version);
        }

        /** Mutable accumulator for {@link Project}. */
        public static final class Builder {
            private final String group;
            private final String name;
            private final String version;
            private String jdk;
            private int java;
            private VersionSelector kotlin;
            private VersionSelector groovy;
            private SourcesMode sourcesMode = SourcesMode.DISABLED;
            private String description;
            private boolean m2install;

            private Builder(String group, String name, String version) {
                this.group = group;
                this.name = name;
                this.version = version;
            }

            /** Toolchain JDK spec, e.g. {@code "temurin-25"} or {@code "25"}. */
            public Builder jdk(String jdk) {
                this.jdk = jdk;
                return this;
            }

            /** Toolchain JDK from a bare major ({@code 25} → {@code "25"}; 0/negative → unset). */
            public Builder jdkMajor(int major) {
                this.jdk = majorSpec(major);
                return this;
            }

            /** {@code --release} target for javac (0 → falls back to the jdk major). */
            public Builder java(int java) {
                this.java = java;
                return this;
            }

            public Builder kotlin(VersionSelector kotlin) {
                this.kotlin = kotlin;
                return this;
            }

            public Builder groovy(VersionSelector groovy) {
                this.groovy = groovy;
                return this;
            }

            public Builder sourcesMode(SourcesMode sourcesMode) {
                this.sourcesMode = sourcesMode;
                return this;
            }

            public Builder description(String description) {
                this.description = description;
                return this;
            }

            public Builder m2install(boolean m2install) {
                this.m2install = m2install;
                return this;
            }

            public Project build() {
                return new Project(
                        group, name, version, jdk, java, kotlin, groovy, sourcesMode, description, m2install);
            }
        }

        /** True when this is a Kotlin project (i.e. a {@code kotlin} version is set). */
        public boolean isKotlin() {
            return kotlin != null;
        }

        /** True when this is a Groovy project (i.e. a {@code groovy} version is set). */
        public boolean isGroovy() {
            return groovy != null;
        }

        /** {@code java} release, or {@code jdk} major when {@code java} is unset. */
        public int javaRelease() {
            return java > 0 ? java : jdkMajor();
        }

        /** Major implied by {@code jdk} ({@code "temurin-25"} → 25); 0 when unset/unparseable. */
        public int jdkMajor() {
            return majorOf(jdk);
        }

        /** First numeric-leading token in a JDK spec (before {@code .}); 0 if none. */
        public static int majorOf(String spec) {
            if (spec == null) return 0;
            for (String tok : spec.toLowerCase(Locale.ROOT).split("[-_]")) {
                if (tok.isEmpty() || !Character.isDigit(tok.charAt(0))) continue;
                int dot = tok.indexOf('.');
                try {
                    return Integer.parseInt(dot < 0 ? tok : tok.substring(0, dot));
                } catch (NumberFormatException ignored) {
                    // not a clean integer — keep scanning later tokens
                }
            }
            return 0;
        }

        /** True when the spec pins a point release (e.g. {@code "25.0.3"}); jk rejects these. */
        public static boolean hasPointRelease(String spec) {
            if (spec == null) return false;
            for (String tok : spec.toLowerCase(Locale.ROOT).split("[-_]")) {
                if (!tok.isEmpty() && Character.isDigit(tok.charAt(0)) && tok.indexOf('.') >= 0) {
                    return true;
                }
            }
            return false;
        }

        /** {@code "java"} / {@code "kotlin"} / {@code "groovy"} — derived from which compiler field is set. */
        public String languageName() {
            return isKotlin() ? "kotlin" : isGroovy() ? "groovy" : "java";
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
     */
    public record Application(String main, boolean assembly, boolean minified, boolean nativeImage) {

        public Application {
            if (main != null && main.isBlank()) main = null;
            // Artifacts are additive and a minified jar is built from the fat one, so asking for
            // -min.jar always yields -all.jar beside it. That is also what makes the pair
            // A/B-testable without a config change.
            if (minified) assembly = true;
        }

        /** Convenience for importers: no minified artifact, no native image. */
        public Application(String main, boolean assembly) {
            this(main, assembly, false, false);
        }

        /** Convenience: no native image. */
        public Application(String main, boolean assembly, boolean minified) {
            this(main, assembly, minified, false);
        }
    }

    /**
     * {@code [native]} table for GraalVM native-image. {@link #enabled} is the resolved
     * {@code enabled} key (default {@link NativeMode#SUPPORTED} when the table is present with no
     * key). See {@link JkBuild#nativeMode}.
     */
    public record NativeConfig(String mainClass, String name, List<String> args, String graal, NativeMode enabled) {

        public NativeConfig {
            args = args == null ? List.of() : List.copyOf(args);
            if (mainClass != null && mainClass.isBlank()) mainClass = null;
            if (name != null && name.isBlank()) name = null;
            if (graal != null && graal.isBlank()) graal = null;
            if (enabled == null) enabled = NativeMode.SUPPORTED;
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
            /** {@code [build] test-workers}: {@code null} = inherit CLI/auto; {@code 0} = auto; {@code 1} = serial. */
            Integer testWorkers,
            /**
             * {@code [test] serial-tags}: class-level JUnit tags whose classes never share the
             * sharded worker pool — they run in a single trailing worker while untagged classes
             * shard across {@code workers}. Lets a module keep {@code workers = 0} for its unit
             * tier while its nested-engine/integration classes stay serial (JK-2184).
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
            /** {@code [build] extra-resources}: files from outside the module, copied onto its classpath. */
            List<ExtraResource> extraResources,
            /**
             * {@code [test] env} — added to every forked test JVM's environment. Test-scoped like
             * {@code testPluginJars}, hence its home here. Values may use {@code ${target}} and
             * {@code ${module}}; explicit tokens rather than guessing which values look like paths.
             */
            Map<String, String> testEnv) {

        public static final Build EMPTY = new Build(
                List.of(),
                List.of(),
                true,
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of(),
                PlatformPolicy.ENFORCED,
                UnmappedPolicy.MEDIATE,
                List.of(),
                Map.of());

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
            extraResources = extraResources == null ? List.of() : List.copyOf(extraResources);
            testEnv = testEnv == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(testEnv));
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
                    testWorkers,
                    testSerialTags,
                    platformPolicy,
                    unmappedPolicy,
                    extraResources,
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
                    testWorkers,
                    testSerialTags,
                    policy == null ? PlatformPolicy.ENFORCED : policy,
                    unmappedPolicy,
                    extraResources,
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
     * One {@code [build] extra-resources} entry: files from outside the module's own resource root,
     * copied onto the classpath at package time.
     *
     * <p>{@code from} is a module-relative {@link cc.jumpkick.glob.GlobSet} pattern (so {@code../}
     * and wildcards are allowed); {@code into} is the destination directory inside the output;
     * {@code rename} optionally renames each match, with {@code &#123;1&#125;} substituting the
     * pattern's wildcard captures. Matched files keep their path relative to the pattern's literal
     * prefix, so a directory's shape survives the copy.
     *
     * <p>Exists because jk-core bakes each plugin's {@code jk-plugin.toml} in as the built-in plugin
     * registry, and those blueprint files are the single source of truth — copying them into the
     * module would create a second, drifting copy.
     */
    public record ExtraResource(String from, String into, String rename, List<String> exclude, boolean optional) {
        public ExtraResource {
            Objects.requireNonNull(from, "from");
            into = into == null ? "" : into;
            exclude = exclude == null ? List.of() : List.copyOf(exclude);
        }
    }

    /**
     * {@code [[kotlin-plugins]]} entry: {@code group:artifact[:version]} (omit version to match
     * the project Kotlin version); {@code id} defaults to the artifact name.
     */
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
