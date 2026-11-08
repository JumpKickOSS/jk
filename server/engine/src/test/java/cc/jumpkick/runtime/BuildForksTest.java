// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.kotlin.KotlinResolver;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.runtime.base.DokkaResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What a build forks, as the planner reads the modules: nothing is staged that the build would not use. */
class BuildForksTest {

    @AfterEach
    void forgetFakeForks() {
        BuildForks.forgetStaged(); // the fakes below must not stand for real forks in later tests
    }

    @TempDir
    Path tmp;

    private Path module(String name, String manifest, @Nullable String source) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve(name));
        Files.writeString(dir.resolve("jk.toml"), manifest);
        if (source != null) {
            Path file = dir.resolve(source);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "// source\n");
        }
        return dir;
    }

    private List<String> keys(Map<Path, JkBuild> modules) {
        return BuildForks.of(modules, Lockfile.empty("test"), tmp.resolve("jk-lock.toml"), new Cas(tmp.resolve("cas")))
                .stream()
                .map(BuildForks.Fork::key)
                .toList();
    }

    private static JkBuild parse(Path dir) throws Exception {
        return JkBuildParser.parse(dir.resolve("jk.toml"));
    }

    @Test
    void a_java_module_forks_the_compiler_and_the_test_runner() throws Exception {
        Path app = module("app", """
                group = "g"
                name = "app"
                version = "1.0"
                java = 25
                """, "src/main/java/g/App.java");

        assertThat(keys(Map.of(app, parse(app)))).containsExactly("worker:jk-java-compiler", "worker:jk-test-runner");
    }

    @Test
    void a_kotlin_module_also_loads_its_toolchain_once_however_many_modules_share_it() throws Exception {
        String manifest = """
                group = "g"
                name = "%s"
                version = "1.0"
                java = 25
                kotlin = "=2.1.0"
                """;
        Path a = module("a", manifest.formatted("a"), "src/main/kotlin/g/A.kt");
        Path b = module("b", manifest.formatted("b"), "src/main/kotlin/g/B.kt");
        Map<Path, JkBuild> modules = new LinkedHashMap<>();
        modules.put(a, parse(a));
        modules.put(b, parse(b));

        // A plain build writes no javadoc jar for a library that left javadoc unset: no Dokka.
        assertThat(keys(modules))
                .containsExactly(
                        "worker:jk-java-compiler",
                        "worker:jk-test-runner",
                        "worker:jk-kotlin-compiler",
                        "kotlin:" + KotlinResolver.floored("2.1.0"));
        // A build that asks for docs: both are libraries, their javadoc jar is Dokka's, staged once.
        assertThat(SessionContext.where(SessionContext.current().withJavadoc(true), () -> keys(modules)))
                .containsExactly(
                        "worker:jk-java-compiler",
                        "worker:jk-test-runner",
                        "worker:jk-kotlin-compiler",
                        "kotlin:" + KotlinResolver.floored("2.1.0"),
                        "dokka:javadoc:" + DokkaResolver.DEFAULT_VERSION);
    }

    @Test
    void a_workspace_root_with_no_sources_forks_nothing_of_its_own() throws Exception {
        Path root = module("root", """
                group = "g"
                name = "root"
                version = "1.0"
                java = 25

                [workspace]
                modules = ["lib"]
                """, null);
        Path lib = Files.createDirectories(root.resolve("lib"));
        Files.writeString(lib.resolve("jk.toml"), """
                group = "g"
                name = "lib"
                version = "1.0"
                java = 25
                """);
        Files.createDirectories(lib.resolve("src/main/java/g"));
        Files.writeString(lib.resolve("src/main/java/g/Lib.java"), "package g; class Lib {}\n");

        assertThat(keys(Map.of(root, parse(root)))).as("the root alone").isEmpty();
        Map<Path, JkBuild> both = new LinkedHashMap<>();
        both.put(root, parse(root));
        both.put(lib, parse(lib));
        assertThat(keys(both))
                .as("its Java member")
                .containsExactly("worker:jk-java-compiler", "worker:jk-test-runner");
    }

    @Test
    void a_fork_that_cannot_be_staged_stops_none_of_the_others_and_is_named() throws Exception {
        AtomicBoolean staged = new AtomicBoolean();
        List<String> failed = BuildForks.stage(List.of(
                new BuildForks.Fork("worker:jk-java-compiler", () -> {
                    Thread.sleep(50);
                    staged.set(true);
                    return "ok";
                }),
                new BuildForks.Fork("dokka:javadoc:2.2.0", () -> {
                    throw new IOException("offline");
                })));

        assertThat(failed).containsExactly("dokka:javadoc:2.2.0: offline");
        assertThat(staged).as("the other fork was staged all the same").isTrue();
    }
}
