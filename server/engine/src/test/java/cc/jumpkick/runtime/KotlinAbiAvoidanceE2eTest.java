// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.wire.runtime.ModulePlan;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end compile avoidance for a Kotlin consumer: a dependency rewritten with the same ABI
 * leaves the consumer's {@code compile-kotlin} an action-cache hit; a dependency whose ABI moved
 * — a public function added, an inline body or a {@code const val} changed, all of which the
 * Kotlin classpath snapshot covers and a JVM-signature ABI would not — misses, and what the
 * consumer then compiles is the new dependency.
 *
 * <p>The proof is the consumer's action key: the record its {@code compile-kotlin} task points at
 * keeps the same key across a body-only rebuild of the dependency and moves otherwise. The step's
 * last label says which path the build took ({@code cache hit …} against {@code compiling …}).
 *
 * <p>Network: the Kotlin compiler comes from Maven Central into the cache under {@code build/},
 * which persists across runs so repeats are warm.
 */
@Tag("integration")
class KotlinAbiAvoidanceE2eTest {

    @Test
    void a_kotlin_consumer_recompiles_only_when_the_dependencys_abi_moves(@TempDir Path tmp) throws Exception {
        Path cache = cache();
        Path ws = kotlinWorkspace(tmp);
        lock(ws, cache);

        Labels first = new Labels();
        assertThat(build(ws, cache, first).success()).as("first build").isTrue();
        String initial = appCompileKey(ws, cache);
        assertThat(first.label("app", TaskNames.COMPILE_KOTLIN)).startsWith("compiling");

        // Body only: twice() computes the same thing another way. Nothing app compiles against moved.
        lib(ws, "fun twice(n: Int): Int = n + n", INLINE_V1, CONST_V1, "");
        Labels bodyOnly = new Labels();
        assertThat(build(ws, cache, bodyOnly).success()).isTrue();
        assertThat(bodyOnly.label("lib", TaskNames.COMPILE_KOTLIN)).startsWith("compiling");
        assertThat(bodyOnly.label("app", TaskNames.COMPILE_KOTLIN))
                .as("app's compile-kotlin is a cache hit: lib's jar changed, its ABI did not")
                .startsWith("cache hit");
        assertThat(appCompileKey(ws, cache)).isEqualTo(initial);

        // An inline function body is ABI for kotlinc: it is copied into every call site.
        lib(ws, "fun twice(n: Int): Int = n + n", "inline fun thrice(n: Int): Int = n + n + n", CONST_V1, "");
        Labels inlineMoved = new Labels();
        assertThat(build(ws, cache, inlineMoved).success()).isTrue();
        assertThat(inlineMoved.label("app", TaskNames.COMPILE_KOTLIN)).startsWith("compiling");
        String afterInline = appCompileKey(ws, cache);
        assertThat(afterInline).isNotEqualTo(initial);

        // A const val is ABI too, and the consumer must carry the new value.
        lib(ws, "fun twice(n: Int): Int = n + n", "inline fun thrice(n: Int): Int = n + n + n", CONST_V2, "");
        Labels constMoved = new Labels();
        assertThat(build(ws, cache, constMoved).success()).isTrue();
        assertThat(constMoved.label("app", TaskNames.COMPILE_KOTLIN)).startsWith("compiling");
        String afterConst = appCompileKey(ws, cache);
        assertThat(afterConst).isNotEqualTo(afterInline);
        assertThat(new String(Files.readAllBytes(appClass(ws)), StandardCharsets.ISO_8859_1))
                .as("App.name() inlines Lib.NAME: the recompiled class carries the new constant")
                .contains("lib-v2")
                .doesNotContain("lib-v1");

        // A public function app never calls still moves the snapshot, so the key misses even
        // though the incremental compile may find nothing to recompile.
        lib(
                ws,
                "fun twice(n: Int): Int = n + n",
                "inline fun thrice(n: Int): Int = n + n + n",
                CONST_V2,
                "fun quadruple(n: Int): Int = n * 4");
        Labels apiAdded = new Labels();
        assertThat(build(ws, cache, apiAdded).success()).isTrue();
        assertThat(apiAdded.label("app", TaskNames.COMPILE_KOTLIN)).startsWith("compiling");
        assertThat(appCompileKey(ws, cache)).isNotEqualTo(afterConst);
    }

    @Test
    void a_java_only_dependency_is_snapshotted_so_a_kotlin_consumer_avoids(@TempDir Path tmp) throws Exception {
        Path cache = cache();
        Path ws = javaLibWorkspace(tmp);
        lock(ws, cache);

        Labels first = new Labels();
        assertThat(build(ws, cache, first).success()).as("first build").isTrue();
        String initial = appCompileKey(ws, cache);

        Files.writeString(ws.resolve("lib/src/com/example/Lib.java"), """
                package com.example;

                public final class Lib {
                    private Lib() {}

                    public static int twice(int n) {
                        return n + n;
                    }
                }
                """);
        Labels bodyOnly = new Labels();
        assertThat(build(ws, cache, bodyOnly).success()).isTrue();
        assertThat(bodyOnly.label("lib", TaskNames.COMPILE_MAIN)).doesNotStartWith("up to date");
        assertThat(bodyOnly.label("app", TaskNames.COMPILE_KOTLIN))
                .as("a Java-only sibling still gets a Kotlin snapshot, so the Kotlin consumer avoids")
                .startsWith("cache hit");
        assertThat(appCompileKey(ws, cache)).isEqualTo(initial);
    }

    /**
     * A mixed module's Kotlin compile reads the module's own Java declarations through {@code
     * -Xjava-source-roots}: a Java body-only edit is a cache hit, a Java signature edit misses and
     * the Kotlin output that ships was compiled against the new declaration.
     */
    @Test
    void a_mixed_modules_kotlin_compile_follows_its_java_declarations(@TempDir Path tmp) throws Exception {
        Path cache = cache();
        Path ws = mixedWorkspace(tmp);
        lock(ws, cache);
        Path util = ws.resolve("app/src/com/example/Util.java");

        Labels first = new Labels();
        assertThat(build(ws, cache, first).success()).as("first build").isTrue();
        assertThat(first.label("app", TaskNames.COMPILE_KOTLIN)).startsWith("compiling");
        byte[] appInitial = Files.readAllBytes(appClass(ws));

        Files.writeString(util, MIXED_UTIL.replace("return n * 2;", "return n + n;"));
        Labels bodyOnly = new Labels();
        assertThat(build(ws, cache, bodyOnly).success()).isTrue();
        assertThat(bodyOnly.label("app", TaskNames.COMPILE_KOTLIN))
                .as("a Java body-only edit changes nothing kotlinc reads")
                .startsWith("cache hit");

        Files.writeString(util, MIXED_UTIL.replace("public static int twice", "public static long twice"));
        Labels signature = new Labels();
        assertThat(build(ws, cache, signature).success())
                .as("build after the Java signature edit")
                .isTrue();
        assertThat(signature.label("app", TaskNames.COMPILE_KOTLIN))
                .as("a Java signature edit is a kotlinc input")
                .startsWith("compiling");
        assertThat(Files.readAllBytes(appClass(ws)))
                .as("App.six() now returns Util.twice's new type: the class was compiled against the new declaration")
                .isNotEqualTo(appInitial);
    }

    // ---- fixture -----------------------------------------------------------------------------

    private static final String MIXED_UTIL = """
            package com.example;

            public final class Util {
                private Util() {}

                public static int twice(int n) {
                    return n * 2;
                }
            }
            """;

    /** One module with Java and Kotlin side by side; the Kotlin side calls the Java side. */
    private static Path mixedWorkspace(Path tmp) throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["app"]
                """);
        Path app = Files.createDirectories(ws.resolve("app"));
        Files.writeString(app.resolve("jk.toml"), """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                java    = 25
                kotlin  = "^2.4.10"

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Files.createDirectories(app.resolve("src/com/example"));
        Files.writeString(app.resolve("src/com/example/Util.java"), MIXED_UTIL);
        Files.writeString(app.resolve("src/com/example/App.kt"), """
                package com.example

                object App {
                    fun six() = Util.twice(3)
                }
                """);
        return ws;
    }

    private static final String INLINE_V1 = "inline fun thrice(n: Int): Int = n * 3";
    private static final String CONST_V1 = "const val NAME = \"lib-v1\"";
    private static final String CONST_V2 = "const val NAME = \"lib-v2\"";

    private static Path cache() {
        return Path.of(System.getProperty("user.dir"), "build", "android-spike-cache");
    }

    /** The action key app's {@code compile-kotlin} record points at, straight from the cache. */
    private static String appCompileKey(Path ws, Path cache) throws IOException {
        ActionCache ac = new ActionCache(JkStores.cacheCas(cache), CacheTree.ACTIONS.under(cache));
        String taskId = ActionKey.qualifiedTaskId(
                TaskNames.COMPILE_KOTLIN, appLayout(ws).classesDir());
        ActionCache.ActionRecord record =
                ac.lastFor(taskId).orElseThrow(() -> new AssertionError("no compile-kotlin record for app"));
        return Objects.requireNonNull(record.actionKey(), "the record names its key");
    }

    private static Path appClass(Path ws) throws IOException {
        return appLayout(ws).classesDir().resolve("com/example/App.class");
    }

    private static BuildLayout appLayout(Path ws) throws IOException {
        return BuildLayout.of(ws, ws.resolve("app"), JkBuildParser.parse(ws.resolve("app/jk.toml")));
    }

    private static void lock(Path ws, Path cache) throws Exception {
        JkBuild root = JkBuildParser.parse(ws.resolve("jk.toml"));
        BuildPlan lock =
                LockPlans.lockBuildPlan(ws, root, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("workspace lock").isTrue();
        // Members redirect to the root lock.
        for (String member : List.of("lib", "app")) {
            if (Files.isDirectory(ws.resolve(member))) {
                Files.copy(ws.resolve("jk-lock.toml"), ws.resolve(member).resolve("jk-lock.toml"));
            }
        }
    }

    private static WorkspaceResult build(Path ws, Path cache, Labels labels) {
        return WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(ws, cache, null, 0, null, false, false, 2, null, false, false), labels);
    }

    /** The last label each step of each module reported. */
    private static final class Labels implements WorkspaceBuildListener {
        private final Map<String, String> last = new LinkedHashMap<>();

        @Override
        public BuildPlanListener onModuleStart(ModulePlan module) {
            String name = module.dir().getFileName().toString();
            return new BuildPlanListener() {
                @Override
                public void label(String step, String label) {
                    synchronized (Labels.this) {
                        last.put(name + "/" + step, label);
                    }
                }
            };
        }

        synchronized String label(String module, String step) {
            return last.getOrDefault(module + "/" + step, "");
        }
    }

    private static void lib(Path ws, String twice, String thrice, String constant, String extra) throws IOException {
        Files.writeString(ws.resolve("lib/src/com/example/Lib.kt"), """
                package com.example

                object Lib {
                    %s
                    %s
                    %s
                    %s
                }
                """.formatted(constant, twice, thrice, extra));
    }

    private static void workspaceRoot(Path ws) throws IOException {
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["lib", "app"]
                """);
    }

    private static void kotlinApp(Path ws, String body) throws IOException {
        Path app = Files.createDirectories(ws.resolve("app"));
        Files.writeString(app.resolve("jk.toml"), """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                java    = 25
                kotlin  = "^2.4.10"

                [dependencies]
                lib = { workspace = true }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Files.createDirectories(app.resolve("src/com/example"));
        Files.writeString(app.resolve("src/com/example/App.kt"), body);
    }

    /** lib is Kotlin; app uses its function, its inline function and its constant. */
    private static Path kotlinWorkspace(Path tmp) throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        workspaceRoot(ws);
        Path lib = Files.createDirectories(ws.resolve("lib"));
        Files.writeString(lib.resolve("jk.toml"), """
                group   = "com.example"
                name    = "lib"
                version = "1.0.0"
                java    = 25
                kotlin  = "^2.4.10"

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Files.createDirectories(lib.resolve("src/com/example"));
        lib(ws, "fun twice(n: Int): Int = n * 2", INLINE_V1, CONST_V1, "");
        kotlinApp(ws, """
                package com.example

                object App {
                    fun name(): String = Lib.NAME
                    fun six(): Int = Lib.twice(3)
                    fun nine(): Int = Lib.thrice(3)
                }
                """);
        return ws;
    }

    /** lib is Java only; app is Kotlin and calls into it. */
    private static Path javaLibWorkspace(Path tmp) throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        workspaceRoot(ws);
        Path lib = Files.createDirectories(ws.resolve("lib"));
        Files.writeString(lib.resolve("jk.toml"), """
                group   = "com.example"
                name    = "lib"
                version = "1.0.0"
                java    = 25

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Files.createDirectories(lib.resolve("src/com/example"));
        Files.writeString(lib.resolve("src/com/example/Lib.java"), """
                package com.example;

                public final class Lib {
                    private Lib() {}

                    public static int twice(int n) {
                        return n * 2;
                    }
                }
                """);
        kotlinApp(ws, """
                package com.example

                object App {
                    fun six(): Int = Lib.twice(3)
                }
                """);
        return ws;
    }
}
