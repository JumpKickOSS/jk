// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.ActionRecordText;
import cc.jumpkick.config.CallerEnv;
import cc.jumpkick.config.RequestShell;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.task.KeySeed;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Under {@code [env] scope} a suite keys on what its test JVM could be handed and nothing else, and
 * no value by a plain digest: without a remote cache's seed the result stays here, under one two
 * homes key alike. The names are the dojo-5 study's scoped spring-petclinic record's: the
 * writer's suite had read them before the module scoped, and kept them.
 */
class ScopedEnvKeysTest {

    /** The scope jk's hint gives spring-petclinic. */
    private static final SuiteEnvReads.Policy PETCLINIC = new SuiteEnvReads.Policy(
            false, List.of("SPRING_*", "TESTCONTAINERS_*", "DOCKER_*", "PETCLINIC_*"), Set.of());

    /** The repository token variable the study's writer and reader ran with. */
    private static final String TOKEN_VAR = "JK_REPO_LOCALHOST_8099_TOKEN";

    /** What the study's scoped record named of the caller's environment: listed, and these by name. */
    private static final SuiteEnvReads.Record STUDY = new SuiteEnvReads.Record(
            Set.of(
                    TOKEN_VAR,
                    "LOGNAME",
                    "SDKMAN_BROKER_API",
                    "SDKMAN_CANDIDATES_API",
                    "SDKMAN_CANDIDATES_DIR",
                    "SDKMAN_DIR",
                    "SDKMAN_PLATFORM",
                    "SHELL",
                    "TOKFILE",
                    "TPORT",
                    "USER",
                    "SPRING_PROFILES_ACTIVE"),
            true,
            true);

    private static CallerEnv caller(Map<String, String> vars) {
        return CallerEnv.of(RequestShell.of(vars, Set.of(), true, null), null);
    }

    /** A home's shell: its own user, token file and token (made up), the same Spring profile. */
    private static CallerEnv home(String user, String tokfile, String token) {
        Map<String, String> vars = new HashMap<>();
        vars.put("USER", user);
        vars.put("LOGNAME", user);
        vars.put("SHELL", "/bin/zsh");
        vars.put("TOKFILE", tokfile);
        vars.put("TPORT", "8099");
        vars.put(TOKEN_VAR, token);
        vars.put("SDKMAN_DIR", "/home/" + user + "/.sdkman");
        vars.put("SPRING_PROFILES_ACTIVE", "test");
        vars.put("PATH", "/usr/bin");
        return caller(vars);
    }

    /** A made-up team seed, as two homes of one cache hold it. */
    private static final KeySeed SEED = KeySeed.derive(
            "made-up-team-seed-0123456789".getBytes(StandardCharsets.UTF_8), "team\u0000https://cache.example.com");

    @Test
    void a_scoped_suite_keys_on_this_homes_secret_and_stays_here_without_a_seed() {
        CallerEnv a = home("alice", "/var/tmp/a/tok", "made-up-writer-token");

        List<String> inA = SuiteEnvReads.tokens(STUDY, PETCLINIC, a);

        assertThat(inA)
                .as("no plain digest a guessed value could be checked against, scoped or not")
                .noneMatch(t -> t.contains(Hashing.sha256Hex("SPRING_PROFILES_ACTIVE\u0000test")))
                .anyMatch(t -> t.startsWith("env-read:SPRING_PROFILES_ACTIVE="))
                .contains(SuiteEnvReads.HOST_TOKEN);
        assertThat(SuiteEnvReads.localOnly(STUDY, PETCLINIC, a)).isEqualTo("read the value of SPRING_PROFILES_ACTIVE");
        assertThat(STUDY.under(PETCLINIC).names()).containsExactly("SPRING_PROFILES_ACTIVE");
    }

    @Test
    void under_a_seed_a_scoped_suite_keys_alike_in_two_homes_whatever_names_an_earlier_run_kept() {
        CallerEnv a = home("alice", "/var/tmp/a/tok", "made-up-writer-token");
        CallerEnv b = home("bob", "/var/tmp/b/tok", "made-up-reader-token");

        List<String> inA = SuiteEnvReads.sharedTokens(STUDY, PETCLINIC, a, SEED, false);

        assertThat(SuiteEnvReads.sharedTokens(STUDY, PETCLINIC, b, SEED, false))
                .as("another user, token file and token key alike: the scope hides them")
                .isEqualTo(inA);
        assertThat(inA)
                .noneMatch(t -> t.contains("USER")
                        || t.contains("LOGNAME")
                        || t.contains("TOKFILE")
                        || t.contains("SDKMAN")
                        || t.contains(TOKEN_VAR))
                .contains("env-read:SPRING_PROFILES_ACTIVE=" + SEED.hmac("SPRING_PROFILES_ACTIVE\u0000test"))
                .doesNotContain(SuiteEnvReads.HOST_TOKEN);
    }

    @Test
    void without_a_scope_the_suite_keys_on_what_it_read_and_stays_on_this_host() {
        CallerEnv a = home("alice", "/var/tmp/a/tok", "made-up-writer-token");
        CallerEnv b = home("bob", "/var/tmp/b/tok", "made-up-writer-token");

        List<String> inA = SuiteEnvReads.tokens(STUDY, SuiteEnvReads.Policy.NONE, a);

        assertThat(SuiteEnvReads.tokens(STUDY, SuiteEnvReads.Policy.NONE, b)).isNotEqualTo(inA);
        assertThat(inA).anyMatch(t -> t.startsWith("env-read:USER=")).contains(SuiteEnvReads.HOST_TOKEN);
        assertThat(SuiteEnvReads.localOnly(STUDY, SuiteEnvReads.Policy.NONE, a))
                .as("jk's own token, withheld from every test JVM, keys nothing however the record named it")
                .isEqualTo("read the value of LOGNAME");
        assertThat(inA).noneMatch(t -> t.contains(TOKEN_VAR));
    }

    @Test
    void a_credential_the_scope_lets_through_keys_under_the_seed_and_never_plainly() {
        String password = "made-up-password";
        SuiteEnvReads.Record read =
                new SuiteEnvReads.Record(Set.of("SPRING_DATASOURCE_PASSWORD", "SPRING_PROFILES_ACTIVE"), false, false);
        CallerEnv shell = caller(Map.of("SPRING_DATASOURCE_PASSWORD", password, "SPRING_PROFILES_ACTIVE", "test"));
        String plain = Hashing.sha256Hex("SPRING_DATASOURCE_PASSWORD\u0000" + password);

        assertThat(SuiteEnvReads.tokens(read, PETCLINIC, shell))
                .noneMatch(t -> t.contains(plain) || t.contains(password))
                .contains(SuiteEnvReads.HOST_TOKEN);
        assertThat(SuiteEnvReads.sharedTokens(read, PETCLINIC, shell, SEED, false))
                .noneMatch(t -> t.contains(plain) || t.contains(password))
                .contains("env-read:SPRING_DATASOURCE_PASSWORD="
                        + SEED.hmac("SPRING_DATASOURCE_PASSWORD\u0000" + password));
        assertThat(SuiteEnvReads.sharedTokens(read, PETCLINIC, shell, SEED, true))
                .isNull();
        assertThat(SuiteEnvReads.tokens(read, PETCLINIC, caller(Map.of())))
                .as("both unset: they key as unset everywhere, no secret involved")
                .containsExactly("env-read:SPRING_DATASOURCE_PASSWORD=unset", "env-read:SPRING_PROFILES_ACTIVE=unset");
    }

    @Test
    void jks_own_token_variable_under_a_scope_that_lets_everything_through_is_never_keyed_plainly() {
        SuiteEnvReads.Policy everything = new SuiteEnvReads.Policy(false, List.of("*"), Set.of());
        String token = "made-up-cache-token";
        SuiteEnvReads.Record read = new SuiteEnvReads.Record(Set.of(TOKEN_VAR), false, true);
        CallerEnv shell = caller(Map.of(TOKEN_VAR, token, "MODE", "fast"));
        String whole = Hashing.sha256Hex("\u0000" + TOKEN_VAR + "=" + token + "\nMODE=fast\n");

        for (List<String> tokens : List.of(
                SuiteEnvReads.tokens(read, everything, shell),
                SuiteEnvReads.sharedTokens(read, everything, shell, SEED, false))) {
            assertThat(tokens)
                    .noneMatch(t -> t.contains(Hashing.sha256Hex(TOKEN_VAR + "\u0000" + token)))
                    .noneMatch(t -> t.equals("env-all=" + whole));
        }
    }

    /**
     * The study's scoped record as this jk writes it now: its local form, keyed on this home's
     * secret, stays here; its shared form under a seed goes to the shared cache with every value,
     * digest and name behind a listing withheld.
     */
    @Test
    void a_record_keyed_on_this_host_alone_never_leaves_it() {
        String key = "k".repeat(64);
        CallerEnv a = home("alice", "/var/tmp/a/tok", "made-up-writer-token");
        String scoped = record(key, SuiteEnvReads.tokens(STUDY, PETCLINIC, a));
        String open = record(key, SuiteEnvReads.tokens(STUDY, SuiteEnvReads.Policy.NONE, a));
        List<String> sharedTokens =
                Objects.requireNonNull(SuiteEnvReads.sharedTokens(STUDY, PETCLINIC, a, SEED, false));
        String shared = record(key, sharedTokens);

        for (String local : List.of(scoped, open)) {
            assertThat(local).contains("INPUT this-host x:env-keyed\n");
            assertThat(ActionRecordText.shareable(ActionRecordText.parse(local, key)))
                    .isFalse();
        }
        assertThat(ActionRecordText.shareable(ActionRecordText.parse(shared, key)))
                .isTrue();
        String leaving = ActionRecordText.renderShared(ActionRecordText.parse(shared, key));
        assertThat(leaving).contains("INPUT - x:env-read:SPRING_PROFILES_ACTIVE\n");
        for (String t : sharedTokens) {
            String digest = t.substring(t.indexOf('=') + 1);
            if (digest.length() == 64) assertThat(leaving).doesNotContain(digest);
        }
    }

    /** A green run-tests marker whose stamp extras are {@code tokens}, as the cache writes it. */
    private static String record(String key, List<String> tokens) {
        StringBuilder sb = new StringBuilder(String.join(
                "\n",
                "TASK run-tests@093fa723c276",
                "KEY " + key,
                "FORMAT 2",
                "KIND java:run-tests",
                "WRITES test-marker",
                "CREATED 2026-11-18T09:22:28Z",
                "PRODUCER jk/0.53.1-SNAPSHOT",
                "HOST linux-x86_64",
                "INPUT 6e39be448abf7901b9c95a164da25743450d826b3b29440810a6597333aa5f99 cp",
                "INPUT SPRING_*,TESTCONTAINERS_*,DOCKER_*,PETCLINIC_* x:env-policy:scope",
                ""));
        for (String t : tokens.stream().sorted().toList()) {
            int eq = t.indexOf('=');
            sb.append("INPUT ")
                    .append(t.substring(eq + 1))
                    .append(" x:")
                    .append(t, 0, eq)
                    .append('\n');
        }
        sb.append("OUTPUT 0 tests.failed\nOUTPUT 72 tests.succeeded\nOUTPUT 72 tests.total\n");
        return sb.append("END 3\n").toString();
    }
}
