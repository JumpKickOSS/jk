// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JK-1267: what a forked test JVM sees.
 *
 * <p>The default is the point. A test JVM inherits the engine's environment, so jk's own suite ran
 * against the developer's real {@code ~/.jk} — reading the real library catalog and able to write the
 * real local m2. That is what jk's Gradle build redirects per module, and it should not be something
 * each project has to remember.
 */
class TestEnvTest {

    @Test
    void jk_home_and_m2_are_sandboxed_under_the_module_by_default(@TempDir Path tmp) throws Exception {
        JkBuild project = project(tmp, "");
        var env = TestEnv.forModule(project, tmp, BuildLayout.of(tmp, project));

        assertThat(env.get("JK_HOME")).isEqualTo(tmp.resolve("target/test-jk-home").toAbsolutePath().toString());
        assertThat(env.get("JK_M2_LOCAL")).isEqualTo(tmp.resolve("target/test-m2").toAbsolutePath().toString());
        // Never the real home.
        assertThat(env.get("JK_HOME")).doesNotContain(System.getProperty("user.home") + "/.jk");
    }

    @Test
    void a_declared_value_overrides_the_sandbox_default(@TempDir Path tmp) throws Exception {
        JkBuild project = project(
                tmp,
                """
                [test]
                env = { JK_HOME = "${target}/mine", JK_HTTP_ENABLED = "false" }
                """);
        var env = TestEnv.forModule(project, tmp, BuildLayout.of(tmp, project));

        assertThat(env.get("JK_HOME")).isEqualTo(tmp.resolve("target/mine").toAbsolutePath().toString());
        assertThat(env.get("JK_HTTP_ENABLED")).isEqualTo("false");
        // The default the module didn't mention survives.
        assertThat(env.get("JK_M2_LOCAL")).endsWith("test-m2");
    }

    @Test
    void module_and_target_tokens_expand(@TempDir Path tmp) throws Exception {
        JkBuild project = project(
                tmp,
                """
                [test]
                env = { A = "${module}/fixtures", B = "${target}/scratch", C = "literal" }
                """);
        var env = TestEnv.forModule(project, tmp, BuildLayout.of(tmp, project));

        assertThat(env.get("A")).isEqualTo(tmp.toAbsolutePath() + "/fixtures");
        assertThat(env.get("B")).isEqualTo(tmp.resolve("target").toAbsolutePath() + "/scratch");
        assertThat(env.get("C")).isEqualTo("literal");
    }

    @Test
    void a_value_without_tokens_is_left_alone() {
        assertThat(TestEnv.expand("plain", Path.of("/m"), Path.of("/m/target"))).isEqualTo("plain");
        assertThat(TestEnv.expand(null, Path.of("/m"), Path.of("/m/target"))).isNull();
        // A lone $ is not a token and must not be mangled.
        assertThat(TestEnv.expand("costs $5", Path.of("/m"), Path.of("/m/target")))
                .isEqualTo("costs $5");
    }

    @Test
    void bare_booleans_and_numbers_are_accepted_as_strings(@TempDir Path tmp) throws Exception {
        // TOML users will reach for `false` before `"false"`; both should mean the same thing.
        JkBuild project = project(
                tmp,
                """
                [test]
                env = { FLAG = false, COUNT = 3 }
                """);
        assertThat(project.build().testEnv()).containsEntry("FLAG", "false").containsEntry("COUNT", "3");
    }

    @Test
    void declared_test_env_takes_part_in_the_test_stamp_key(@TempDir Path tmp) throws Exception {
        // A changed [test] env changes what the suite sees, so it must retest rather than be skipped.
        JkBuild before = project(tmp, "[test]\nenv = { MODE = \"a\" }\n");
        JkBuild after = project(tmp, "[test]\nenv = { MODE = \"b\" }\n");

        assertThat(BuildPipelines.testStampExtras(tmp, before))
                .isNotEqualTo(BuildPipelines.testStampExtras(tmp, after));
        assertThat(BuildPipelines.testStampExtras(tmp, before)).contains("test-env:MODE=a");
    }

    private static JkBuild project(Path dir, String extra) throws Exception {
        Files.writeString(
                dir.resolve("jk.toml"),
                """
                [project]
                group   = "com.example"
                name    = "m"
                version = "1.0.0"
                """
                        + extra);
        return JkBuildParser.parse(dir.resolve("jk.toml"));
    }
}
