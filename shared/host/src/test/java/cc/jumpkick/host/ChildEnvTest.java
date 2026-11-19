// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A child environment keeps one entry per name on Windows, whatever the spelling: {@link
 * ProcessBuilder#environment()} there is a case-sensitive map whose {@code Path} a {@code PATH}
 * would stand beside, and the child reads whichever the OS meets first.
 */
class ChildEnvTest {

    /** A {@link ProcessBuilder#environment()} on Windows: case-sensitive, system path spelled Path. */
    private static Map<String, String> windowsProcessEnv() {
        Map<String, String> env = new HashMap<>();
        env.put("Path", "C:\\Windows\\System32");
        env.put("SystemRoot", "C:\\Windows");
        return env;
    }

    @Test
    void a_put_by_another_spelling_replaces_the_value_under_the_existing_one() {
        Map<String, String> env = windowsProcessEnv();

        ChildEnv.put(env, "PATH", "C:\\node;C:\\Windows\\System32", true);

        assertThat(env).containsOnlyKeys("Path", "SystemRoot");
        assertThat(env.get("Path")).isEqualTo("C:\\node;C:\\Windows\\System32");
    }

    @Test
    void a_lookup_finds_either_spelling_on_windows_only() {
        assertThat(ChildEnv.get(windowsProcessEnv(), "PATH", true)).isEqualTo("C:\\Windows\\System32");
        assertThat(ChildEnv.get(windowsProcessEnv(), "PATH", false)).isNull();
    }

    @Test
    void a_copy_folds_two_spellings_into_one_entry_the_later_value_winning() {
        Map<String, String> both = new LinkedHashMap<>();
        both.put("Path", "C:\\Windows\\System32");
        both.put("PATH", "C:\\node;C:\\Windows\\System32");

        Map<String, String> copy = ChildEnv.copyOf(both, true);

        assertThat(copy).hasSize(1);
        assertThat(copy.get("path")).isEqualTo("C:\\node;C:\\Windows\\System32");
    }

    @Test
    void a_remove_drops_every_spelling() {
        Map<String, String> env = windowsProcessEnv();
        env.put("PATH", "x");

        ChildEnv.remove(env, "path", true);

        assertThat(env).containsOnlyKeys("SystemRoot");
    }

    @Test
    void posix_names_stay_distinct() {
        Map<String, String> env = new HashMap<>(Map.of("Path", "a"));

        ChildEnv.put(env, "PATH", "b", false);

        assertThat(env).containsEntry("Path", "a").containsEntry("PATH", "b");
    }

    /**
     * The cache key seed, the one name kept from every child, under any spelling: what replace, put
     * and putAll hand over leaves it out, and a child started through start does not inherit it.
     */
    @Test
    void the_cache_key_seed_is_kept_from_every_child() throws Exception {
        assertThat(ChildEnv.withheld("JK_CACHE_KEY_SEED")).isTrue();
        assertThat(ChildEnv.withheld("jk_cache_key_seed")).isTrue();
        assertThat(ChildEnv.withheld("JK_CACHE_KEY_SEED_FILE")).isFalse();

        ProcessBuilder pb = new ProcessBuilder("unused");
        pb.environment().put("JK_CACHE_KEY_SEED", "made-up-seed-0123456789");
        ChildEnv.putAll(pb, Map.of("A", "1"));
        assertThat(pb.environment()).containsEntry("A", "1").doesNotContainKey("JK_CACHE_KEY_SEED");
        ChildEnv.put(pb, "JK_CACHE_KEY_SEED", "made-up-seed-0123456789");
        assertThat(pb.environment()).doesNotContainKey("JK_CACHE_KEY_SEED");
        ChildEnv.replace(pb, Map.of("B", "2", "Jk_Cache_Key_Seed", "made-up-seed-0123456789"));
        assertThat(pb.environment()).containsOnlyKeys("B");
    }

    @Test
    void a_child_started_through_start_or_captured_inherits_no_seed() throws Exception {
        for (boolean captured : new boolean[] {false, true}) {
            ProcessBuilder pb = (Os.isWindows()
                            ? new ProcessBuilder("cmd.exe", "/c", "set")
                            : new ProcessBuilder("env"))
                    .redirectErrorStream(true);
            // What a child of an engine started from a shell that exported the seed would inherit.
            pb.environment().put("JK_CACHE_KEY_SEED", "made-up-seed-0123456789");
            pb.environment().put("JK_SEED_NEIGHBOUR", "kept");
            String out;
            if (captured) {
                out = Processes.capture(pb, Duration.ofSeconds(30)).output();
            } else {
                out = Processes.capture(ChildEnv.start(pb), Duration.ofSeconds(30))
                        .output();
            }
            assertThat(out)
                    .as(captured ? "Processes.capture" : "ChildEnv.start")
                    .contains("JK_SEED_NEIGHBOUR=kept")
                    .doesNotContain("made-up-seed");
        }
    }

    /**
     * jk's own credentials are withheld by family, in any case; a repository's host binding, a
     * credential without a repository id, the names jk sets on a child itself and the credentials
     * other tools read too are not.
     */
    @Test
    void jks_own_credentials_are_withheld_by_family() {
        for (String name : new String[] {
            "JK_REPO_X_TOKEN",
            "JK_REPO_CACHE_EXAMPLE_COM_TOKEN",
            "JK_REPO_CENTRAL_USERNAME",
            "JK_REPO_CENTRAL_PASSWORD",
            "JK_REPO_CORP_TOKEN_FILE",
            "JK_REPO_CORP_PASSWORD_FILE",
            "JK_REPO_CORP_ACCESS_KEY",
            "JK_REPO_CORP_SECRET_KEY",
            "JK_REPO_INTERNAL_KEY",
            "JK_REPO_CORP_SECRET_KEY_FILE",
            "jk_repo_x_token",
            "JK_GITHUB_TOKEN",
            "JK_GITLAB_TOKEN",
            "JK_GITEA_TOKEN",
            "JK_BITBUCKET_TOKEN",
            "JK_GPG_PASSPHRASE"
        }) {
            assertThat(ChildEnv.withheld(name)).as(name).isTrue();
            assertThat(ChildEnv.refused(name)).as(name).isFalse();
        }
        for (String name : new String[] {
            "JK_REPO_X_HOST",
            "JK_REPO_TOKEN",
            "JK_HOME",
            "JK_OFFLINE",
            "JK_COMMAND_TOKEN",
            "JK_JOOQ_JDBC_PASSWORD",
            "JK_GIT_CRED_PASS",
            "GITHUB_TOKEN",
            "AWS_SECRET_ACCESS_KEY",
            "PUBLISH_PASSWORD",
            "JK_CACHE_REMOTE",
            "JK_GITHUB_OAUTH_CLIENT_ID",
            "JK_REPO_CORP_REGION",
            "JK_REPO_STAGE_DIR",
            "JK_REPO_PROBE",
            "JK_REPO_KEYS_HOST",
            "JK_CACHE_KEY_SEED_FILE"
        }) {
            assertThat(ChildEnv.withheld(name)).as(name).isFalse();
        }
        assertThat(ChildEnv.refused("JK_CACHE_KEY_SEED")).isTrue();
    }

    /**
     * A credential is kept from a child unless the environment handed over names it in {@link
     * ChildEnv#FORWARDED}, as the engine does for one a manifest forwards by name; the seed is
     * never kept, named or not.
     */
    @Test
    void a_credential_reaches_a_child_only_when_its_environment_names_it_forwarded() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("unused");
        pb.environment().put("JK_REPO_X_TOKEN", "made-up-token-0123456789");
        ChildEnv.putAll(pb, Map.of("A", "1"));
        assertThat(pb.environment()).containsEntry("A", "1").doesNotContainKey("JK_REPO_X_TOKEN");

        Map<String, String> asked = new LinkedHashMap<>();
        asked.put("B", "2");
        asked.put("JK_REPO_X_TOKEN", "made-up-token-0123456789");
        asked.put("JK_REPO_Y_TOKEN", "made-up-other-0123456789");
        asked.put("JK_CACHE_KEY_SEED", "made-up-seed-0123456789");
        asked.put(ChildEnv.FORWARDED, "jk_repo_x_token,JK_CACHE_KEY_SEED");
        ChildEnv.replace(pb, asked);
        assertThat(pb.environment())
                .containsEntry("JK_REPO_X_TOKEN", "made-up-token-0123456789")
                .doesNotContainKeys("JK_REPO_Y_TOKEN", "JK_CACHE_KEY_SEED")
                .containsKeys("B", ChildEnv.FORWARDED);
        assertThat(ChildEnv.forwardedValue(List.of("B", "jk_repo_y_token", "JK_REPO_X_TOKEN", "JK_CACHE_KEY_SEED")))
                .isEqualTo("JK_REPO_X_TOKEN,JK_REPO_Y_TOKEN");
        assertThat(ChildEnv.forwardedValue(List.of("B"))).isNull();
    }

    @Test
    void a_child_started_through_start_inherits_a_credential_only_when_forwarded() throws Exception {
        for (boolean forwarded : new boolean[] {false, true}) {
            ProcessBuilder pb = (Os.isWindows()
                            ? new ProcessBuilder("cmd.exe", "/c", "set")
                            : new ProcessBuilder("env"))
                    .redirectErrorStream(true);
            // What a child of an engine (or of a worker) started from a shell that exported it inherits.
            pb.environment().put("JK_REPO_X_TOKEN", "made-up-token-0123456789");
            if (forwarded) pb.environment().put(ChildEnv.FORWARDED, "JK_REPO_X_TOKEN");
            String out = Processes.capture(ChildEnv.start(pb), Duration.ofSeconds(30))
                    .output();
            if (forwarded) {
                assertThat(out).as("forwarded").contains("JK_REPO_X_TOKEN=made-up-token-0123456789");
            } else {
                assertThat(out).as("unasked").doesNotContain("made-up-token");
            }
        }
    }
}
