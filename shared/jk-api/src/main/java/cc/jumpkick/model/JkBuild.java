// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import cc.jumpkick.plugin.PluginConfig;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
        // [manifest] custom attributes; Main-Class comes from [application].main, not here.
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

    /** {@code [application].shadow-jar} — bundle an all-in-one (shadow / fat) jar. */
    public boolean shadowJar() {
        return application.map(Application::shadowJar).orElse(false);
    }

    /** {@code [native].graal} — the GraalVM spec {@code jk native} uses, or {@code null} if unset. */
    public String graal() {
        return nativeConfig.map(NativeConfig::graal).orElse(null);
    }

    /** From {@code [native]} presence and its {@code always} flag. */
    public NativeMode nativeMode() {
        return nativeConfig
                .map(nc -> nc.always() ? NativeMode.ALWAYS : NativeMode.SUPPORTED)
                .orElse(NativeMode.DISABLED);
    }

    /** Backward-compat: true when native mode is not DISABLED. */
    public boolean nativeImage() {
        return nativeMode() != NativeMode.DISABLED;
    }

    public static JkBuild of(Project project) {
        return builder(project).build();
    }

    /** Fluent builder; only {@code project} is required. */
    public static Builder builder(Project project) {
        return new Builder(project);
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
     * Source layout: {@code simple} ({@code ./src}, {@code ./test}), {@code traditional} (Maven),
     * or {@code auto} (infer; default when absent).
     */
    public enum Layout {
        SIMPLE,
        TRADITIONAL,
        AUTO;

        /** Parse from a jk.toml string value; null or blank → AUTO. */
        public static Layout parse(String raw) {
            if (raw == null || raw.isBlank()) return AUTO;
            return switch (raw.trim().toLowerCase()) {
                case "simple" -> SIMPLE;
                case "traditional" -> TRADITIONAL;
                case "auto" -> AUTO;
                default ->
                    throw new IllegalArgumentException(
                            "project.layout must be \"simple\", \"traditional\", or \"auto\" (got: " + raw + ")");
            };
        }

        /** The string written to jk.toml, or null for AUTO (omitted). */
        public String tomlValue() {
            return switch (this) {
                case SIMPLE -> "simple";
                case TRADITIONAL -> "traditional";
                case AUTO -> null;
            };
        }
    }

    /** From {@code [native]} presence and {@code always}; see {@link JkBuild#nativeMode}. */
    public enum NativeMode {
        /** {@code [native]} absent. */
        DISABLED,
        /** Eligible for {@code jk native}, not auto-built by {@code jk build}. */
        SUPPORTED,
        /** Native-image on {@code jk build}, {@code jk install}, and {@code jk native}. */
        ALWAYS;

        public boolean isEnabled() {
            return this != DISABLED;
        }
    }

    /** When a sources JAR is produced ({@code project.sources}): never / publish only / always. */
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

    public record Project(
            String group,
            String name,
            String version,
            String jdk,
            int java,
            VersionSelector kotlin,
            SourcesMode sourcesMode,
            String description,
            boolean m2install,
            Layout layout) {

        public Project {
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(version, "version");
            if (group.isBlank()) throw new IllegalArgumentException("project.group must not be blank");
            if (name.isBlank()) throw new IllegalArgumentException("project.name must not be blank");
            if (version.isBlank()) throw new IllegalArgumentException("project.version must not be blank");
            if (java < 0) {
                throw new IllegalArgumentException("project.java must be non-negative");
            }
            if (jdk != null && jdk.isBlank()) jdk = null;
            if (sourcesMode == null) sourcesMode = SourcesMode.DISABLED;
            if (layout == null) layout = Layout.AUTO;
            if (description != null && description.isBlank()) description = null;
        }

        /** Library project — bare-major {@code jdk} (0 → unset). */
        public Project(String group, String name, String version, int jdk) {
            this(group, name, version, majorSpec(jdk), jdk, null, null, null, false, Layout.AUTO);
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
            private SourcesMode sourcesMode = SourcesMode.DISABLED;
            private String description;
            private boolean m2install;
            private Layout layout = Layout.AUTO;

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

            public Builder layout(Layout layout) {
                this.layout = layout;
                return this;
            }

            public Project build() {
                return new Project(
                        group, name, version, jdk, java, kotlin, sourcesMode, description, m2install, layout);
            }
        }

        /** True when this is a Kotlin project (i.e. a {@code kotlin} version is set). */
        public boolean isKotlin() {
            return kotlin != null;
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

        /** {@code "java"} / {@code "kotlin"} — derived from which compiler field is non-zero. */
        public String languageName() {
            return isKotlin() ? "kotlin" : "java";
        }
    }

    /**
     * {@code [application]} block. Presence alone marks an application; absent means library.
     */
    public record Application(String main, boolean shadowJar) {

        public Application {
            if (main != null && main.isBlank()) main = null;
        }
    }

    /**
     * {@code [native]} table for GraalVM native-image. Presence alone enables {@link NativeMode};
     * see {@link JkBuild#nativeMode}.
     */
    public record NativeConfig(String mainClass, String name, List<String> args, String graal, boolean always) {

        public NativeConfig {
            args = args == null ? List.of() : List.copyOf(args);
            if (mainClass != null && mainClass.isBlank()) mainClass = null;
            if (name != null && name.isBlank()) name = null;
            if (graal != null && graal.isBlank()) graal = null;
        }
    }

    /**
     * Optional {@code [build]} block: order-only deps, test plugin jars, lint, Kotlin plugins,
     * KSP options, and extra source roots — never on a classpath or lockfile.
     */
    public record Build(
            List<String> orderAfter,
            List<String> testPluginJars,
            boolean lint,
            List<KotlinPluginDecl> kotlinPlugins,
            List<String> kspOptions,
            List<String> extraSrc) {

        public static final Build EMPTY = new Build(List.of(), List.of(), true, List.of(), List.of(), List.of());

        public Build {
            orderAfter = orderAfter == null ? List.of() : List.copyOf(orderAfter);
            testPluginJars = testPluginJars == null ? List.of() : List.copyOf(testPluginJars);
            kotlinPlugins = kotlinPlugins == null ? List.of() : List.copyOf(kotlinPlugins);
            kspOptions = kspOptions == null ? List.of() : List.copyOf(kspOptions);
            extraSrc = extraSrc == null ? List.of() : List.copyOf(new java.util.LinkedHashSet<>(extraSrc));
        }

        /** Append {@code dirs} to {@code extra-src} (variant fold point). */
        public Build withExtraSrc(List<String> dirs) {
            if (dirs.isEmpty()) return this;
            var all = new java.util.ArrayList<>(extraSrc);
            all.addAll(dirs);
            return new Build(orderAfter, testPluginJars, lint, kotlinPlugins, kspOptions, all);
        }

        /** {@code orderAfter} plus every {@code testPluginJars} module, de-duplicated. */
        public List<String> allOrderAfter() {
            if (testPluginJars.isEmpty()) return orderAfter;
            var all = new java.util.LinkedHashSet<>(orderAfter);
            all.addAll(testPluginJars);
            return List.copyOf(all);
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

    /** {@code [format]} block: optional style preset and per-language overrides (raw strings). */
    public record FormatConfig(String style, String java, String kotlin, Boolean optimizeImports) {

        public static final FormatConfig EMPTY = new FormatConfig(null, null, null, null);

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
