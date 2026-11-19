// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.RequestShell;
import cc.jumpkick.config.TestEnvValues;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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

    /** The cache key seed keys no reader of a module that inherits the caller's whole shell. */
    @Test
    void the_cache_key_seed_keys_nothing() throws Exception {
        JkBuild project = project("""
                [env]
                inherit = true
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

    /**
     * A manifest that names the cache key seed, in any place that hands a named variable on, keys a
     * plugin on it or sends it as a credential, is refused with the place and the reason: it would
     * otherwise reach a worker, a plugin's config or a repository server.
     */
    @Test
    void a_manifest_that_names_the_cache_key_seed_is_refused_where_it_names_it() {
        Map<String, String> places = new LinkedHashMap<>();
        places.put("[env]\noptional = [\"JK_CACHE_KEY_SEED\"]\n", "[env].optional[0]");
        places.put("[env]\nrequire = [\"jk_cache_key_seed\"]\n", "[env].require[0]");
        places.put("[env]\nvars = [\"JK_CACHE_KEY_SEED\"]\n", "[env].vars[0]");
        places.put(
                "[test]\nenv = [{ SEED = \"x-${JK_CACHE_KEY_SEED}\" }]\n", "test.env[0].SEED (${JK_CACHE_KEY_SEED})");
        places.put(
                "[repositories.corp]\nurl = \"https://repo.example.com\"\npassword = \"${env:JK_CACHE_KEY_SEED}\"\n",
                "repositories.corp.password (${env:JK_CACHE_KEY_SEED})");
        places.put("[test]\nenv = [\"JK_CACHE_KEY_SEED\"]\n", "[test].env[0]");
        places.put("[test]\nenv = [{ JK_CACHE_KEY_SEED = \"x\" }]\n", "[test].env[0].JK_CACHE_KEY_SEED");
        places.put("""
                [android]
                namespace   = "t.app"
                compile-sdk = 34
                min-sdk     = 24

                [android.signing.release]
                store-file = "env:JK_CACHE_KEY_SEED"
                key-alias  = "upload"
                """, "a plugin table's env:JK_CACHE_KEY_SEED");
        int i = 0;
        for (Map.Entry<String, String> place : places.entrySet()) {
            Path dir = tmp.resolve("refused-" + i++);
            assertThatThrownBy(() -> projectIn(dir, place.getKey()))
                    .as(place.getKey())
                    .isInstanceOf(JkBuildParseException.class)
                    .hasMessageContaining(place.getValue() + " names ")
                    .hasMessageContaining("the remote cache key seed")
                    .hasMessageContaining("so jk.toml may not name it");
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
        return projectIn(tmp, extra);
    }

    private static JkBuild projectIn(Path dir, String extra) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "com.example"
                name    = "m"
                version = "1.0.0"

                """ + extra);
        return JkBuildParser.parse(dir.resolve("jk.toml"));
    }
}
