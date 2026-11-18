// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where a request's shared cache is and whether it writes there: the user's own settings, never a
 * pull request's writes, and never a credential sent in the clear off this machine.
 */
class RemoteCacheSettingsTest {

    private static @Nullable RemoteCacheSettings resolve(Map<String, String> env, Path config) {
        return RemoteCacheSettings.resolve(env::get, config);
    }

    private static boolean writes(Map<String, String> env, Path config) {
        return requireNonNull(resolve(env, config), "settings").writes();
    }

    @Test
    void nothing_set_is_no_shared_cache(@TempDir Path tmp) {
        assertThat(resolve(Map.of(), tmp.resolve("config.toml"))).isNull();
        assertThat(resolve(Map.of(RemoteCacheSettings.URL_ENV, "off"), tmp.resolve("config.toml")))
                .isNull();
    }

    @Test
    void the_shell_names_it_over_the_users_config(@TempDir Path tmp) throws Exception {
        Path config = Files.writeString(tmp.resolve("config.toml"), """
                [cache]
                remote = "https://cache.example.com"
                remote-write = true
                """);
        RemoteCacheSettings fromFile = resolve(Map.of(), config);
        assertThat(fromFile).isNotNull();
        assertThat(requireNonNull(fromFile).url()).isEqualTo(URI.create("https://cache.example.com"));
        assertThat(requireNonNull(fromFile).writes())
                .as("remote-write = true on a developer's machine")
                .isTrue();

        RemoteCacheSettings fromShell = resolve(
                Map.of(
                        RemoteCacheSettings.URL_ENV,
                        "https://other.example.com/jk",
                        RemoteCacheSettings.WRITE_ENV,
                        "false"),
                config);
        assertThat(fromShell).isNotNull();
        assertThat(requireNonNull(fromShell).url()).isEqualTo(URI.create("https://other.example.com/jk"));
        assertThat(requireNonNull(fromShell).writes()).isFalse();
    }

    @Test
    void ci_writes_and_a_developer_reads_unless_told(@TempDir Path tmp) {
        Path none = tmp.resolve("config.toml");
        Map<String, String> url = Map.of(RemoteCacheSettings.URL_ENV, "https://cache.example.com");
        assertThat(writes(url, none)).as("a developer's machine reads").isFalse();
        Map<String, String> ci = new HashMap<>(url);
        ci.put("CI", "true");
        assertThat(writes(ci, none)).as("CI writes").isTrue();
        ci.put(RemoteCacheSettings.WRITE_ENV, "false");
        assertThat(writes(ci, none)).as("unless told not to").isFalse();
    }

    @Test
    void a_pull_requests_run_never_writes_whatever_it_says(@TempDir Path tmp) {
        Path none = tmp.resolve("config.toml");
        for (Map<String, String> pr : List.of(
                Map.of("GITHUB_EVENT_NAME", "pull_request"),
                Map.of("GITHUB_EVENT_NAME", "pull_request_target"),
                Map.of("CI_PIPELINE_SOURCE", "merge_request_event"),
                Map.of("CI_MERGE_REQUEST_SOURCE_BRANCH_NAME", "feature"),
                Map.of("BUILDKITE_PULL_REQUEST", "42"),
                Map.of("CIRCLE_PULL_REQUEST", "https://github.com/acme/shop/pull/42"))) {
            Map<String, String> env = new HashMap<>(pr);
            env.put("CI", "true");
            env.put(RemoteCacheSettings.URL_ENV, "https://cache.example.com");
            env.put(RemoteCacheSettings.WRITE_ENV, "true");
            assertThat(writes(env, none)).as(pr.toString()).isFalse();
        }
        Map<String, String> push = Map.of(
                "CI",
                "true",
                "GITHUB_EVENT_NAME",
                "push",
                "BUILDKITE_PULL_REQUEST",
                "false",
                RemoteCacheSettings.URL_ENV,
                "https://cache.example.com");
        assertThat(writes(push, none)).as("a push writes").isTrue();
    }

    @Test
    void a_credential_goes_over_plain_http_only_to_this_machine(@TempDir Path tmp) {
        Path none = tmp.resolve("config.toml");
        assertThat(resolve(Map.of(RemoteCacheSettings.URL_ENV, "http://127.0.0.1:8080"), none))
                .isNotNull();
        assertThat(resolve(Map.of(RemoteCacheSettings.URL_ENV, "http://localhost:8080"), none))
                .isNotNull();
        assertThat(resolve(Map.of(RemoteCacheSettings.URL_ENV, "http://cache.example.com"), none))
                .isNull();
        assertThat(resolve(Map.of(RemoteCacheSettings.URL_ENV, "ftp://cache.example.com"), none))
                .isNull();
        assertThat(resolve(Map.of(RemoteCacheSettings.URL_ENV, "https://user:secret@cache.example.com"), none))
                .as("a credential in the URL")
                .isNull();
    }
}
