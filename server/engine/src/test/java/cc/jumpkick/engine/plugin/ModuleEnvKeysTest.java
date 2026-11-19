// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.RequestShell;
import cc.jumpkick.config.TestEnvValues;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Each kind of environment read keys the readers it reaches, and only those. */
class ModuleEnvKeysTest {

    @TempDir
    Path tmp;

    @Test
    void a_plugin_env_value_keys_the_plugin_steps_and_not_the_compilers() throws Exception {
        JkBuild project = project("""
                [android]
                namespace   = "t.app"
                compile-sdk = 34
                min-sdk     = 24

                [android.signing.release]
                store-file = "env:KEYSTORE_FILE"
                key-alias  = "upload"
                """);
        List<String> debug = under("KEYSTORE_FILE", "/k/debug.jks", () -> ModuleEnvKeys.plugin(project, tmp));
        List<String> release = under("KEYSTORE_FILE", "/k/release.jks", () -> ModuleEnvKeys.plugin(project, tmp));

        assertThat(debug).isNotEqualTo(release);
        assertThat(String.join(" ", debug)).doesNotContain("/k/debug.jks");
        assertThat(under("KEYSTORE_FILE", "/k/debug.jks", () -> ModuleEnvKeys.worker(project, tmp)))
                .isEmpty();
    }

    @Test
    void a_test_env_value_keys_the_test_jvm_and_not_the_workers() throws Exception {
        JkBuild project = project("""
                [test]
                env = ["SUITE_MODE"]
                """);
        assertThat(under(
                        "SUITE_MODE",
                        "a",
                        () -> ModuleEnvKeys.testJvm(project, tmp, TestEnvValues.Mode.CacheKey::digested)))
                .isNotEqualTo(under(
                        "SUITE_MODE",
                        "b",
                        () -> ModuleEnvKeys.testJvm(project, tmp, TestEnvValues.Mode.CacheKey::digested)));
        assertThat(under("SUITE_MODE", "a", () -> ModuleEnvKeys.plugin(project, tmp)))
                .isEmpty();
    }

    /**
     * What the test JVM is handed keys its suite by digest: a forwarded name the caller lacks as
     * {@code unset}, a literal and a forwarded value by their digests, so the run-tests record that
     * carries these tokens holds no control character and no value (JK-ANWXB).
     */
    @Test
    void a_test_env_token_holds_no_value_and_no_control_character() throws Exception {
        JkBuild project = project("""
                [test]
                env = ["JK_ANWXB_UNSET", "SUITE_MODE", { CI = "false" }]
                """);
        List<String> tokens = under(
                "SUITE_MODE", "fast", () -> ModuleEnvKeys.testJvm(project, tmp, TestEnvValues.Mode.CacheKey::digested));

        assertThat(tokens)
                .hasSize(3)
                .contains("test-env:JK_ANWXB_UNSET=unset", "test-env:CI=" + Hashing.sha256Hex("false"))
                .anyMatch(t -> t.matches("test-env:SUITE_MODE=[0-9a-f]{64}"));
        assertThat(String.join("\n", tokens))
                .doesNotContain("\u0000")
                .doesNotContain("=fast")
                .doesNotContain("=false");
    }

    @Test
    void a_declared_value_keys_every_reader() throws Exception {
        JkBuild project = project("""
                [env]
                optional = ["GEN_MODE"]
                """);
        for (Callable<List<String>> reader : List.<Callable<List<String>>>of(
                () -> ModuleEnvKeys.worker(project, tmp),
                () -> ModuleEnvKeys.plugin(project, tmp),
                () -> ModuleEnvKeys.testJvm(project, tmp, TestEnvValues.Mode.CacheKey::digested),
                () -> ModuleEnvKeys.module(project, tmp))) {
            assertThat(under("GEN_MODE", "A", reader)).isNotEqualTo(under("GEN_MODE", "B", reader));
        }
    }

    /** The cache key seed keys no reader: declared for every worker, for the test JVM, or inherited with the shell. */
    @Test
    void the_cache_key_seed_keys_nothing() throws Exception {
        JkBuild project = project("""
                [env]
                inherit = true
                optional = ["JK_CACHE_KEY_SEED"]

                [test]
                env = ["JK_CACHE_KEY_SEED"]
                """);
        for (Callable<List<String>> reader : List.<Callable<List<String>>>of(
                () -> ModuleEnvKeys.worker(project, tmp),
                () -> ModuleEnvKeys.plugin(project, tmp),
                () -> ModuleEnvKeys.testJvm(project, tmp, TestEnvValues.Mode.CacheKey::digested),
                () -> ModuleEnvKeys.module(project, tmp))) {
            assertThat(under("JK_CACHE_KEY_SEED", "made-up-seed-a-0123456789", reader))
                    .isNotEmpty()
                    .isEqualTo(under("JK_CACHE_KEY_SEED", "made-up-seed-b-0123456789", reader))
                    .isEqualTo(RequestShell.where(Map.of("PATH", "/bin"), reader));
        }
    }

    @Test
    void a_module_that_reads_nothing_has_no_tokens() throws Exception {
        JkBuild project = project("");
        assertThat(ModuleEnvKeys.module(project, tmp)).isEmpty();
        assertThat(ModuleEnvKeys.testJvm(project, tmp, TestEnvValues.Mode.CacheKey::digested))
                .isEmpty();
    }

    private static List<String> under(String name, String value, Callable<List<String>> reader) throws Exception {
        return RequestShell.where(Map.of("PATH", "/bin", name, value), reader);
    }

    private JkBuild project(String extra) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                group   = "com.example"
                name    = "m"
                version = "1.0.0"

                """ + extra);
        return JkBuildParser.parse(tmp.resolve("jk.toml"));
    }
}
