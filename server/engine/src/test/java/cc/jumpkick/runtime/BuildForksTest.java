// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.kotlin.KotlinResolver;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.runtime.base.DokkaResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What a build forks, as the planner reads the modules: nothing is staged that the build would not use. */
class BuildForksTest {

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

        assertThat(keys(modules))
                .containsExactly(
                        "worker:jk-java-compiler",
                        "worker:jk-test-runner",
                        "worker:jk-kotlin-compiler",
                        "kotlin:" + KotlinResolver.floored("2.1.0"),
                        // Both are libraries: their javadoc jar is Dokka's, staged once.
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
}
