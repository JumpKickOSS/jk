// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.androidsdk.AndroidSdk;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.compile.GroovycRequest;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.manifest.PluginDescriptors;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.workspace.ExplainReport;
import cc.jumpkick.runtime.workspace.ForecastWalk;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.FreshnessStamp;
import cc.jumpkick.task.KotlinClasspathAbi;
import cc.jumpkick.wire.runtime.BuildKnobs;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The forecast and the build read one module the same way: right after a build, {@code jk explain}
 * and the preflight's walk find every module clean, and one source edit makes exactly the edited
 * module dirty. Each fixture is a workspace of the module under test beside a plain Java module, in
 * a shape whose run differs from the plain one: a Kotlin module with processors, a Kotlin or Groovy
 * module a plugin generates Java for, a Groovy module tested in Java, a plugin that hands the test
 * JVM flags and classpath, a plugin's provided jars on the test JVM's classpath, a Java module whose
 * compile classpath, processor path and test processor path each carry their own entries, a
 * Kotlin module whose multi-release root compiles against the provided jars with no Java main
 * sources, and a module whose sources compile to no class file beside its resources. The request's knobs vary too: a {@code --variant}, {@code jk compile} and {@code
 * --skip-tests} build, each forecast under the knobs it was built with.
 */
@Tag("integration")
class ForecastRunParityMatrixTest {

    /** The first-party worker whose jar the stub plugin's stands in for, while a test runs. */
    private static final PluginJar STUB_WORKER = PluginJar.TAGLIB;

    @TempDir
    Path tmp;

    private @Nullable String oldWorker;
    private @Nullable String oldSdkRoot;

    @BeforeEach
    void stubPlugins() throws IOException {
        oldWorker = System.getProperty(STUB_WORKER.jarProperty());
        System.setProperty(
                STUB_WORKER.jarProperty(),
                StubContributionsPlugin.jar(tmp.resolve("worker/stub.jar")).toString());
        PluginTableRegistry.putBuiltIn(
                PluginDescriptors.parse(
                        StubContributionsPlugin.descriptor(STUB_WORKER.artifactId()), "stub-contributions.toml"),
                null);

        Path sdk = Files.createDirectories(tmp.resolve("sdk"));
        jar(sdk.resolve("platform.jar"), "platform.txt");
        oldSdkRoot = System.getProperty(AndroidSdk.ROOT_PROPERTY);
        System.setProperty(AndroidSdk.ROOT_PROPERTY, sdk.toString());
        PluginTableRegistry.putBuiltIn(PluginDescriptors.parse("""
                        [plugin]
                        id = "provided-matrix"
                        table = "provided-matrix"

                        [[contribute.step-dependency]]
                        artifact = "platform"
                        sdk-component = "root"
                        sdk-path = "platform.jar"

                        [[contribute.provided-classpath]]
                        dependency = "platform"
                        """, "provided-matrix.toml"), null);
    }

    @AfterEach
    void restore() {
        set(STUB_WORKER.jarProperty(), oldWorker);
        set(AndroidSdk.ROOT_PROPERTY, oldSdkRoot);
    }

    @Test
    void plain_java() throws Exception {
        Path ws = workspace(JAVA_MODULE, Lib.JAVA);
        assertParity(ws, "src/com/example/Lib.java", true);
    }

    @Test
    void kotlin_with_processor_dependencies() throws Exception {
        // A javac processor: the module runs javac beside kotlinc, and no KSP round.
        Path ws = workspace("""
                kotlin = "^2.4.10"

                [processor-dependencies]
                auto-service = { group = "com.google.auto.service", version = "1.1.1" }
                """, Lib.KOTLIN);
        assertParity(ws, "src/com/example/Lib.kt", true);
    }

    @Test
    void kotlin_with_plugin_java_sources() throws Exception {
        Path ws = workspace("""
                kotlin = "^2.4.10"

                [stub-contributions]
                sources = true
                """, Lib.KOTLIN_READING_GENERATED);
        assertParity(ws, "src/com/example/Lib.kt", true);
    }

    @Test
    void groovy_with_plugin_java_sources() throws Exception {
        Path ws = workspace("""
                groovy = "5.0.4"

                [stub-contributions]
                sources = true
                """, Lib.GROOVY);
        assertParity(ws, "src/com/example/Lib.groovy", true);
    }

    @Test
    void groovy_with_java_tests() throws Exception {
        Path ws = workspace("""
                groovy = "5.0.4"
                """, Lib.GROOVY_TESTED_IN_JAVA);
        assertParity(ws, "src/com/example/Lib.groovy", true);
    }

    @Test
    void plugin_test_jvm_args_and_classpath() throws Exception {
        Path ws = workspace("""
                [stub-contributions]
                test-env = true
                """, Lib.JAVA_READING_TEST_ENV);
        assertParity(ws, "src/com/example/Lib.java");
    }

    @Test
    void provided_runtime_jars() throws Exception {
        Path ws = workspace("""
                [provided-matrix]
                """, Lib.JAVA);
        assertParity(ws, "src/com/example/Lib.java");
    }

    @Test
    void java_with_processors_and_a_sibling() throws Exception {
        // The sibling's classes tree on the compile classpath, the processor path, and a test
        // table that does not inherit it: compile-test's own path.
        Path ws = workspace("""
                [dependencies]
                b = { workspace = true }

                [processor-dependencies]
                auto-service = { group = "com.google.auto.service", version = "1.1.1" }

                [test-processor-dependencies]
                inherit = false
                auto-service = { group = "com.google.auto.service", version = "1.1.1" }
                """, Lib.JAVA);
        assertParity(ws, "src/com/example/Lib.java", true);
    }

    @Test
    void kotlin_with_provided_jars_and_a_multi_release_root() throws Exception {
        // No Java main sources, so compile-java prices nothing; compile-versions still compiles
        // against the main classpath, the plugin's provided jar included.
        Path ws = workspace("""
                kotlin = "^2.4.10"

                [provided-matrix]

                [multi-release]
                25 = "mr/java25"
                """, Lib.KOTLIN);
        write(ws.resolve("a/mr/java25/com/example/Versioned.java"), """
                package com.example;

                public final class Versioned {
                    private Versioned() {}
                }
                """);
        assertParity(ws, "src/com/example/Lib.kt");
    }

    @Test
    void a_classless_module_with_resources() throws Exception {
        // Its one source is a package-info.java of javadoc alone: javac writes no class file, so the
        // classes tree holds the copied resource and nothing else.
        Path ws = workspace(JAVA_MODULE, Lib.CLASSLESS);
        write(ws.resolve("a/resources/manifest.toml"), "id = \"classless\"\n");
        assertParity(ws, "src/com/example/package-info.java", true);
    }

    @Test
    void a_variant_build() throws Exception {
        // The selected value adds a source root the default does not compile.
        Path ws = workspace("""
                [variants.mode]
                default = "a"
                [variants.mode.a]
                [variants.mode.b]
                extra-src = ["src-b"]
                """, Lib.JAVA);
        write(ws.resolve("a/src-b/com/example/OnlyB.java"), """
                package com.example;

                public final class OnlyB {
                    private OnlyB() {}
                }
                """);
        assertParity(ws, "src/com/example/Lib.java", true, BuildKnobs.DEFAULTS.withVariant("mode=b"));
    }

    @Test
    void jk_compile() throws Exception {
        // No jar and no test classes: the goal never makes them, so none is missing.
        Path ws = workspace(JAVA_MODULE, Lib.JAVA);
        assertParity(ws, "src/com/example/Lib.java", true, BuildKnobs.DEFAULTS.withTarget(WorkspaceTarget.COMPILE));
    }

    @Test
    void a_skip_tests_build() throws Exception {
        Path ws = workspace(JAVA_MODULE, Lib.JAVA);
        assertParity(ws, "src/com/example/Lib.java", false, BuildKnobs.DEFAULTS.withSkipTests(true));
    }

    /**
     * Build {@code ws}, then: the walk and {@code jk explain} find every module clean; after one edit
     * of {@code edited} in module {@code a}, both find {@code a} and nothing else dirty.
     */
    private void assertParity(Path ws, String edited) throws Exception {
        assertParity(ws, edited, false);
    }

    /**
     * As above; with {@code byKey}, between the two: once {@code a}'s freshness stamps are gone, and
     * again once its whole {@code target/} is, the walk prices each of its compiles by the action key
     * of the request the build made, and finds it.
     */
    private void assertParity(Path ws, String edited, boolean byKey) throws Exception {
        assertParity(ws, edited, byKey, BuildKnobs.DEFAULTS);
    }

    /**
     * As above with the build, the walk and {@code jk explain} under {@code knobs}; explain prices
     * {@code jk build}, so a goal that does not package skips it.
     */
    private void assertParity(Path ws, String edited, boolean byKey, BuildKnobs knobs) throws Exception {
        Path cache = tmp.resolve("cache");
        lock(ws, cache);
        build(ws, cache, knobs);

        assertCompileInputsAgree(ws, cache, knobs);
        assertThat(dirty(walk(ws, cache, knobs)))
                .as("the walk right after a build")
                .isEmpty();
        if (knobs.target().packages()) {
            assertThat(dirty(explain(ws, cache, knobs)))
                    .as("jk explain right after a build")
                    .isEmpty();
        }

        if (byKey) {
            Path a = ws.resolve("a");
            BuildLayout layout = BuildLayout.of(a, JkBuildParser.parse(a.resolve("jk.toml")));
            PathUtil.deleteRecursively(layout.compileStampDir());
            assertThat(uncachedCompiles(walk(ws, cache, knobs)))
                    .as("a's compiles without their freshness stamps")
                    .isEmpty();

            // Plugin-generated sources live in their step's scratch under target/: the build restores
            // them before its compiles read them, and the walk keys the compiles on them as restored.
            PathUtil.deleteRecursively(layout.moduleTargetDir());
            assertThat(uncachedCompiles(walk(ws, cache, knobs)))
                    .as("a's compiles once its target/ is gone")
                    .isEmpty();
        }

        Path source = ws.resolve("a").resolve(edited);
        Files.writeString(source, Files.readString(source) + "\n// edited\n");
        assertThat(dirtyNames(walk(ws, cache, knobs)))
                .as("the walk after editing a")
                .containsExactly("a");
        if (knobs.target().packages()) {
            assertThat(dirtyNames(explain(ws, cache, knobs)))
                    .as("jk explain after editing a")
                    .containsExactly("a");
        }
    }

    /** Module {@code a}'s compiles that are not cached, for the failure message. */
    private static List<String> uncachedCompiles(List<TaskForecast.Module> modules) {
        return modules.stream()
                .filter(m -> m.dir().getFileName().toString().equals("a"))
                .flatMap(m -> m.steps().stream())
                .filter(s -> COMPILES.contains(s.name()) && !s.cached())
                .map(s -> s.name() + " (" + s.text() + ")")
                .toList();
    }

    /**
     * Module {@code a}'s forecast derives each main compile's inputs as the build did: the stamp each
     * compile wrote holds under the sources, classpath token lines and option digest the forecast
     * derives, and names the action key of the request the forecast builds.
     */
    private static void assertCompileInputsAgree(Path ws, Path cache, BuildKnobs knobs) throws Exception {
        SessionContext.where(session(cache, knobs), () -> {
            assertCompileInputsAgree(ws, cache);
            return null;
        });
    }

    /** As above under the ambient session's knobs. */
    private static void assertCompileInputsAgree(Path ws, Path cache) throws Exception {
        BuildGraph.Result graph = BuildGraph.resolve(ws, JkBuildParser.parse(ws.resolve("jk.toml")));
        BuildGraph.BuildUnit unit = graph.topoOrder().stream()
                .filter(u -> u.dir().getFileName().toString().equals("a"))
                .findFirst()
                .orElseThrow();
        ForecastScope s = ForecastScope.alone(
                unit,
                TaskForecaster.effectiveManifest(unit, EffectiveManifest.Selection.ambient()),
                cache,
                new ActionCache(JkStores.cacheCas(cache), CacheTree.ACTIONS.under(cache), JkStores.storeCas()));
        ForecastScope.Prepared prepared = s.prepared();
        ForecastScope.Parsed parsed = prepared.parsed();
        CompileInputs.Module module = PlannerCompileForecast.compileInputs(s, prepared);
        BuildLayout layout = parsed.layout();
        String version = BuildIdentity.cacheKeyVersion();
        List<String> compiled = new ArrayList<>();
        if (!parsed.mainSrc().isEmpty()) {
            CompileRequest request = PlannerCompile.mainCompileRequest(
                    CompileInputs.java(module, parsed.mainSrc(), CompileInputs.groovyJar(module)));
            List<String> tokens = ActionKey.javacClasspathTokens(request, s.restored.abiToken());
            assertStamp(
                    layout.classesDir(),
                    BuildStamps.JAVA,
                    request.sources(),
                    tokens,
                    parsed.release(),
                    ActionKey.javacOptionsDigest(request),
                    ActionKey.forJavac(
                            ActionKey.qualifiedTaskId(TaskNames.COMPILE_MAIN, layout.classesDir()),
                            request,
                            version,
                            tokens));
            compiled.add(TaskNames.COMPILE_JAVA);
        }
        if (!parsed.ktSrc().isEmpty()) {
            CompileInputs.Kotlin inputs = CompileInputs.kotlin(module, parsed.ktSrc(), parsed.mainSrc());
            KotlinClasspathAbi.Snapshotter snapshotter = s.restored.kotlinSnapshotter();
            assertStamp(
                    layout.compileStampDir(),
                    BuildStamps.KOTLIN,
                    inputs.stampInputs(),
                    PlannerLang.kotlinStampTokens(inputs.classpath(), snapshotter),
                    parsed.release(),
                    inputs.config().digest(),
                    ActionKey.forKotlinc(
                            CompileInputs.kotlinTaskId(module),
                            CompileInputs.kotlinWorker(module, inputs).request(),
                            version,
                            snapshotter));
            compiled.add(TaskNames.COMPILE_KOTLIN);
        }
        if (!parsed.gvSrc().isEmpty()) {
            CompileInputs.Groovy inputs =
                    CompileInputs.groovy(module, parsed.gvSrc(), parsed.mainSrc(), parsed.ktSrc());
            GroovycRequest request = CompileInputs.groovyRequest(module, inputs);
            assertStamp(
                    layout.compileStampDir(),
                    BuildStamps.GROOVY,
                    inputs.stampInputs(),
                    ActionKey.groovycClasspathTokens(request, s.restored.abiToken()),
                    parsed.release(),
                    CompileInputs.groovyDigest(module),
                    ActionKey.forGroovyc(CompileInputs.groovyTaskId(module), request, version, s.restored.abiToken()));
            compiled.add(TaskNames.COMPILE_GROOVY);
        }
        assertThat(compiled).as("a's main compiles").isNotEmpty();
    }

    /** The stamp {@code name} under {@code dir} holds under the forecast's inputs and names {@code key}. */
    private static void assertStamp(
            Path dir, String name, List<Path> inputs, List<String> tokens, int release, String digest, String key)
            throws IOException {
        Path file = dir.resolve(name);
        String written = Files.isRegularFile(file) ? Files.readString(file) : "(none)";
        Optional<FreshnessStamp.Stamp> stamp = FreshnessStamp.freshStamp(
                dir, name, inputs, FreshnessStamp.ClasspathTokens.of(tokens), release, digest);
        assertThat(stamp)
                .as(
                        "%s holds under the forecast's inputs %s, tokens %s and digest %s; the build wrote:%n%s",
                        name, inputs, tokens, digest, written)
                .isPresent();
        assertThat(stamp.get().actionKey())
                .as("%s names the forecast's action key", name)
                .isEqualTo(key);
    }

    private static final Set<String> COMPILES =
            Set.of(TaskNames.COMPILE_JAVA, TaskNames.COMPILE_KOTLIN, TaskNames.COMPILE_GROOVY);

    private static List<TaskForecast.Module> walk(Path ws, Path cache, BuildKnobs knobs) throws Exception {
        BuildGraph.Result graph = BuildGraph.resolve(ws, JkBuildParser.parse(ws.resolve("jk.toml")));
        assertThat(graph.hasErrors()).isFalse();
        ActionCache actionCache =
                new ActionCache(JkStores.cacheCas(cache), CacheTree.ACTIONS.under(cache), JkStores.storeCas());
        return SessionContext.where(
                session(cache, knobs),
                () -> ForecastWalk.of(graph, JkStores.cacheCas(cache), actionCache, cache, knobs, null));
    }

    private static List<TaskForecast.Module> explain(Path ws, Path cache, BuildKnobs knobs) throws Exception {
        JkBuild root = JkBuildParser.parse(ws.resolve("jk.toml"));
        ExplainReport report = ExplainReport.compute(ws, root, cache, session(cache, knobs), knobs, 0);
        assertThat(report.plan().errors()).isEmpty();
        return report.plan().modules();
    }

    /** The session a client sends beside a request under {@code knobs}: its variant and env. */
    private static Session session(Path cache, BuildKnobs knobs) {
        return Session.defaults().withCacheDir(cache).withVariant(knobs.variant(), knobs.clientEnv());
    }

    /** Each dirty module with the steps that are not cached, for the failure message. */
    private static List<String> dirty(List<TaskForecast.Module> modules) {
        return modules.stream()
                .filter(TaskForecast.Module::dirty)
                .map(m -> m.dir().getFileName() + " " + (m.reason() == null ? "" : m.reason() + " ")
                        + m.steps().stream()
                                .filter(s -> !s.cached())
                                .map(s -> s.name() + " (" + s.text() + ")")
                                .toList())
                .toList();
    }

    private static Set<String> dirtyNames(List<TaskForecast.Module> modules) {
        Set<String> names = new TreeSet<>();
        for (TaskForecast.Module m : modules) {
            if (m.dirty()) names.add(m.dir().getFileName().toString());
        }
        return names;
    }

    private static void lock(Path ws, Path cache) throws Exception {
        LockFlow.Result lock = LockFlow.run(ws, cache, List.of(), false, null);
        assertThat(lock.status()).as("workspace lock: %s", lock.error()).isZero();
    }

    /** A build of every module under {@code knobs}. */
    private static void build(Path ws, Path cache, BuildKnobs knobs) throws Exception {
        WorkspaceResult result = SessionContext.where(
                session(cache, knobs),
                () -> WorkspaceExecute.buildWorkspace(knobs.request(ws, cache), new WorkspaceBuildListener() {}));
        assertThat(result.errors()).as("build errors").isEmpty();
        assertThat(result.success()).as("build success").isTrue();
    }

    private static final String JAVA_MODULE = "";

    /**
     * A workspace of module {@code a} — the shared manifest head, then {@code extra}, its main
     * source and its test from {@code lib} — and module {@code b}, plain Java.
     */
    private Path workspace(String extra, Lib lib) throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.matrix"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["a", "b"]
                """);
        module(ws.resolve("a"), "a", extra, lib);
        module(ws.resolve("b"), "b", JAVA_MODULE, Lib.JAVA);
        return ws;
    }

    private static void module(Path dir, String name, String extra, Lib lib) throws IOException {
        Files.createDirectories(dir);
        // The module's own tables first, then the shared ones: TOML keys after a table header
        // belong to that table.
        String head = """
                group   = "com.matrix"
                name    = "%s"
                version = "1.0.0"
                java    = 25
                """.formatted(name);
        String topLevel = extra.contains("[") ? extra.substring(0, extra.indexOf('[')) : extra;
        String tables = extra.contains("[") ? extra.substring(extra.indexOf('[')) : "";
        Files.writeString(dir.resolve("jk.toml"), head + topLevel + """

                [test-dependencies]
                junit-jupiter           = { group = "org.junit.jupiter", name = "junit-jupiter", version = "6.1.3" }
                junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "6.1.3" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"

                """ + tables);
        write(dir.resolve("src/com/example/" + lib.mainFile()), lib.main());
        write(dir.resolve("test/src/com/example/" + lib.testFile()), lib.test());
    }

    /** A module's main source and its test. */
    private record Lib(String mainFile, String main, String testFile, String test) {

        static final Lib JAVA = new Lib("Lib.java", """
                package com.example;

                public final class Lib {
                    private Lib() {}

                    public static String greet() {
                        return "hi";
                    }
                }
                """, "LibTest.java", """
                package com.example;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                class LibTest {
                    @Test
                    void greets() {
                        assertEquals("hi", Lib.greet());
                    }
                }
                """);

        static final Lib JAVA_READING_TEST_ENV = new Lib("Lib.java", JAVA.main(), "LibTest.java", """
                package com.example;

                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.junit.jupiter.api.Assertions.assertNotNull;

                import org.junit.jupiter.api.Test;

                class LibTest {
                    @Test
                    void runs_with_the_plugins_flag_and_classpath() {
                        assertEquals("hi", Lib.greet());
                        assertEquals("on", System.getProperty("stub.contributions"));
                        assertNotNull(LibTest.class.getResource("/stub-contributions.properties"));
                    }
                }
                """);

        static final Lib CLASSLESS = new Lib("package-info.java", """
                /** A package of resources: no type is declared, so javac writes no class file. */
                package com.example;
                """, "ResourceTest.java", """
                package com.example;

                import static org.junit.jupiter.api.Assertions.assertNotNull;

                import org.junit.jupiter.api.Test;

                class ResourceTest {
                    @Test
                    void the_resource_is_on_the_classpath() {
                        assertNotNull(ResourceTest.class.getResource("/manifest.toml"));
                    }
                }
                """);

        static final Lib KOTLIN = new Lib("Lib.kt", """
                package com.example

                object Lib {
                    fun greet(): String = "hi"
                }
                """, "LibTest.kt", """
                package com.example

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                class LibTest {
                    @Test
                    fun greets() {
                        assertEquals("hi", Lib.greet())
                    }
                }
                """);

        static final Lib KOTLIN_READING_GENERATED = new Lib("Lib.kt", """
                package com.example

                object Lib {
                    fun greet(): String = Generated.NAME
                }
                """, "LibTest.kt", """
                package com.example

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                class LibTest {
                    @Test
                    fun greets_with_the_generated_name() {
                        assertEquals("generated", Lib.greet())
                    }
                }
                """);

        static final Lib GROOVY = new Lib("Lib.groovy", """
                package com.example

                class Lib {
                    static String greet() {
                        Generated.NAME
                    }
                }
                """, "LibTest.groovy", """
                package com.example

                import static org.junit.jupiter.api.Assertions.assertEquals

                import org.junit.jupiter.api.Test

                class LibTest {
                    @Test
                    void greets_with_the_generated_name() {
                        assertEquals("generated", Lib.greet())
                    }
                }
                """);

        static final Lib GROOVY_TESTED_IN_JAVA = new Lib("Lib.groovy", """
                package com.example

                class Lib {
                    static String greet() {
                        "hi"
                    }
                }
                """, JAVA.testFile(), JAVA.test());
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private static void jar(Path out, String entry) throws IOException {
        try (OutputStream os = Files.newOutputStream(out);
                JarOutputStream jar = new JarOutputStream(os)) {
            jar.putNextEntry(new JarEntry(entry));
            jar.write(entry.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }

    private static void set(String property, @Nullable String value) {
        if (value == null) System.clearProperty(property);
        else System.setProperty(property, value);
    }
}
