// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SecretRedactor;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.task.ActionCache;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * what a forked test JVM sees.
 *
 * <p>The default is the point. A test JVM inherits the engine's environment, so jk's own suite ran
 * against the developer's real {@code JK_HOME} / platform product layout — reading the real library catalog and able to write the
 * real local m2. That is what jk's Gradle build redirects per module, and it should not be something
 * each project has to remember.
 */
class TestEnvTest {

    /** A name no environment sets — asserted, not assumed, so a stray export cannot green this file. */
    private static final String UNSET = "JK_2384_UNSET_ON_PURPOSE";

    @BeforeAll
    static void the_unset_variable_really_is_unset() {
        // Asserted, not assumed: a stray export would otherwise turn the two strictness tests green
        // without exercising the unset path at all.
        assertThat(System.getenv(UNSET)).isNull();
    }

    @Test
    void jk_home_and_m2_are_sandboxed_under_the_module_by_default(@TempDir Path tmp) throws Exception {
        JkBuild project = project(tmp, "");
        var env = TestEnv.forModule(project, tmp, BuildLayout.of(tmp, project));

        Path sandbox = tmp.resolve("target/test-jk-home").toAbsolutePath();
        assertThat(env.get("JK_HOME")).isEqualTo(sandbox.toString());
        assertThat(env.get("JK_JDKS_DIR")).isEqualTo(sandbox.resolve("jdks").toString());
        assertThat(env.get("JK_M2_LOCAL"))
                .isEqualTo(tmp.resolve("target/test-m2").toAbsolutePath().toString());
        // Never the real product data root.
        assertThat(env.get("JK_HOME")).doesNotContain(System.getProperty("user.home") + "/.local/share/jk");
        assertThat(env.get("JK_HTTP_ENABLED")).isEqualTo("false");
        assertThat(env.get("JK_HTTP_PORT")).isEqualTo("0");
    }

    @Test
    void a_declared_value_overrides_the_sandbox_default(@TempDir Path tmp) throws Exception {
        JkBuild project = project(tmp, """
                [test]
                env = { JK_HOME = "${target}/mine", JK_HTTP_ENABLED = "false" }
                """);
        var env = TestEnv.forModule(project, tmp, BuildLayout.of(tmp, project));

        // ${target} expands to a host path, so on Windows the declared value reads
        // C:\…\target/mine — mixed separators. The contract is the directory named, not the string.
        assertThat(Path.of(env.get("JK_HOME")))
                .isEqualTo(tmp.resolve("target/mine").toAbsolutePath());
        assertThat(env.get("JK_HTTP_ENABLED")).isEqualTo("false");
        // The default the module didn't mention survives.
        assertThat(env.get("JK_M2_LOCAL")).endsWith("test-m2");
    }

    @Test
    void module_and_target_tokens_expand(@TempDir Path tmp) throws Exception {
        JkBuild project = project(tmp, """
                [test]
                env = { A = "${module}/fixtures", B = "${target}/scratch", C = "literal", D = "costs $5" }
                """);
        var env = TestEnv.forModule(project, tmp, BuildLayout.of(tmp, project));

        assertThat(env.get("A")).isEqualTo(tmp.toAbsolutePath() + "/fixtures");
        assertThat(env.get("B")).isEqualTo(tmp.resolve("target").toAbsolutePath() + "/scratch");
        assertThat(env.get("C")).isEqualTo("literal");
        // A lone $ is not a reference and must not be mangled.
        assertThat(env.get("D")).isEqualTo("costs $5");
    }

    @Test
    void bare_booleans_and_numbers_are_accepted_as_strings(@TempDir Path tmp) throws Exception {
        // TOML users will reach for `false` before `"false"`; both should mean the same thing.
        JkBuild project = project(tmp, """
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

        assertThat(BuildPlanner.testStampExtras(tmp, before)).isNotEqualTo(BuildPlanner.testStampExtras(tmp, after));
        assertThat(BuildPlanner.testStampExtras(tmp, before)).contains("test-env:MODE=a");
    }

    @Test
    void real_env_references_are_hashed_too_never_literal(@TempDir Path tmp) throws Exception {
        // a non-secret env reference (${HOME}, a CI id) still keys the stamp by VALUE
        // a changed environment retests — but the literal (an absolute path) must not land in a
        // potentially shared key.
        JkBuild project = project(tmp, "[test]\nenv = { HOME_DIR = \"${HOME}\" }\n");
        String home = System.getenv("HOME");
        org.junit.jupiter.api.Assumptions.assumeTrue(home != null && !home.isBlank());

        List<String> extras = BuildPlanner.testStampExtras(tmp, project);

        assertThat(extras).noneMatch(s -> s.contains(home));
        assertThat(extras).anyMatch(s -> s.startsWith("test-env:HOME_DIR=" + SecretRedactor.KEY_PREFIX));
    }

    @Test
    void env_sourced_secret_is_hashed_into_the_test_stamp_not_written_verbatim(@TempDir Path tmp) throws Exception {
        // a.env value that participates in the stamp key must be hashed, never literal.
        String secret = "jk-1274-unique-secret-token-xyz";
        Files.writeString(tmp.resolve(".env"), "TOKEN=" + secret + "\n");
        JkBuild project = project(tmp, "[test]\nenv = { API_KEY = \"${TOKEN}\" }\n");

        List<String> extras = BuildPlanner.testStampExtras(tmp, project);
        assertThat(extras).noneMatch(s -> s.contains(secret));
        assertThat(extras).anyMatch(s -> s.startsWith("test-env:API_KEY=" + SecretRedactor.KEY_PREFIX));

        // A different secret must retest (different digest).
        Files.writeString(tmp.resolve(".env"), "TOKEN=other-secret-value\n");
        List<String> after = BuildPlanner.testStampExtras(tmp, project);
        assertThat(after).isNotEqualTo(extras);
        assertThat(after).noneMatch(s -> s.contains("other-secret-value"));
    }

    /**
     * The launch path and the cache-key path used to disagree here: launch threw, the key path
     * caught the same exception and keyed on the raw {@code ${VAR}} text. One manifest, two answers.
     */
    @Test
    void an_unset_reference_fails_the_key_path_exactly_as_it_fails_at_launch(@TempDir Path tmp) throws Exception {
        JkBuild project = project(tmp, "[test]\nenv = { API_KEY = \"${" + UNSET + "}\" }\n");
        BuildLayout layout = BuildLayout.of(tmp, project);

        assertThatThrownBy(() -> TestEnv.forModule(project, tmp, layout))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[test].env.API_KEY")
                .hasMessageContaining(UNSET);
        assertThatThrownBy(() -> BuildPlanner.testStampExtras(tmp, project))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[test].env.API_KEY")
                .hasMessageContaining(UNSET);
    }

    /**
     * The reproducibility fence: a build's outcome must not be a function of cache state. The
     * manifest is identical in both halves; only the action cache differs.
     */
    @Test
    void a_bad_reference_fails_the_plan_cold_and_with_a_green_marker_present(@TempDir Path tmp) throws Exception {
        JkBuild project = project(tmp, "[test]\nenv = { API_KEY = \"${" + UNSET + "}\" }\n");
        Path lock = tmp.resolve("jk-lock.toml");
        Path classes = Files.createDirectories(tmp.resolve("target/classes"));
        ActionCache cache = new ActionCache(new Cas(tmp.resolve("cas")), tmp.resolve("actions"));

        // Cold: nothing cached for this module.
        assertThatThrownBy(() -> BuildPlanner.runTestsStampKey(tmp, project, false, classes, lock, List.of()))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[test].env.API_KEY");

        // Give the variable a value, take the key the build would use and store the green marker
        // under it — "this module's tests are cached" is now true on disk.
        Files.writeString(tmp.resolve(".env"), UNSET + "=a-value-long-enough-to-count\n");
        String key = BuildPlanner.runTestsStampKey(tmp, project, false, classes, lock, List.of());
        assertThat(key).isNotNull();
        cache.storeWithOutputs("run-tests", key, Map.of(), Map.of("tests.total", "1"));
        assertThat(cache.lookup(key)).isPresent();

        // Take the value away again. Same manifest, same marker: the plan must fail as it did cold,
        // not key on the raw ${VAR} text and let the forecast report a skip.
        Files.delete(tmp.resolve(".env"));
        assertThatThrownBy(() -> BuildPlanner.runTestsStampKey(tmp, project, false, classes, lock, List.of()))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[test].env.API_KEY");
    }

    private static JkBuild project(Path dir, String extra) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "com.example"
                name    = "m"
                version = "1.0.0"
                """ + extra);
        return JkBuildParser.parse(dir.resolve("jk.toml"));
    }
}
