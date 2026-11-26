// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.forge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.host.ChildEnv;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ForgeAuthTest {

    private static Function<String, @Nullable String> env(Map<String, String> m) {
        return m::get;
    }

    private static final CliTokenProbe NO_CLI = argv -> Optional.empty();

    @Test
    void jk_env_var_wins_over_everything(@TempDir Path dir) {
        var store = new TokenStore(dir);
        store.write("github.com", "stored-token");
        CliTokenProbe cli = argv -> Optional.of("gh-token");

        var auth = new ForgeAuth(
                store,
                env(Map.of(
                        "JK_GITHUB_TOKEN", "jk-token",
                        "GITHUB_TOKEN", "native-token")),
                cli);

        var resolved = auth.resolveSilently(ForgeKind.GITHUB, null).orElseThrow();
        assertThat(resolved.value()).isEqualTo("jk-token");
        assertThat(resolved.source()).isEqualTo(TokenSource.JK_ENV);
    }

    @Test
    void native_env_var_beats_cli_and_store(@TempDir Path dir) {
        var store = new TokenStore(dir);
        store.write("github.com", "stored-token");
        CliTokenProbe cli = argv -> Optional.of("gh-token");

        var auth = new ForgeAuth(store, env(Map.of("GITHUB_TOKEN", "native-token")), cli);

        var resolved = auth.resolveSilently(ForgeKind.GITHUB, null).orElseThrow();
        assertThat(resolved.value()).isEqualTo("native-token");
        assertThat(resolved.source()).isEqualTo(TokenSource.NATIVE_ENV);
    }

    @Test
    void native_cli_used_when_no_env(@TempDir Path dir) {
        var store = new TokenStore(dir);
        store.write("github.com", "stored-token");
        CliTokenProbe cli = argv -> {
            assertThat(argv).isEqualTo(List.of("gh", "auth", "token", "--hostname", "github.com"));
            return Optional.of("gh-token");
        };

        var auth = new ForgeAuth(store, env(Map.of()), cli);

        var resolved = auth.resolveSilently(ForgeKind.GITHUB, null).orElseThrow();
        assertThat(resolved.value()).isEqualTo("gh-token");
        assertThat(resolved.source()).isEqualTo(TokenSource.NATIVE_CLI);
    }

    @Test
    void native_cli_is_asked_for_the_host_being_called(@TempDir Path dir) {
        List<List<String>> asked = new ArrayList<>();
        CliTokenProbe cli = argv -> {
            asked.add(argv);
            return Optional.of("tok");
        };
        var auth = new ForgeAuth(new TokenStore(dir), env(Map.of()), cli);

        auth.resolveSilently(ForgeKind.GITHUB, "ghe.acme.dev");
        auth.resolveSilently(ForgeKind.GITLAB, "gitlab.acme.dev");

        assertThat(asked)
                .containsExactly(
                        List.of("gh", "auth", "token", "--hostname", "ghe.acme.dev"),
                        List.of("glab", "config", "get", "token", "--host", "gitlab.acme.dev"));
    }

    @Test
    void store_used_when_no_env_or_cli(@TempDir Path dir) {
        var store = new TokenStore(dir);
        store.write("github.com", "stored-token");

        var auth = new ForgeAuth(store, env(Map.of()), NO_CLI);

        var resolved = auth.resolveSilently(ForgeKind.GITHUB, null).orElseThrow();
        assertThat(resolved.value()).isEqualTo("stored-token");
        assertThat(resolved.source()).isEqualTo(TokenSource.STORE);
    }

    @Test
    void empty_when_nothing_configured(@TempDir Path dir) {
        var auth = new ForgeAuth(new TokenStore(dir), env(Map.of()), NO_CLI);
        assertThat(auth.resolveSilently(ForgeKind.GITHUB, null)).isEmpty();
    }

    @Test
    void provider_without_default_host_requires_explicit_host(@TempDir Path dir) {
        var auth = new ForgeAuth(new TokenStore(dir), env(Map.of()), NO_CLI);
        assertThatThrownBy(() -> auth.resolveSilently(ForgeKind.GITEA, null))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("--host");
    }

    @Test
    void gitea_resolves_per_host_credential(@TempDir Path dir) {
        var store = new TokenStore(dir);
        store.write("codeberg.org", "codeberg-token");
        var auth = new ForgeAuth(store, env(Map.of()), NO_CLI);

        var resolved = auth.resolveSilently(ForgeKind.GITEA, "codeberg.org").orElseThrow();
        assertThat(resolved.value()).isEqualTo("codeberg-token");
    }

    @Test
    void bitbucket_has_no_native_cli_so_cli_step_is_skipped(@TempDir Path dir) {
        // nativeCliToken(host) is empty for Bitbucket → the probe must never run.
        CliTokenProbe failing = argv -> {
            throw new AssertionError("CLI must not be probed");
        };
        var auth = new ForgeAuth(new TokenStore(dir), env(Map.of()), failing);
        assertThat(auth.resolveSilently(ForgeKind.BITBUCKET, null)).isEmpty();
    }

    /** Every forge's own token variable is one of jk's withheld credentials: no child jk starts inherits it. */
    @Test
    void every_forges_jk_token_variable_is_withheld_from_children() {
        for (ForgeKind kind : ForgeKind.values()) {
            assertThat(ChildEnv.withheld(kind.jkEnvVar())).as(kind.jkEnvVar()).isTrue();
            for (String nativeVar : kind.nativeEnvVars()) {
                assertThat(ChildEnv.withheld(nativeVar)).as(nativeVar).isFalse();
            }
        }
    }
}
