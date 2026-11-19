// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.RequestShell;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.task.KeySeed;
import cc.jumpkick.test.SuiteEnvReads;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A declared variable the caller's shell answers keys a suite by HMAC, never by plain digest: under
 * this home's secret in the local form, which then stays on the host, and under the remote cache's
 * seed in the shared form. A literal from jk.toml keys as before. The values and the
 * seed are made up.
 */
class SharedStampKeysTest {

    private static final KeySeed SEED = KeySeed.derive(
            "made-up-team-seed-0123456789".getBytes(StandardCharsets.UTF_8), "team\u0000https://cache.example.com");

    @TempDir
    Path tmp;

    @Test
    void a_forwarded_value_keys_under_this_homes_secret_here_and_under_the_seed_in_the_shared_form() throws Exception {
        JkBuild project = project("[env]\nrequire = [\"MY_APP_URL\"]\n");

        List<String> local = declared(project, "http://localhost:8080", null);
        List<String> shared = declared(project, "http://localhost:8080", SEED);

        assertThat(local)
                .contains(SuiteEnvReads.HOST_TOKEN)
                .anyMatch(t -> t.startsWith("test-env:MY_APP_URL="))
                .noneMatch(t -> t.contains(Hashing.sha256Hex(Hashing.sha256Hex("http://localhost:8080"))));
        assertThat(shared)
                .doesNotContain(SuiteEnvReads.HOST_TOKEN)
                .isEqualTo(declared(project, "http://localhost:8080", SEED))
                .isNotEqualTo(declared(project, "http://localhost:9090", SEED));
        assertThat(local)
                .as("the local form never depends on the seed")
                .isEqualTo(declared(project, "http://localhost:8080", null));
        assertThat(shared.getFirst()).isNotEqualTo(local.getFirst());
    }

    @Test
    void an_unset_forward_and_a_literal_key_no_secret_and_stay_shareable() throws Exception {
        JkBuild project = project("[env]\noptional = [\"MY_APP_URL\"]\n\n[test]\nenv = [{ MODE = \"fast\" }]\n");

        List<String> local =
                RequestShell.where(Map.of("PATH", "/bin"), () -> SharedStampKeys.declaredTokens(project, tmp, null));

        assertThat(local).doesNotContain(SuiteEnvReads.HOST_TOKEN).contains("test-env:MY_APP_URL=unset");
        assertThat(RequestShell.where(Map.of("PATH", "/bin"), () -> SharedStampKeys.declaredTokens(project, tmp, SEED)))
                .as("no secret involved: both forms are one")
                .isEqualTo(local);
    }

    @Test
    void the_shared_extras_put_each_form_where_the_first_of_its_tokens_stood() {
        List<String> extras = List.of(
                "jk:1",
                "test-env:A=x",
                SuiteEnvReads.HOST_TOKEN,
                "tool:git=2",
                "env-read:B=y",
                SuiteEnvReads.HOST_TOKEN);

        assertThat(SharedStampKeys.shared(extras, List.of("env-read:B=s"), List.of("test-env:A=s")))
                .containsExactly("jk:1", "test-env:A=s", "env-read:B=s", "tool:git=2");
    }

    private List<String> declared(JkBuild project, String value, @Nullable KeySeed seed) throws Exception {
        return RequestShell.where(
                Map.of("PATH", "/bin", "MY_APP_URL", value), () -> SharedStampKeys.declaredTokens(project, tmp, seed));
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
