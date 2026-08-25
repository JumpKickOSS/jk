// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.manifest;

import cc.jumpkick.model.PluginConfig;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Declarative plugin manifest ({@code jk-plugin.toml}): owned table, schema, contributions.
 * Evaluated as data — no plugin classes loaded for this layer. Giter8 trees for
 * {@code jk new -t} live at {@code templates/<lang>/<framework>/<name>.g8/} in the jar, not in this file.
 */
public record PluginDescriptor(
        String id,
        String table,
        String version,
        String jkCompat,
        Map<String, SchemaKey> schema,
        Contributions contributions,
        Code code,
        Packaging packaging,
        List<GradleImport> gradleImports,
        Map<String, Map<String, SchemaKey>> subSchemas,
        Map<String, SubTable> subTables) {

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
    public record GradleImport(String id, String versionTo, String missingVersionWarning) {
        public GradleImport {
            Objects.requireNonNull(id, "id");
        }
    }

    /** {@code [code]}: worker jar artifactId and protocol prefix. */
    public record Code(String worker, String protocolPrefix) {}

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
            String packager,
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
            String jvmEnvironment) {

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
    public record SourceRoot(String dir, boolean resource, Condition when) {
        public SourceRoot {
            Objects.requireNonNull(dir, "dir");
        }
    }

    /** Extra packager artifact (fetch-only; not on the project graph). */
    public record PackagerDependency(String artifact, String coordinate, Condition when) {}

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
     */
    public record StepDependency(
            String artifact,
            String coordinate,
            boolean transitive,
            String sdkComponent,
            String sdkPath,
            String managedBy,
            List<String> with,
            Condition when) {

        public StepDependency {
            with = with == null ? List.of() : List.copyOf(with);
        }

        public StepDependency(String artifact, String coordinate, Condition when) {
            this(artifact, coordinate, false, null, null, null, List.of(), when);
        }
    }

    /**
     * Step-dependency also on the COMPILE classpath (PROVIDED: compile-only, not packaged).
     */
    public record ProvidedClasspath(String dependency, Condition when) {}

    /**
     * BOM-style platform dependency; injected at parse time (may not use {@code classpath-has}).
     */
    public record PlatformDependency(String coordinate, Condition when) {}

    /**
     * Default javac/kotlinc/groovyc/ksp args; skipped when the user already supplied the same arg.
     */
    public record CompilerArgs(
            List<String> javac, List<String> kotlin, List<String> groovy, List<String> ksp, Condition when) {
        public CompilerArgs {
            javac = javac == null ? List.of() : List.copyOf(javac);
            kotlin = kotlin == null ? List.of() : List.copyOf(kotlin);
            groovy = groovy == null ? List.of() : List.copyOf(groovy);
            ksp = ksp == null ? List.of() : List.copyOf(ksp);
        }

        /** Back-compat constructor: no groovy lane. */
        public CompilerArgs(List<String> javac, List<String> kotlin, List<String> ksp, Condition when) {
            this(javac, kotlin, List.of(), ksp, when);
        }
    }

    /**
     * Extra {@code native-image} arguments a plugin's framework requires — chiefly class
     * initialization policy, which reachability metadata does not express and static analysis
     * cannot infer. Composed before the user's {@code [native] args}, so the user wins.
     */
    public record NativeArgs(List<String> args, Condition when) {
        public NativeArgs {
            args = args == null ? List.of() : List.copyOf(args);
        }
    }

    /** Kotlin compiler plugin: BTA id, jar coordinate, options. */
    public record KotlinPlugin(String id, String coordinate, List<String> options, Condition when) {
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
     * One schema key: its type ({@code string | bool | int | string-list}), whether the table
     * must declare it, and the value applied when absent ({@code null} = stay absent — the
     * tri-state pattern). {@code example} and {@code hint} feed the required-key error message
     * so schema-driven validation keeps the hand-written diagnostics' quality.
     */
    public record SchemaKey(
            String name,
            Type type,
            boolean required,
            Object defaultValue,
            String example,
            String hint,
            boolean secret) {

        public enum Type {
            STRING,
            BOOL,
            INT,
            STRING_LIST;

            public static Type parse(String raw, String where) {
                return switch (raw) {
                    case "string" -> STRING;
                    case "bool" -> BOOL;
                    case "int" -> INT;
                    case "string-list" -> STRING_LIST;
                    default ->
                        throw new IllegalArgumentException(
                                where + ": unknown schema type `" + raw + "` (string|bool|int|string-list)");
                };
            }
        }

        /** The default's runtime shape, normalized to the {@code PluginConfig} value vocabulary. */
        public Object normalizedDefault() {
            if (defaultValue instanceof List<?> l)
                return List.copyOf(l.stream().map(String::valueOf).toList());
            return defaultValue;
        }
    }
}
