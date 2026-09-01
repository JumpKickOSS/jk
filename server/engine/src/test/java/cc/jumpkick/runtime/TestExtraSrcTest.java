// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.task.ActionKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code [test] extra-src}: extra test-tier sources compiled into test classes. Sibling-consumed
 * helpers use {@code [test] fixtures} instead; this is the single-file / extra-root lane
 * ({@code clients/cli} compiling one IntelliJ parser type).
 *
 * <p>The roots compile into the module's own test classes output. {@code compile-test}'s key has
 * to cover these files because {@code TestSupport.compileWithCache} is driven from the primary
 * source directory and only its {@code extraSources} argument reaches {@code CompileRequest.sources}.
 */
class TestExtraSrcTest {

    private static final String MANIFEST = """
            name = "lib"
            group = "com.example"
            version = "0.1.0"
            java = 25

            [test]
            extra-src = ["src/extra-test/java"]
            """;

    private static Path fixtureModule(Path dir, String fixtureBody) throws Exception {
        Files.createDirectories(dir.resolve("src/test/java/com/example"));
        Files.writeString(dir.resolve("src/test/java/com/example/LibTest.java"), "class LibTest {}\n");
        Files.createDirectories(dir.resolve("src/extra-test/java/com/example"));
        Files.writeString(dir.resolve("src/extra-test/java/com/example/Fix.java"), fixtureBody);
        Files.writeString(dir.resolve("jk.toml"), MANIFEST);
        return dir;
    }

    @Test
    void a_declared_root_is_collected_and_an_undeclared_one_is_not(@TempDir Path tmp) throws Exception {
        Path dir = fixtureModule(tmp.resolve("lib"), "class Fix {}\n");

        JkBuild declared = JkBuildParser.parse(dir.resolve("jk.toml"));
        assertThat(TestSupport.testExtraSources(declared, dir, ".java"))
                .as("the root named by [test] extra-src")
                .containsExactly(dir.resolve("src/extra-test/java/com/example/Fix.java"));

        // Same tree on disk, manifest silent: the directory is not a suite, so nothing else finds
        // it either. An extra-src root only exists because a manifest says so.
        Files.writeString(
                dir.resolve("jk.toml"), MANIFEST.replace("[test]\nextra-src = [\"src/extra-test/java\"]\n", ""));
        JkBuild silent = JkBuildParser.parse(dir.resolve("jk.toml"));
        assertThat(TestSupport.testExtraSources(silent, dir, ".java"))
                .as("no [test] extra-src, so no roots")
                .isEmpty();
    }

    @Test
    void a_root_that_is_not_on_disk_is_skipped_rather_than_failing(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("lib");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("jk.toml"), MANIFEST);

        // A declared-but-absent root is a manifest a module has not grown into yet, not a build
        // error: the same manifest is shared by a template and by the module it scaffolds.
        assertThat(TestSupport.testExtraSources(JkBuildParser.parse(dir.resolve("jk.toml")), dir, ".java"))
                .isEmpty();
    }

    @Test
    void editing_a_fixture_moves_the_compile_test_key(@TempDir Path tmp) throws Exception {
        Path dir = fixtureModule(tmp.resolve("lib"), "class Fix { static int v() { return 1; } }\n");
        JkBuild project = JkBuildParser.parse(dir.resolve("jk.toml"));
        Path suite = dir.resolve("src/test/java/com/example/LibTest.java");
        Path out = dir.resolve("target/test-classes");

        String before = compileTestKey(project, dir, suite, out);

        // The consumer's own key moves because WorkspaceClasspath hands it this module's test
        // classes DIRECTORY and ActionKey tree-hashes those. But that only helps if the classes
        // were rebuilt, which is this key's job.
        Files.writeString(
                dir.resolve("src/extra-test/java/com/example/Fix.java"),
                "class Fix { static int v() { return 2; } }\n");
        assertThat(compileTestKey(project, dir, suite, out))
                .as("compile-test key after editing only a fixture")
                .isNotEqualTo(before);
    }

    /** compile-test's request the way PlannerTest builds it: suite sources plus the extra roots. */
    private static String compileTestKey(JkBuild project, Path dir, Path suite, Path out) throws Exception {
        List<Path> sources =
                CompileSupport.concatDistinct(List.of(suite), TestSupport.testExtraSources(project, dir, ".java"));
        CompileRequest req = CompileRequest.builder()
                .sources(sources)
                .classpath(List.of())
                .outputDir(out)
                .release(25)
                .build();
        return ActionKey.forJavac("compile-test", req, "test");
    }
}
