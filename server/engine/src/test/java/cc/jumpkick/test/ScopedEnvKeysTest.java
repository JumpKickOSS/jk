// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.ActionRecordText;
import cc.jumpkick.config.CallerEnv;
import cc.jumpkick.config.RequestShell;
import cc.jumpkick.host.Hashing;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Under {@code [env] scope} a suite keys on what its test JVM could be handed and nothing else,
 * and a result only this home keys alike stays here (JK-2M8VG). The names are the dojo-5 study's scoped spring-petclinic record's: the
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

    @Test
    void a_scoped_suite_keys_alike_in_two_homes_whatever_names_an_earlier_run_kept() {
        CallerEnv a = home("alice", "/var/tmp/a/tok", "made-up-writer-token");
        CallerEnv b = home("bob", "/var/tmp/b/tok", "made-up-reader-token");

        List<String> inA = SuiteEnvReads.tokens(STUDY, PETCLINIC, a);
        List<String> inB = SuiteEnvReads.tokens(STUDY, PETCLINIC, b);

        assertThat(inB).as("another user, token file and token key alike").isEqualTo(inA);
        assertThat(inA)
                .as("the names the scope hides key nothing")
                .noneMatch(t -> t.contains("USER")
                        || t.contains("LOGNAME")
                        || t.contains("TOKFILE")
                        || t.contains("SDKMAN")
                        || t.contains(TOKEN_VAR))
                .anyMatch(t -> t.startsWith(
                        "env-read:SPRING_PROFILES_ACTIVE=" + Hashing.sha256Hex("SPRING_PROFILES_ACTIVE\u0000test")))
                .doesNotContain(SuiteEnvReads.HOST_TOKEN);
        assertThat(SuiteEnvReads.localOnly(STUDY, PETCLINIC, a))
                .as("the result may be shared")
                .isNull();
        assertThat(STUDY.under(PETCLINIC).names()).containsExactly("SPRING_PROFILES_ACTIVE");
    }

    @Test
    void without_a_scope_the_suite_keys_on_what_it_read_and_stays_on_this_host() {
        CallerEnv a = home("alice", "/var/tmp/a/tok", "made-up-writer-token");
        CallerEnv b = home("bob", "/var/tmp/b/tok", "made-up-writer-token");

        List<String> inA = SuiteEnvReads.tokens(STUDY, SuiteEnvReads.Policy.NONE, a);

        assertThat(SuiteEnvReads.tokens(STUDY, SuiteEnvReads.Policy.NONE, b)).isNotEqualTo(inA);
        assertThat(inA).anyMatch(t -> t.startsWith("env-read:USER=")).contains(SuiteEnvReads.HOST_TOKEN);
        assertThat(SuiteEnvReads.localOnly(STUDY, SuiteEnvReads.Policy.NONE, a))
                .isEqualTo("read " + TOKEN_VAR + ", which the module does not declare");
    }

    /**
     * The study's scoped record as this jk writes it now, and as it wrote it unscoped: the scoped one
     * goes to the shared cache with every value withheld, the unscoped one, keyed on this home's
     * secret, stays here.
     */
    @Test
    void a_record_keyed_on_this_host_alone_never_leaves_it() {
        String key = "k".repeat(64);
        CallerEnv a = home("alice", "/var/tmp/a/tok", "made-up-writer-token");
        String scoped = record(key, SuiteEnvReads.tokens(STUDY, PETCLINIC, a));
        String open = record(key, SuiteEnvReads.tokens(STUDY, SuiteEnvReads.Policy.NONE, a));

        assertThat(open).contains("INPUT this-host x:env-keyed\n");
        assertThat(ActionRecordText.shareable(ActionRecordText.parse(open, key)))
                .isFalse();
        assertThat(scoped).doesNotContain("x:env-keyed").doesNotContain("USER").doesNotContain(TOKEN_VAR);
        assertThat(ActionRecordText.shareable(ActionRecordText.parse(scoped, key)))
                .isTrue();
        assertThat(ActionRecordText.renderShared(ActionRecordText.parse(scoped, key)))
                .contains("INPUT - x:env-read:SPRING_PROFILES_ACTIVE\n")
                .doesNotContain(Hashing.sha256Hex("SPRING_PROFILES_ACTIVE\u0000test"));
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
