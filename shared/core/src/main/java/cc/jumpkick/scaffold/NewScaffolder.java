// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.scaffold;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Writes the generated project tree from {@link NewInputs}:
 *
 * <ul>
 * <li>{@code jk.toml} via {@link NewJkBuildRenderer}
 * <li>the production + test source roots (see {@link #createSourceTree})
 * <li>optional sample source tree (Java or Kotlin)
 * </ul>
 *
 * <p>No {@code jk-lock.toml} — that's generated on the first build/run.
 *
 * <p>The curated dependency map is the single source of truth for which "short id" maps to which
 * Maven coordinate + version + scope; both the renderer and the wizard's MultiSelect step pull from
 * it.
 *
 * <p>Shared by CLI {@code jk new} and engine {@code POST /api/projects} (JK-1193). Framework plugin
 * scaffolds ({@code --spring}/…​) use {@link FrameworkScaffoldSource} so the engine can call
 * {@code ScaffoldOps} in-process while the CLI still goes over the wire.
 */
public final class NewScaffolder {

    /**
     * Supplies engine-rendered plugin scaffold files (paths + contents). CLI uses ExportSupport;
     * engine uses ScaffoldOps. Null-safe: when no framework flag is set, this is never called.
     */
    @FunctionalInterface
    public interface FrameworkScaffoldSource {
        ScaffoldFiles generate(NewInputs inputs) throws IOException;
    }

    /** Parallel path/content lists from a framework plugin scaffold (same shape as GeneratedFiles). */
    public record ScaffoldFiles(List<String> paths, List<String> contents) {
        public ScaffoldFiles {
            paths = paths == null ? List.of() : List.copyOf(paths);
            contents = contents == null ? List.of() : List.copyOf(contents);
        }
    }

    /** Thread-local / call-scoped source for framework scaffolds. Default: unset (plain only). */
    private static final ThreadLocal<FrameworkScaffoldSource> FRAMEWORK = new ThreadLocal<>();

    /**
     * Per-dep record. {@code version} is the major-version selector that ends up after the {@code @}
     * in the rendered TOML coord (e.g., {@code org.projectlombok:lombok@1}). Floating @-form lets the
     * project pick up patch updates without an explicit bump.
     */
    public record CuratedEntry(String coord, String version, String scope) {}

    /**
     * Curated dependency catalog. The {@code version} is intentionally a bare major (e.g. {@code
     * "1"}); the renderer emits the {@code @}-form so the resolver treats it as a floating caret
     * selector ({@code ^1} → 1.x.x).
     */
    public static final Map<String, List<CuratedEntry>> CURATED_DEPS = Map.of(
            "lombok",
                    List.of(
                            new CuratedEntry("org.projectlombok:lombok", "1", "processor"),
                            new CuratedEntry("org.projectlombok:lombok", "1", "provided")),
            "jspecify", List.of(new CuratedEntry("org.jspecify:jspecify", "1", "main")),
            "commons-lang", List.of(new CuratedEntry("org.apache.commons:commons-lang3", "3", "main")),
            "commons-io", List.of(new CuratedEntry("commons-io:commons-io", "2", "main")),
            "guava", List.of(new CuratedEntry("com.google.guava:guava", "33", "main")),
            "kotest", List.of(new CuratedEntry("io.kotest:kotest-runner-junit6", "6", "test")));

    private NewScaffolder() {}

    public static void write(NewInputs inputs) throws IOException {
        write(inputs, true, null);
    }

    public static void write(NewInputs inputs, boolean standalone) throws IOException {
        write(inputs, standalone, null);
    }

    /**
     * Scaffold the project tree. {@code standalone} is false for a workspace module, whose {@code
     * .gitignore} is owned by the workspace root and so is skipped here (Cargo/uv: modules never
     * carry their own gitignore).
     *
     * <p>No {@code jk-lock.toml} is written — it's generated on the first build or run, so a
     * freshly-scaffolded project carries only its manifest + sources.
     *
     * <p>A plugin scaffold ({@code --spring}) fetches its payloads BEFORE anything touches disk via
     * {@code frameworkSource}: the engine renders the plugin's {@code [scaffold]} data, and a
     * failure leaves no half-written project behind.
     */
    public static void write(NewInputs inputs, boolean standalone, FrameworkScaffoldSource frameworkSource)
            throws IOException {
        if (inputs.plugin()) {
            writePluginProject(inputs, standalone);
            return;
        }
        var dir = inputs.directory();
        ScaffoldFiles plugin = null;
        if (inputs.frameworkScaffold()) {
            FrameworkScaffoldSource src = frameworkSource != null ? frameworkSource : FRAMEWORK.get();
            if (src == null) {
                throw new IOException(
                        "jk new: framework scaffold ("
                                + inputs.frameworkPluginFlag()
                                + ") requires an engine FrameworkScaffoldSource");
            }
            plugin = src.generate(inputs);
            if (plugin == null) throw new IOException("jk new: plugin scaffold failed");
        }

        Files.createDirectories(dir);
        if (plugin != null) {
            for (int i = 0; i < plugin.paths().size(); i++) {
                Path target = Path.of(plugin.paths().get(i));
                if (target.getParent() != null) Files.createDirectories(target.getParent());
                Files.writeString(target, plugin.contents().get(i), StandardCharsets.UTF_8);
            }
        } else {
            Files.writeString(dir.resolve("jk.toml"), NewJkBuildRenderer.render(inputs), StandardCharsets.UTF_8);
        }

        if (standalone) {
            writeGitignore(dir); // modules inherit the workspace root's.gitignore
        }

        createSourceTree(inputs);

        if (plugin == null && inputs.sample()) {
            writeSample(inputs);
        }
    }

    /**
     * Run {@code action} with a thread-local framework scaffold source (CLI ExportSupport adapter).
     */
    public static void withFrameworkSource(FrameworkScaffoldSource source, IoRunnable action) throws IOException {
        FrameworkScaffoldSource prev = FRAMEWORK.get();
        FRAMEWORK.set(source);
        try {
            action.run();
        } finally {
            if (prev == null) FRAMEWORK.remove();
            else FRAMEWORK.set(prev);
        }
    }

    @FunctionalInterface
    public interface IoRunnable {
        void run() throws IOException;
    }

    // jk new --plugin: a build-plugin AUTHORING project (a fat jar carrying jk-plugin.toml at its
    // root, whose Main-Class is the SDK's PluginMain). Fully client-side and framework-free — the
    // opposite direction from pluginScaffold above (a plugin CONTRIBUTING a `jk new --<flag>`).

    private static void writePluginProject(NewInputs inputs, boolean standalone) throws IOException {
        Path dir = inputs.directory();
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("jk.toml"), NewJkBuildRenderer.render(inputs), StandardCharsets.UTF_8);
        if (standalone) {
            writeGitignore(dir);
        }

        String pkg = inputs.group();
        String pluginId = inputs.name();
        String className = pluginClassName(pluginId);
        String prefix = protocolPrefix(pluginId);
        boolean kotlin = inputs.lang() == NewInputs.Language.KOTLIN;

        // src/main/resources → packaged to the jar ROOT: the manifest jk reads, and the
        // ServiceLoader registration PluginMain uses to find the plugin.
        Path resources = dir.resolve("src").resolve("main").resolve("resources");
        Path services = resources.resolve("META-INF").resolve("services");
        Files.createDirectories(services);
        Files.writeString(
                resources.resolve("jk-plugin.toml"), renderPluginManifest(pluginId, prefix), StandardCharsets.UTF_8);
        Files.writeString(
                services.resolve("cc.jumpkick.plugin.Plugin"), pkg + "." + className + "\n", StandardCharsets.UTF_8);

        // The code layer.
        String pkgPath = "/" + pkg.replace('.', '/');
        String lang = kotlin ? "kotlin" : "java";
        Path src = dir.resolve("src").resolve("main").resolve(lang + pkgPath);
        Files.createDirectories(src);
        String ext = kotlin ? ".kt" : ".java";
        String body = kotlin
                ? renderKotlinPlugin(pkg, className, pluginId, prefix)
                : renderJavaPlugin(pkg, className, pluginId, prefix);
        Files.writeString(src.resolve(className + ext), body, StandardCharsets.UTF_8);

        Files.writeString(
                dir.resolve("README.md"), renderPluginReadme(inputs, pluginId, className), StandardCharsets.UTF_8);
    }

    /** CamelCase the plugin id into a class name, appending {@code Plugin} unless it already ends so. */
    private static String pluginClassName(String id) {
        StringBuilder sb = new StringBuilder();
        boolean up = true;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(up ? Character.toUpperCase(c) : c);
                up = false;
            } else {
                up = true;
            }
        }
        String base = sb.length() == 0 ? "My" : sb.toString();
        return base.endsWith("Plugin") ? base : base + "Plugin";
    }

    /** The worker protocol-line marker, e.g. {@code foo} → {@code ##FOO:}. Alnum-only, uppercased. */
    private static String protocolPrefix(String id) {
        StringBuilder sb = new StringBuilder("##");
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (Character.isLetterOrDigit(c)) sb.append(Character.toUpperCase(c));
        }
        if (sb.length() == 2) sb.append("PLUGIN");
        return sb.append(':').toString();
    }

    private static String renderPluginManifest(String id, String prefix) {
        return """
                # The declarative manifest for the `%1$s` build plugin — pure data jk parses itself
                # (no plugin code runs in the engine). This file is packaged at the jar root. See
                # docs/authoring-plugins.md for the full surface: [[contribute.*]] build shaping,
                # [packaging], [scaffold] (contribute a `jk new --<flag>`), [[import.*]].

                [plugin]
                id        = "%1$s"        # this plugin's identity
                table     = "%1$s"        # the jk.toml table it owns
                version   = "0.1.0"
                jk-compat = ">=0.10"      # the jk versions this plugin supports

                # Typed keys for the [%1$s] table; jk validates the user's table against this for you,
                # then hands your code the defaulted, validated config — never raw TOML.
                [schema]
                greeting = { type = "string", default = "hello", hint = "text the `%1$s` command prints" }

                # The code layer runs in a forked worker JVM. This prefix MUST equal the one in
                # %1$s's manifest — they demux the worker's protocol lines from its stdout.
                [code]
                protocol-prefix = "%2$s"
                """.formatted(id, prefix);
    }

    private static String renderJavaPlugin(String pkg, String className, String id, String prefix) {
        return """
                // SPDX-License-Identifier: Apache-2.0
                package %1$s;

                import cc.jumpkick.plugin.Plugin;
                import cc.jumpkick.plugin.PluginManifest;
                import cc.jumpkick.plugin.build.BuildPlugin;
                import cc.jumpkick.plugin.build.BuildPluginContext;
                import cc.jumpkick.plugin.build.BuildPluginHarness;
                import cc.jumpkick.plugin.build.PluginCommandSpec;
                import cc.jumpkick.plugin.protocol.ProtocolWriter;
                import java.util.List;

                /**
                 * A jk build plugin. Compiles against cc.jumpkick:jk-plugin-sdk, ships as a fat jar
                 * whose Main-Class is PluginMain (see jk.toml), and is discovered via
                 * META-INF/services/cc.jumpkick.plugin.Plugin. Docs: docs/authoring-plugins.md.
                 */
                public final class %2$s implements Plugin, BuildPlugin {

                    // Identity + protocol-line prefix. MUST match [code] protocol-prefix in jk-plugin.toml.
                    @Override
                    public PluginManifest manifest() {
                        return new PluginManifest("%3$s", "%4$s");
                    }

                    // Worker entry point: the harness speaks the describe / run-step / command protocol.
                    @Override
                    public int run(List<String> args, ProtocolWriter out) throws Exception {
                        return BuildPluginHarness.run(this, args, out);
                    }

                    // Register steps, packagers, and commands. This sample adds a `jk %3$s` command
                    // that prints the [%3$s] table's `greeting` (defaulted by the schema).
                    @Override
                    public void register(BuildPluginContext ctx) {
                        ctx.command(PluginCommandSpec.named("%3$s")
                                .description("Print the configured greeting")
                                .run(exec -> {
                                    exec.out(exec.config().string("greeting") + " from the %3$s plugin");
                                    return 0;
                                }));
                    }
                }
                """.formatted(pkg, className, id, prefix);
    }

    private static String renderKotlinPlugin(String pkg, String className, String id, String prefix) {
        return """
                // SPDX-License-Identifier: Apache-2.0
                package %1$s

                import cc.jumpkick.plugin.Plugin
                import cc.jumpkick.plugin.PluginManifest
                import cc.jumpkick.plugin.build.BuildPlugin
                import cc.jumpkick.plugin.build.BuildPluginContext
                import cc.jumpkick.plugin.build.BuildPluginHarness
                import cc.jumpkick.plugin.build.PluginCommandSpec
                import cc.jumpkick.plugin.protocol.ProtocolWriter

                /**
                 * A jk build plugin. Compiles against cc.jumpkick:jk-plugin-sdk, ships as a fat jar
                 * whose Main-Class is PluginMain (see jk.toml), and is discovered via
                 * META-INF/services/cc.jumpkick.plugin.Plugin. Docs: docs/authoring-plugins.md.
                 */
                class %2$s : Plugin, BuildPlugin {

                    // Identity + protocol-line prefix. MUST match [code] protocol-prefix in jk-plugin.toml.
                    override fun manifest() = PluginManifest("%3$s", "%4$s")

                    // Worker entry point: the harness speaks the describe / run-step / command protocol.
                    override fun run(args: List<String>, out: ProtocolWriter): Int =
                        BuildPluginHarness.run(this, args, out)

                    // Register steps, packagers, and commands. This sample adds a `jk %3$s` command
                    // that prints the [%3$s] table's `greeting` (defaulted by the schema).
                    override fun register(ctx: BuildPluginContext) {
                        ctx.command(PluginCommandSpec.named("%3$s")
                            .description("Print the configured greeting")
                            .run { exec ->
                                exec.out(exec.config().string("greeting") + " from the %3$s plugin")
                                0
                            })
                    }
                }
                """.formatted(pkg, className, id, prefix);
    }

    private static String renderPluginReadme(NewInputs inputs, String id, String className) {
        return """
                # %1$s — a jk build plugin

                Built with `jk new --plugin`. A build plugin teaches jk a new `jk.toml` table
                (here `[%1$s]`) and shapes the standard commands around it. Full guide:
                docs/authoring-plugins.md.

                # # Layout
                - `jk-plugin.toml` (under `src/main/resources/`, so it lands at the **jar root**) —
                  the declarative manifest jk reads: the `[%1$s]` schema and the code hook.
                - `%2$s` — the code layer: implements the SDK's `Plugin` + `BuildPlugin`, registered
                  via `META-INF/services/cc.jumpkick.plugin.Plugin`.
                - `jk.toml` — depends on `cc.jumpkick:jk-plugin-sdk` and packages a **fat jar**
                  (`assembly = true`) whose `Main-Class` is the SDK's `PluginMain`. The SDK must be
                  shaded IN (the worker forks as `java -jar`), so the dep is a normal `main` dep.

                # # Build
                ```
                jk build
                ```
                Produces `target/%1$s-0.1.0-all.jar` — the fat jar with `jk-plugin.toml` at its root.

                # # Publish, declare, trust
                ```
                jk publish                 # ships the fat jar as the coordinate
                ```
                In a consumer project:
                ```toml
                [plugins]
                %1$s = { group = "%3$s", name = "%1$s", version = "0.1.0" }

                [%1$s]
                greeting = "hey"
                ```
                Then `jk sync` (resolves + SHA-pins the plugin), `jk trust plugin %3$s:%1$s`
                (code hooks are consent-gated), and `jk %1$s` prints the greeting.
                """.formatted(id, className, inputs.group());
    }

    /**
     * Ensure production/test/resource roots exist: SIMPLE Mill-like ({@code src/},
     * {@code resources/}, {@code test/src/}, {@code test/resources/}) or traditional Maven tree
     *
     */
    private static void createSourceTree(NewInputs inputs) throws IOException {
        var dir = inputs.directory();
        if (inputs.isSimpleLayout()) {
            Files.createDirectories(dir.resolve("src"));
            Files.createDirectories(dir.resolve("resources"));
            Files.createDirectories(dir.resolve("test").resolve("src"));
            Files.createDirectories(dir.resolve("test").resolve("resources"));
        } else {
            String lang = inputs.lang().sourceDir();
            Files.createDirectories(dir.resolve("src").resolve("main").resolve(lang));
            Files.createDirectories(dir.resolve("src").resolve("main").resolve("resources"));
            Files.createDirectories(dir.resolve("src").resolve("test").resolve(lang));
            Files.createDirectories(dir.resolve("src").resolve("test").resolve("resources"));
        }
    }

    /**
     * Seed a {@code.gitignore} covering jk's outputs. Don't clobber an existing file — the user (or
     * their template) may have customised it. We only create one on first scaffold.
     */
    private static void writeGitignore(Path dir) throws IOException {
        Path gitignore = dir.resolve(".gitignore");
        if (Files.exists(gitignore)) return;
        Files.writeString(gitignore, """
                # jk build outputs
                target/
                .jk/

                # IntelliJ IDEA
                .idea/
                * .iml
                * .ipr
                * .iws
                out/

                # VS Code
                .vscode/
                * .code-workspace
                .history/

                # Eclipse compiler (used by VS Code Java plugins)
                .classpath
                .project
                .settings/
                .factorypath
                **/src/**/bin/
                **/test/**/bin/
                """, StandardCharsets.UTF_8);
    }

    /** Class name used for the always-generated Main file. */
    private static final String MAIN_CLASS = "Main";

    /** First JDK feature release that supports instance main + implicit IO import. */
    private static final int JAVA_INSTANCE_MAIN_MIN = 25;

    /**
     * Sample sources mirroring jk's reference layout: a {@code Calc} class with a JUnit {@code
     * CalcTest}, plus a {@code Main} entry point for runnable projects. The package follows the
     * project group; the test relies on the JUnit jk defaults in when no test framework is declared.
     */
    private static void writeSample(NewInputs inputs) throws IOException {
        switch (inputs.lang()) {
            case JAVA -> writeJavaSample(inputs);
            case KOTLIN -> writeKotlinSample(inputs);
            case GROOVY -> writeGroovySample(inputs);
        }
    }

    private static void writeJavaSample(NewInputs inputs) throws IOException {
        String pkg = inputs.group();
        String pkgPath = "/" + pkg.replace('.', '/');
        Path srcDir = inputs.directory().resolve(mainSourceRoot(inputs) + pkgPath);
        Path testDir = inputs.directory().resolve(testSourceRoot(inputs) + pkgPath);
        Files.createDirectories(srcDir);
        Files.createDirectories(testDir);

        Files.writeString(srcDir.resolve("Calc.java"), renderJavaCalc(pkg), StandardCharsets.UTF_8);
        Files.writeString(testDir.resolve("CalcTest.java"), renderJavaCalcTest(pkg), StandardCharsets.UTF_8);
        if (inputs.isRunnable()) {
            // Gate the instance-main syntax on the compile target, not the toolchain.
            boolean instanceMain = inputs.javaRelease() >= JAVA_INSTANCE_MAIN_MIN;
            Files.writeString(
                    srcDir.resolve(MAIN_CLASS + ".java"), renderJavaMain(pkg, instanceMain), StandardCharsets.UTF_8);
        }
    }

    private static void writeKotlinSample(NewInputs inputs) throws IOException {
        // Kotlin keeps its compact convention: the simple layout is package-less
        // (files at./src and./test/src); the traditional layout nests by package.
        boolean simple = inputs.isSimpleLayout();
        String pkg = simple ? "" : inputs.group();
        String pkgPath = pkg.isEmpty() ? "" : "/" + pkg.replace('.', '/');
        Path srcDir = inputs.directory().resolve((simple ? "src" : "src/main/kotlin") + pkgPath);
        Path testDir = inputs.directory().resolve((simple ? "test/src" : "src/test/kotlin") + pkgPath);
        Files.createDirectories(srcDir);
        Files.createDirectories(testDir);

        Files.writeString(srcDir.resolve("Calc.kt"), renderKotlinCalc(pkg), StandardCharsets.UTF_8);
        Files.writeString(testDir.resolve("CalcTest.kt"), renderKotlinCalcTest(pkg), StandardCharsets.UTF_8);
        if (inputs.isRunnable()) {
            Files.writeString(srcDir.resolve(MAIN_CLASS + ".kt"), renderKotlinMain(pkg), StandardCharsets.UTF_8);
        }
    }

    private static void writeGroovySample(NewInputs inputs) throws IOException {
        // Groovy mirrors Kotlin's compact convention: the simple layout is package-less
        // (files at./src and./test/src); the traditional layout nests by package.
        boolean simple = inputs.isSimpleLayout();
        String pkg = simple ? "" : inputs.group();
        String pkgPath = pkg.isEmpty() ? "" : "/" + pkg.replace('.', '/');
        Path srcDir = inputs.directory().resolve((simple ? "src" : "src/main/groovy") + pkgPath);
        Path testDir = inputs.directory().resolve((simple ? "test/src" : "src/test/groovy") + pkgPath);
        Files.createDirectories(srcDir);
        Files.createDirectories(testDir);

        Files.writeString(srcDir.resolve("Calc.groovy"), renderGroovyCalc(pkg), StandardCharsets.UTF_8);
        Files.writeString(testDir.resolve("CalcTest.groovy"), renderGroovyCalcTest(pkg), StandardCharsets.UTF_8);
        if (inputs.isRunnable()) {
            Files.writeString(srcDir.resolve(MAIN_CLASS + ".groovy"), renderGroovyMain(pkg), StandardCharsets.UTF_8);
        }
    }

    /** Production source root: {@code src} (simple) or {@code src/main/<lang>} (traditional). */
    private static String mainSourceRoot(NewInputs inputs) {
        return inputs.isSimpleLayout() ? "src" : "src/main/" + inputs.lang().sourceDir();
    }

    /** Test source root: {@code test/src} (simple Mill-like) or {@code src/test/<lang>} (traditional). */
    private static String testSourceRoot(NewInputs inputs) {
        return inputs.isSimpleLayout()
                ? "test/src"
                : "src/test/" + inputs.lang().sourceDir();
    }

    /**
     * Entry point referencing the sample {@code Calc}. JDK 25+ gets the JEP 512 instance {@code main}
     * with the implicit {@code IO} import; older targets get a classic static {@code main}.
     */
    private static String renderJavaMain(String pkg, boolean instanceMain) {
        if (instanceMain) {
            return """
                    package %s;

                    class Main {
                        void main() {
                            int value = 5;
                            Calc calc = new Calc();
                            IO.println("Hello, world! 5 * 2 = " + calc.doubleValue(value));
                        }
                    }
                    """.formatted(pkg);
        }
        return """
                package %s;

                class Main {
                    public static void main(String... args) {
                        int value = 5;
                        Calc calc = new Calc();
                        System.out.println("Hello, world! 5 * 2 = " + calc.doubleValue(value));
                    }
                }
                """.formatted(pkg);
    }

    private static String renderJavaCalc(String pkg) {
        return """
                package %s;

                public class Calc {
                    public int doubleValue(int value) {
                        return value * 2;
                    }
                }
                """.formatted(pkg);
    }

    private static String renderJavaCalcTest(String pkg) {
        return """
                package %s;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                public class CalcTest {
                    @Test
                    void doubleValueReturnsTwiceTheInput() {
                        Calc calc = new Calc();
                        assertEquals(10, calc.doubleValue(5));
                    }
                }
                """.formatted(pkg);
    }

    private static String renderKotlinMain(String pkg) {
        return pkgHeaderKt(pkg) + """
                fun main() {
                    val value = 5
                    val calc = Calc()
                    println("Hello, world! 5 * 2 = ${calc.doubleValue(value)}")
                }
                """;
    }

    private static String renderKotlinCalc(String pkg) {
        return pkgHeaderKt(pkg) + """
                class Calc {
                    fun doubleValue(value: Int): Int = value * 2
                }
                """;
    }

    private static String renderKotlinCalcTest(String pkg) {
        return pkgHeaderKt(pkg) + """
                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                class CalcTest {
                    @Test
                    fun doubleValueReturnsTwiceTheInput() {
                        assertEquals(10, Calc().doubleValue(5))
                    }
                }
                """;
    }

    private static String renderGroovyMain(String pkg) {
        return pkgHeaderKt(pkg) + """
                class Main {
                    static void main(String[] args) {
                        def value = 5
                        def calc = new Calc()
                        println "Hello, world! 5 * 2 = ${calc.doubleValue(value)}"
                    }
                }
                """;
    }

    private static String renderGroovyCalc(String pkg) {
        return pkgHeaderKt(pkg) + """
                class Calc {
                    int doubleValue(int value) {
                        value * 2
                    }
                }
                """;
    }

    private static String renderGroovyCalcTest(String pkg) {
        return pkgHeaderKt(pkg) + """
                import org.junit.jupiter.api.Test

                import static org.junit.jupiter.api.Assertions.assertEquals

                class CalcTest {
                    @Test
                    void doubleValueReturnsTwiceTheInput() {
                        assertEquals(10, new Calc().doubleValue(5))
                    }
                }
                """;
    }

    /** {@code "package <pkg>\n\n"}, or empty for the package-less (compact Kotlin/Groovy) case. */
    private static String pkgHeaderKt(String pkg) {
        return pkg.isEmpty() ? "" : "package " + pkg + "\n\n";
    }
}
