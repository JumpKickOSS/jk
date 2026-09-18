// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.manifest;

import cc.jumpkick.model.PluginConfig;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Declarative plugin manifest ({@code jk-plugin.toml}): owned table, schema, contributions.
 * Evaluated as data — no plugin classes loaded for this layer. Giter8 trees for
 * {@code jk new -t} live at {@code templates/<lang>/<framework>/<name>.g8/} in the jar, not in this file.
 */
public record PluginDescriptor(
        String id,
        String table,
        @Nullable String version,
        @Nullable String jkCompat,
        /**
         * The {@code jk-plugin-sdk} release the code layer compiled against ({@code [plugin] sdk}),
         * or null: a consumer's lock pins the plugin's SDK floor at this version.
         */
        @Nullable String sdk,
        Map<String, SchemaKey> schema,
        Contributions contributions,
        @Nullable Code code,
        @Nullable Packaging packaging,
        List<GradleImport> gradleImports,
        Map<String, Map<String, SchemaKey>> subSchemas,
        Map<String, SubTable> subTables,
        /**
         * The {@code [sub-schema]} every {@code [<table>.<name>]} entry validates against
         * ({@code [entries] schema}), or null when the owned table is a table of keys only.
         */
        @Nullable String entrySchema) {

    public PluginDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(table, "table");
        schema = schema == null || schema.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(schema));
        contributions = contributions == null ? Contributions.NONE : contributions;
        gradleImports = gradleImports == null ? List.of() : List.copyOf(gradleImports);
        subSchemas = subSchemas == null || subSchemas.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(subSchemas));
        subTables = subTables == null || subTables.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(subTables));
    }

    /** Named nested-table group on the owned table, validated against a {@code [sub-schema]}. */
    public record SubTable(String table, String schema) {
        public SubTable {
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(schema, "schema");
        }
    }

    /**
     * One {@code [[import.gradle-plugin]]} rule (plan row 10): a Gradle plugin id this plugin's
     * table absorbs on {@code jk import}. {@code versionTo} names the config key the Gradle
     * plugin's declared version becomes (null = the id is recognized and consumed with no
     * config); {@code missingVersionWarning} is reported when {@code versionTo} is set but the
     * Gradle build declares the plugin without an inline version.
     */
    public record GradleImport(
            String id, @Nullable String versionTo, @Nullable String missingVersionWarning) {
        public GradleImport {
            Objects.requireNonNull(id, "id");
        }
    }

    /** {@code [code]}: worker jar artifactId and protocol prefix. */
    public record Code(@Nullable String worker, String protocolPrefix) {}

    /**
     * {@code [packaging]} static descriptor for run/install/image (no plugin code).
     *
     * @param packager packager name replacing the main artifact, or null
     * @param execMode {@code jar}, {@code classpath}, or {@code binary}
     * @param selfContained install links one artifact only
     * @param classesRun run/dev from classes dir (packaged layout not classpath-able)
     * @param mainScan discover main by classfile scan when undeclared
     * @param layeredImage layered container dependency layout
     */
    public record Packaging(
            @Nullable String packager,
            String execMode,
            boolean selfContained,
            boolean classesRun,
            boolean mainScan,
            boolean layeredImage,
            String artifactExtension,
            String deployCommand,
            /**
             * False when this packager produces an <em>additional</em> artifact rather than the
             * module's main one — the minified packager writes {@code -min.jar} beside the thin and
             * fat jars. Boot, Quarkus and Grails own the main artifact and leave this true.
             */
            boolean mainArtifact,
            /**
             * A step-output directory holding a {@code native-image.args} the framework computed,
             * or empty when the module's native image is jk's generic one. Quarkus enters through a
             * generated {@code --features} class and builds from its own runner jar, so
             * {@code [application] main} and jk's classpath do not describe its image at all.
             */
            String nativeImageSources,
            /**
             * A directory beside the main artifact that is the whole runnable application —
             * Quarkus's {@code quarkus-app/}. When set, an image ships this tree and launches
             * {@link #appJar} from it, instead of assembling a classpath from the lock: the
             * framework's own layout is the one that works, and the one an AOT cache can be
             * trained against.
             */
            String appDir,
            /** The jar inside {@link #appDir} to run with {@code java -jar}. */
            String appJar,
            List<Variant> variants) {

        /** Config-conditional packaging override; first matching {@code when} wins. */
        public record Variant(Condition when, Packaging packaging) {}

        /** The effective descriptor for {@code config}: the first matching variant, else this. */
        public Packaging resolve(PluginConfig config) {
            for (Variant v : variants) {
                if (v.when() instanceof Condition.ConfigEquals c
                        && c.equals().equals(String.valueOf(config.values().get(c.key())))) {
                    return v.packaging();
                }
            }
            return this;
        }
    }

    /**
     * Declarative build contributions (platforms, compiler args, Kotlin plugins, …). Each entry
     * may carry one {@link Condition}.
     */
    public record Contributions(
            List<PlatformDependency> platformDependencies,
            List<CompilerArgs> compilerArgs,
            List<NativeArgs> nativeArgs,
            List<KotlinPlugin> kotlinPlugins,
            List<PackagerDependency> packagerDependencies,
            List<StepDependency> stepDependencies,
            /**
             * The command lane: tools only {@code ctx.command} bodies read (an {@code adb}, an SDK
             * root). Provisioned when the command runs; in no step's or packager's action key.
             */
            List<StepDependency> commandDependencies,
            List<ProvidedClasspath> providedClasspath,
            List<SourceRoot> sourceRoots,
            /** GMM {@code org.gradle.jvm.environment} (e.g. {@code "android"}), or null. */
            @Nullable String jvmEnvironment) {

        public static final Contributions NONE = new Contributions(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                null);

        public Contributions {
            platformDependencies = platformDependencies == null ? List.of() : List.copyOf(platformDependencies);
            compilerArgs = compilerArgs == null ? List.of() : List.copyOf(compilerArgs);
            nativeArgs = nativeArgs == null ? List.of() : List.copyOf(nativeArgs);
            kotlinPlugins = kotlinPlugins == null ? List.of() : List.copyOf(kotlinPlugins);
            packagerDependencies = packagerDependencies == null ? List.of() : List.copyOf(packagerDependencies);
            stepDependencies = stepDependencies == null ? List.of() : List.copyOf(stepDependencies);
            commandDependencies = commandDependencies == null ? List.of() : List.copyOf(commandDependencies);
            providedClasspath = providedClasspath == null ? List.of() : List.copyOf(providedClasspath);
            sourceRoots = sourceRoots == null ? List.of() : List.copyOf(sourceRoots);
        }

        public boolean isEmpty() {
            return platformDependencies.isEmpty()
                    && compilerArgs.isEmpty()
                    && kotlinPlugins.isEmpty()
                    && packagerDependencies.isEmpty()
                    && stepDependencies.isEmpty()
                    && commandDependencies.isEmpty()
                    && providedClasspath.isEmpty()
                    && sourceRoots.isEmpty();
        }
    }

    /**
     * One {@code [[contribute.source-roots]]} entry: a module-relative directory the plugin adds
     * to the module's input roots (Grails' {@code grails-app/domain}). {@code resource} = the
     * {@code kind = "resource"} spelling; source otherwise. Always relative and non-escaping —
     * the parser rejects absolute or {@code ..} dirs at manifest load.
     */
    public record SourceRoot(
            String dir, boolean resource, @Nullable Condition when) {
        public SourceRoot {
            Objects.requireNonNull(dir, "dir");
        }
    }

    /** Extra packager artifact (fetch-only; not on the project graph). */
    public record PackagerDependency(
            String artifact, String coordinate, @Nullable Condition when) {}

    /**
     * One tool artifact entry — the shape of both {@code [[contribute.step-dependency]]} and
     * {@code [[contribute.command-dependency]]}: either a Maven {@code coordinate}
     * ({@code transitive} → full runtime closure) or an {@code sdk-component} (+ optional
     * {@code sdk-path}). Fetch-only. The lane is the list it sits in — step tools key steps and
     * packagers and are provisioned before a build; command tools are provisioned only when a
     * plugin command runs and key nothing.
     *
     * <p>{@code with} adds extra roots into the <em>same</em> resolve graph (one version per GA).
     * {@code managedBy} is a BOM GAV whose managed pins apply during that resolve — Maven-like
     * tool classpath alignment (no freestyle dual trees).
     *
     * <p>{@code forSteps} ({@code for-step}) names the steps or packagers that read the tool; only
     * those fetch it and key on it. Empty means every step and packager the plugin registers.
     *
     * <p>{@code perEntry} ({@code per-entry}) declares one tool per {@code [entries]} sub-table:
     * {@code artifact}, {@code coordinate}, {@code with}, {@code managedBy} and {@code forSteps}
     * are templates over {@code ${entry.name}} and {@code ${entry.<key>}}, expanded once per entry.
     */
    public record StepDependency(
            String artifact,
            @Nullable String coordinate,
            boolean transitive,
            @Nullable String sdkComponent,
            @Nullable String sdkPath,
            @Nullable String managedBy,
            List<String> with,
            List<String> forSteps,
            boolean perEntry,
            @Nullable Condition when,
            /**
             * A file at an http(s) URL in place of a coordinate, interpolated like one; declared
             * only when the value it resolves to is such a URL, so {@code ${config.<key>}} over a
             * key that names a module file — or nothing — is no tool.
             */
            @Nullable String url) {

        public StepDependency {
            with = with == null ? List.of() : List.copyOf(with);
            forSteps = forSteps == null ? List.of() : List.copyOf(forSteps);
        }

        public StepDependency(String artifact, @Nullable String coordinate, @Nullable Condition when) {
            this(artifact, coordinate, false, null, null, null, List.of(), List.of(), false, when, null);
        }
    }

    /**
     * Step-dependency also on the COMPILE classpath (PROVIDED: compile-only, not packaged).
     */
    public record ProvidedClasspath(
            String dependency, @Nullable Condition when) {}

    /**
     * BOM-style platform dependency; injected at parse time (may not use {@code classpath-has}).
     */
    public record PlatformDependency(
            String coordinate, @Nullable Condition when) {}

    /**
     * Default javac/kotlinc/groovyc/ksp args; skipped when the user already supplied the same arg.
     */
    public record CompilerArgs(
            List<String> javac,
            List<String> kotlin,
            List<String> groovy,
            List<String> ksp,
            @Nullable Condition when) {
        public CompilerArgs {
            javac = javac == null ? List.of() : List.copyOf(javac);
            kotlin = kotlin == null ? List.of() : List.copyOf(kotlin);
            groovy = groovy == null ? List.of() : List.copyOf(groovy);
            ksp = ksp == null ? List.of() : List.copyOf(ksp);
        }

        /** No groovy lane. */
        public CompilerArgs(List<String> javac, List<String> kotlin, List<String> ksp, @Nullable Condition when) {
            this(javac, kotlin, List.of(), ksp, when);
        }
    }

    /**
     * Extra {@code native-image} arguments a plugin's framework requires — chiefly class
     * initialization policy, which reachability metadata does not express and static analysis
     * cannot infer. Composed before the user's {@code [native] args}, so the user wins.
     */
    public record NativeArgs(List<String> args, @Nullable Condition when) {
        public NativeArgs {
            args = args == null ? List.of() : List.copyOf(args);
        }
    }

    /** Kotlin compiler plugin: BTA id, jar coordinate, options. */
    public record KotlinPlugin(
            String id,
            String coordinate,
            List<String> options,
            @Nullable Condition when) {
        public KotlinPlugin {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    /** Closed {@code when} predicates (exactly one per table); no expression language. */
    public sealed interface Condition {

        /** Module is on the resolved classpath. */
        record ClasspathHas(String module) implements Condition {}

        /** Owned-table key equals a value. */
        record ConfigEquals(String key, String equals) implements Condition {}

        /** Project declares {@code [native]}. */
        record NativeDeclared() implements Condition {}

        /** Project declares Kotlin. */
        record KotlinProject() implements Condition {}
    }

    /**
     * One schema key: its type ({@code string | coordinate | bool | int | string-list | string-map}), whether the table
     * must declare it, and the value applied when absent ({@code null} = stay absent — the
     * tri-state pattern). {@code example} and {@code hint} feed the required-key error message
     * so schema-driven validation keeps the hand-written diagnostics' quality. {@code inherit}
     * is an entry-schema key's: an entry that leaves it unset reads the table's value of the
     * same key wherever a per-entry tool names it as {@code ${entry.<key>}}.
     */
    public record SchemaKey(
            String name,
            Type type,
            boolean required,
            @Nullable Object defaultValue,
            @Nullable String example,
            @Nullable String hint,
            boolean secret,
            boolean inherit) {

        /** A key an entry does not inherit from its table. */
        public SchemaKey(
                String name,
                Type type,
                boolean required,
                @Nullable Object defaultValue,
                @Nullable String example,
                @Nullable String hint,
                boolean secret) {
            this(name, type, required, defaultValue, example, hint, secret, false);
        }

        public enum Type {
            STRING,
            /**
             * A Maven coordinate the user writes: {@code group:artifact:version}, a classifier and
             * {@code !type} allowed after it. Fewer than three segments is refused at parse time
             * naming the key, where a fetch-time error would name a coordinate and no table.
             */
            COORDINATE,
            BOOL,
            INT,
            STRING_LIST,
            /** An inline table of string values ({@code options = { a = "1", b = "2" }}). */
            STRING_MAP;

            public static Type parse(String raw, String where) {
                return switch (raw) {
                    case "string" -> STRING;
                    case "coordinate" -> COORDINATE;
                    case "bool" -> BOOL;
                    case "int" -> INT;
                    case "string-list" -> STRING_LIST;
                    case "string-map" -> STRING_MAP;
                    default ->
                        throw new IllegalArgumentException(where + ": unknown schema type `" + raw
                                + "` (string|coordinate|bool|int|string-list|string-map)");
                };
            }
        }

        /** The default's runtime shape, normalized to the {@code PluginConfig} value vocabulary. */
        public @Nullable Object normalizedDefault() {
            if (defaultValue instanceof List<?> l)
                return List.copyOf(l.stream().map(String::valueOf).toList());
            if (defaultValue instanceof Map<?, ?> m) {
                Map<String, String> out = new LinkedHashMap<>();
                m.forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
                return Collections.unmodifiableMap(out);
            }
            return defaultValue;
        }
    }
}
