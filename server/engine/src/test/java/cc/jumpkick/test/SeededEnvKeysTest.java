// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.CallerEnv;
import cc.jumpkick.config.RequestShell;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.task.KeySeed;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A suite key's shared form under a remote cache's seed: a value a suite read keys by
 * HMAC under the seed, alike on every host holding it, so a new seed moves it; a credential's value
 * too, unless the user keeps those on the host; a run with no caller has no shared form; the local
 * form never depends on the seed. The seeds and values here are made up.
 */
class SeededEnvKeysTest {

    private static final KeySeed SEED =
            KeySeed.derive(bytes("made-up-team-seed-one"), "team\u0000https://cache.example.com");
    private static final KeySeed OTHER =
            KeySeed.derive(bytes("made-up-team-seed-two"), "team\u0000https://cache.example.com");

    private static final SuiteEnvReads.Record READS_URL = new SuiteEnvReads.Record(Set.of("MY_APP_URL"), false, false);

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static CallerEnv caller(Map<String, String> vars) {
        return CallerEnv.of(RequestShell.of(vars, Set.of(), true, null), null);
    }

    @Test
    void two_hosts_with_one_seed_and_one_value_key_an_undeclared_read_alike() {
        CallerEnv alice = caller(Map.of("MY_APP_URL", "http://localhost:8080", "USER", "alice"));
        CallerEnv bob = caller(Map.of("MY_APP_URL", "http://localhost:8080", "USER", "bob"));

        List<String> a = SuiteEnvReads.sharedTokens(READS_URL, SuiteEnvReads.Policy.NONE, alice, SEED, false);
        List<String> b = SuiteEnvReads.sharedTokens(READS_URL, SuiteEnvReads.Policy.NONE, bob, SEED, false);

        assertThat(a).isEqualTo(b);
        assertThat(a)
                .containsExactly("env-read:MY_APP_URL=" + SEED.hmac("MY_APP_URL\u0000http://localhost:8080"))
                .doesNotContain(SuiteEnvReads.HOST_TOKEN)
                .noneMatch(t -> t.contains(Hashing.sha256Hex("MY_APP_URL\u0000http://localhost:8080")));
    }

    @Test
    void another_value_or_another_seed_keys_otherwise() {
        CallerEnv one = caller(Map.of("MY_APP_URL", "http://localhost:8080"));
        CallerEnv two = caller(Map.of("MY_APP_URL", "http://localhost:9090"));

        List<String> base = SuiteEnvReads.sharedTokens(READS_URL, SuiteEnvReads.Policy.NONE, one, SEED, false);

        assertThat(SuiteEnvReads.sharedTokens(READS_URL, SuiteEnvReads.Policy.NONE, two, SEED, false))
                .isNotEqualTo(base);
        assertThat(SuiteEnvReads.sharedTokens(READS_URL, SuiteEnvReads.Policy.NONE, one, OTHER, false))
                .isNotEqualTo(base);
    }

    @Test
    void a_suite_whose_reads_were_all_unset_keys_alike_everywhere_seed_or_not() {
        CallerEnv shell = caller(Map.of("USER", "alice"));

        List<String> local = SuiteEnvReads.tokens(READS_URL, SuiteEnvReads.Policy.NONE, shell);

        assertThat(local).containsExactly("env-read:MY_APP_URL=unset");
        assertThat(SuiteEnvReads.sharedTokens(READS_URL, SuiteEnvReads.Policy.NONE, shell, SEED, false))
                .as("no value went under the seed: the same key in both forms, which a new seed leaves alone")
                .isEqualTo(local)
                .isEqualTo(SuiteEnvReads.sharedTokens(READS_URL, SuiteEnvReads.Policy.NONE, shell, OTHER, false));
    }

    @Test
    void one_team_seed_set_for_two_caches_gives_two_unrelated_keys() {
        KeySeed here = KeySeed.derive(bytes("made-up-team-seed-one"), "team\u0000https://cache.example.com");
        KeySeed there = KeySeed.derive(bytes("made-up-team-seed-one"), "team\u0000https://other.example.com");

        assertThat(here).isEqualTo(SEED);
        assertThat(there).isNotEqualTo(here);
        assertThat(there.hmac("x")).isNotEqualTo(here.hmac("x"));
        assertThat(here.toString()).doesNotContain("made-up-team-seed-one").isEqualTo("KeySeed[withheld]");
    }

    @Test
    void the_local_form_is_the_same_with_or_without_a_seed_and_stays_on_this_host() {
        CallerEnv shell = caller(Map.of("MY_APP_URL", "http://localhost:8080"));

        List<String> local = SuiteEnvReads.tokens(READS_URL, SuiteEnvReads.Policy.NONE, shell);

        assertThat(local)
                .contains(SuiteEnvReads.HOST_TOKEN)
                .doesNotContain("env-read:MY_APP_URL=" + SEED.hmac("MY_APP_URL\u0000http://localhost:8080"));
        assertThat(SuiteEnvReads.tokens(READS_URL, SuiteEnvReads.Policy.NONE, shell))
                .as("taken again, as a build with a seed takes it")
                .isEqualTo(local);
    }

    @Test
    void a_credentials_value_keys_under_the_seed_like_any_other_unless_the_user_keeps_those_here() {
        SuiteEnvReads.Record both = new SuiteEnvReads.Record(Set.of("MY_APP_URL", "MY_API_TOKEN"), false, false);
        String token = "made-up-token";
        CallerEnv ci = caller(Map.of("MY_APP_URL", "http://localhost:8080", "MY_API_TOKEN", token));
        CallerEnv laptop = caller(Map.of("MY_APP_URL", "http://localhost:8080", "MY_API_TOKEN", token, "USER", "b"));

        List<String> shared = SuiteEnvReads.sharedTokens(both, SuiteEnvReads.Policy.NONE, ci, SEED, false);

        assertThat(shared)
                .isEqualTo(SuiteEnvReads.sharedTokens(both, SuiteEnvReads.Policy.NONE, laptop, SEED, false))
                .contains("env-read:MY_API_TOKEN=" + SEED.hmac("MY_API_TOKEN\u0000" + token))
                .doesNotContain(SuiteEnvReads.HOST_TOKEN)
                .noneMatch(t -> t.contains(token) || t.contains(Hashing.sha256Hex("MY_API_TOKEN\u0000" + token)));
        assertThat(SuiteEnvReads.sharedTokens(both, SuiteEnvReads.Policy.NONE, ci, SEED, true))
                .as("[cache] credential-env = \"local\": the result stays on this host")
                .isNull();
        assertThat(SuiteEnvReads.sharedTokens(READS_URL, SuiteEnvReads.Policy.NONE, ci, SEED, true))
                .as("a result that read no credential shares under the opt-out too")
                .isNotNull();
        assertThat(SuiteEnvReads.sharedTokens(
                        new SuiteEnvReads.Record(Set.of(), false, true), SuiteEnvReads.Policy.NONE, ci, SEED, true))
                .as("a program started with a credential set: kept here under the opt-out")
                .isNull();
    }

    @Test
    void a_run_with_no_caller_has_no_shared_form() {
        assertThat(SuiteEnvReads.sharedTokens(
                        READS_URL, SuiteEnvReads.Policy.NONE, CallerEnv.of(null, null), SEED, false))
                .isNull();
    }

    @Test
    void the_seed_variable_is_never_handed_to_a_test_and_keys_nothing() {
        String seed = "made-up-team-seed-0123456789";
        Map<String, String> vars = Map.of("JK_CACHE_KEY_SEED", seed, "MY_APP_URL", "u");
        SuiteEnvReads.Record broad = new SuiteEnvReads.Record(Set.of("JK_CACHE_KEY_SEED"), true, true);

        assertThat(SuiteEnvReads.Policy.NONE.handed("JK_CACHE_KEY_SEED")).isFalse();
        assertThat(SuiteEnvReads.tokens(broad, SuiteEnvReads.Policy.NONE, caller(vars)))
                .as("taken with the seed variable set, or another seed, or none: one local key")
                .isEqualTo(SuiteEnvReads.tokens(
                        broad,
                        SuiteEnvReads.Policy.NONE,
                        caller(Map.of("JK_CACHE_KEY_SEED", "made-up-other-seed-0123456789", "MY_APP_URL", "u"))))
                .isEqualTo(SuiteEnvReads.tokens(broad, SuiteEnvReads.Policy.NONE, caller(Map.of("MY_APP_URL", "u"))))
                .noneMatch(t -> t.contains("JK_CACHE_KEY_SEED"));
    }

    /**
     * A test JVM that runs without the probe gets the caller's whole environment, less the seed,
     * even where the module declares it.
     */
    @Test
    void the_whole_environment_a_test_jvm_gets_without_the_probe_leaves_the_seed_out() throws Exception {
        CallerEnv caller = caller(Map.of("JK_CACHE_KEY_SEED", "made-up-team-seed-0123456789", "MY_APP_URL", "u"));
        for (SuiteEnvReads.Policy policy : List.of(
                SuiteEnvReads.Policy.NONE, new SuiteEnvReads.Policy(false, List.of(), Set.of("JK_CACHE_KEY_SEED")))) {
            Map<String, String> env = WorkerEnv.withEngineEnvironment(
                    Map.of("PATH", "/usr/bin"),
                    SuiteEnvDelivery.unprobedEnvironment(WorkerEnv.strict(), true, policy, caller)::environment);
            assertThat(env).containsEntry("MY_APP_URL", "u").doesNotContainKey("JK_CACHE_KEY_SEED");
        }
    }

    @Test
    void a_listing_and_a_whole_environment_key_under_the_seed_too() {
        SuiteEnvReads.Record broad = new SuiteEnvReads.Record(Set.of(), true, true);
        Map<String, String> vars = Map.of("MY_APP_URL", "http://localhost:8080", "LANGUAGE", "en");

        List<String> a = SuiteEnvReads.sharedTokens(broad, SuiteEnvReads.Policy.NONE, caller(vars), SEED, false);

        assertThat(a).isNotNull().anyMatch(t -> t.startsWith("env-names=")).anyMatch(t -> t.startsWith("env-all="));
        assertThat(SuiteEnvReads.sharedTokens(broad, SuiteEnvReads.Policy.NONE, caller(vars), SEED, false))
                .isEqualTo(a);
        assertThat(SuiteEnvReads.sharedTokens(
                        broad,
                        SuiteEnvReads.Policy.NONE,
                        caller(Map.of("MY_APP_URL", "x", "LANGUAGE", "en")),
                        SEED,
                        false))
                .as("another shell, another key: two hosts' environments rarely match")
                .isNotEqualTo(a);
    }
}
