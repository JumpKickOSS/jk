// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.CallerEnv;
import cc.jumpkick.config.RequestShell;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.wire.protocol.ProjectInfo;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a suite read of the caller's environment keys its result: each variable read by its value
 * (or its absence), a listing by the names present less the volatile ones, a program started by
 * every value less the volatile ones; a strict module by none of it.
 */
class SuiteEnvReadsTest {

    private static final SuiteEnvReads.Policy OPEN = SuiteEnvReads.Policy.NONE;

    private static CallerEnv caller(Map<String, String> vars) {
        return CallerEnv.of(RequestShell.of(vars, Set.of(), true, null), null);
    }

    @Test
    void a_variable_read_keys_by_its_value_and_its_absence_and_never_carries_the_value() {
        SuiteEnvReads.Record read = new SuiteEnvReads.Record(Set.of("MUST_BE_TRUE"), false, false);
        List<String> t = SuiteEnvReads.tokens(read, OPEN, caller(Map.of("MUST_BE_TRUE", "true")));
        List<String> again = SuiteEnvReads.tokens(read, OPEN, caller(Map.of("MUST_BE_TRUE", "true", "X", "1")));
        List<String> banana = SuiteEnvReads.tokens(read, OPEN, caller(Map.of("MUST_BE_TRUE", "banana")));
        List<String> unset = SuiteEnvReads.tokens(read, OPEN, caller(Map.of("OTHER", "1")));
        assertThat(t)
                .as("an undeclared value keys on this home alone")
                .hasSize(2)
                .contains(SuiteEnvReads.HOST_TOKEN)
                .anyMatch(tok -> tok.startsWith("env-read:MUST_BE_TRUE="));
        assertThat(t.getFirst()).doesNotContain("true");
        assertThat(again).as("another variable never read").isEqualTo(t);
        assertThat(banana).isNotEqualTo(t);
        assertThat(unset).containsExactly("env-read:MUST_BE_TRUE=unset");
        assertThat(SuiteEnvReads.tokens(read, OPEN, CallerEnv.of(null, null)))
                .as("no caller to answer")
                .containsExactly("env-read:MUST_BE_TRUE=unknown", SuiteEnvReads.HOST_TOKEN);
    }

    @Test
    void a_listing_keys_on_the_names_less_the_volatile_and_the_machine_ones() {
        SuiteEnvReads.Record listed = new SuiteEnvReads.Record(Set.of(), true, false);
        List<String> one = SuiteEnvReads.tokens(
                listed, OPEN, caller(Map.of("MY_APP_FOO", "1", "TERM_SESSION_ID", "a", "PATH", "/bin")));
        List<String> otherTerminal = SuiteEnvReads.tokens(
                listed, OPEN, caller(Map.of("MY_APP_FOO", "2", "TERM_SESSION_ID", "b", "SSH_TTY", "/dev/pts/3")));
        List<String> newName = SuiteEnvReads.tokens(listed, OPEN, caller(Map.of("MY_APP_FOO", "1", "MY_APP_BAR", "1")));
        assertThat(otherTerminal)
                .as("a value never read, a session id, an ssh login and the PATH do not move it")
                .isEqualTo(one);
        assertThat(newName).as("a new name does").isNotEqualTo(one);
    }

    @Test
    void a_program_started_keys_on_every_value_less_the_volatile_ones() {
        SuiteEnvReads.Record whole = new SuiteEnvReads.Record(Set.of(), false, true);
        List<String> one = SuiteEnvReads.tokens(whole, OPEN, caller(Map.of("A", "1", "PS1", "$ ")));
        assertThat(SuiteEnvReads.tokens(whole, OPEN, caller(Map.of("A", "1", "PS1", "# ", "WINDOWID", "7"))))
                .isEqualTo(one);
        assertThat(SuiteEnvReads.tokens(whole, OPEN, caller(Map.of("A", "2", "PS1", "$ "))))
                .isNotEqualTo(one);
    }

    @Test
    void a_strict_module_keys_nothing_and_a_scoped_one_keys_by_plain_digest_and_may_be_shared() {
        SuiteEnvReads.Record read = new SuiteEnvReads.Record(Set.of("SPRING_PROFILES_ACTIVE"), true, false);
        SuiteEnvReads.Policy strict = new SuiteEnvReads.Policy(true, List.of(), Set.of());
        SuiteEnvReads.Policy scoped = new SuiteEnvReads.Policy(false, List.of("SPRING_*"), Set.of());
        CallerEnv shell = caller(Map.of("SPRING_PROFILES_ACTIVE", "test", "HOME_ONLY", "x"));
        assertThat(SuiteEnvReads.tokens(read, strict, shell)).isEmpty();
        List<String> here = SuiteEnvReads.tokens(read, scoped, shell);
        assertThat(SuiteEnvReads.tokens(read, scoped, caller(Map.of("SPRING_PROFILES_ACTIVE", "test", "LAPTOP", "y"))))
                .as("names outside the scope are not seen, so another host's keys alike")
                .isEqualTo(here);
        assertThat(SuiteEnvReads.localOnly(read, scoped, shell)).isNull();
        assertThat(here).doesNotContain(SuiteEnvReads.HOST_TOKEN);
        assertThat(SuiteEnvReads.localOnly(read, OPEN, shell)).contains("SPRING_PROFILES_ACTIVE");
        assertThat(SuiteEnvReads.tokens(read, OPEN, shell)).contains(SuiteEnvReads.HOST_TOKEN);
        assertThat(SuiteEnvReads.localOnly(new SuiteEnvReads.Record(Set.of(), true, false), OPEN, shell))
                .contains("listed");
        assertThat(SuiteEnvReads.localOnly(read, scoped, CallerEnv.of(null, null)))
                .contains("no caller");
        assertThat(scoped.visible("SPRING_X")).isTrue();
        assertThat(scoped.visible("AWS_SECRET")).isFalse();
    }

    @Test
    void the_machine_names_and_the_declared_ones_are_held_not_read() {
        SuiteEnvReads.Policy policy = new SuiteEnvReads.Policy(false, List.of(), Set.of("DECLARED"));
        assertThat(policy.held("PATH")).isTrue();
        assertThat(policy.held("DECLARED")).isTrue();
        assertThat(policy.held("JAVA_HOME")).isTrue();
        assertThat(policy.held("MUST_BE_TRUE")).isFalse();
        assertThat(SuiteEnvReads.volatileName("TERM_SESSION_ID")).isTrue();
        assertThat(SuiteEnvReads.volatileName("=C:")).isTrue();
        assertThat(SuiteEnvReads.volatileName("DATABASE_URL")).isFalse();
        assertThat(SuiteEnvReads.volatileName("PSModulePath"))
                .as("PowerShell's module path, which a cmd window does not share")
                .isTrue();
        assertThat(SuiteEnvReads.volatileName("PSExecutionPolicyPreference")).isTrue();
    }

    @Test
    void the_record_is_kept_with_what_the_suite_read_and_named_to_the_client(@TempDir Path tmp) {
        Path module = tmp.resolve("m");
        SuiteReads.Discovered d = SuiteReads.Discovered.NONE.with(
                Set.of(), Set.of(), Set.of(), new SuiteEnvReads.Record(Set.of("B", "A"), true, false));
        SuiteReads.save(module, TestSelection.DEFAULT, d);
        assertThat(SuiteReads.load(module, TestSelection.DEFAULT).env())
                .isEqualTo(new SuiteEnvReads.Record(Set.of("A", "B"), true, false));
        assertThat(SuiteEnvReads.wanted(List.of(module.toString())))
                .containsExactlyInAnyOrder("A", "B", ProjectInfo.ENV_READS_NAMES);
        SuiteReads.Discovered more = SuiteReads.load(module, TestSelection.DEFAULT)
                .with(Set.of(), Set.of(), Set.of(), new SuiteEnvReads.Record(Set.of("C"), false, true));
        assertThat(more.env()).isEqualTo(new SuiteEnvReads.Record(Set.of("A", "B", "C"), true, true));
    }
}
