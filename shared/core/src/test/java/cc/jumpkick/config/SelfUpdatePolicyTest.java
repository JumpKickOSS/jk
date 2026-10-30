// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.SelfUpdatePolicy.Action;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class SelfUpdatePolicyTest {

    private static final SelfUpdateState HELD = SelfUpdateState.EMPTY.withHold("1.2.0", "1.3.0");

    private static Action decide(
            String version, @Nullable String env, @Nullable String config, SelfUpdateState state, boolean attended) {
        return SelfUpdatePolicy.decide(version, env, config, state, attended).action();
    }

    @Test
    void a_snapshot_never_updates_itself_whatever_is_asked() {
        assertThat(decide("1.4.0-SNAPSHOT", "auto", "auto", SelfUpdateState.EMPTY, false))
                .isEqualTo(Action.OFF);
        assertThat(decide("0.17.0-SNAPSHOT", null, null, SelfUpdateState.EMPTY, false))
                .isEqualTo(Action.OFF);
    }

    @Test
    void a_hold_outranks_the_env_and_the_pre_release_rule() {
        assertThat(decide("1.2.0", "auto", null, HELD, false)).isEqualTo(Action.OFF);
        assertThat(decide("0.16.2", null, null, HELD, false)).isEqualTo(Action.OFF);
        assertThat(SelfUpdatePolicy.decide("0.16.2", null, null, HELD, false).reason())
                .contains("held at 1.2.0")
                .contains("jk self update");
    }

    @Test
    void the_env_outranks_the_pre_release_rule_and_the_config() {
        assertThat(decide("0.17.0", "never", "auto", SelfUpdateState.EMPTY, true))
                .isEqualTo(Action.OFF);
        assertThat(decide("1.2.0", "ask", "never", SelfUpdateState.EMPTY, false))
                .isEqualTo(Action.ASK);
        assertThat(decide("1.2.0", "auto", "ask", SelfUpdateState.EMPTY, true)).isEqualTo(Action.AUTO);
    }

    @Test
    void before_1_0_the_config_cannot_turn_updates_off_or_ask_first() {
        assertThat(decide("0.17.0", null, "never", SelfUpdateState.EMPTY, true)).isEqualTo(Action.AUTO);
        assertThat(decide("0.17.0", null, "ask", SelfUpdateState.EMPTY, true)).isEqualTo(Action.AUTO);
        assertThat(decide("1.0.0-rc1", null, "never", SelfUpdateState.EMPTY, true))
                .isEqualTo(Action.AUTO);
        assertThat(SelfUpdatePolicy.configIgnored("0.17.0", null, "never")).isTrue();
        assertThat(SelfUpdatePolicy.configIgnored("0.17.0", null, "auto")).isFalse();
        assertThat(SelfUpdatePolicy.configIgnored("0.17.0", "never", "ask")).isFalse();
        assertThat(SelfUpdatePolicy.configIgnored("1.2.0", null, "never")).isFalse();
    }

    @Test
    void from_1_0_the_config_decides() {
        assertThat(decide("1.2.0", null, "never", SelfUpdateState.EMPTY, true)).isEqualTo(Action.OFF);
        assertThat(decide("1.2.0", null, "ask", SelfUpdateState.EMPTY, false)).isEqualTo(Action.ASK);
        assertThat(decide("1.2.0", null, "AUTO", SelfUpdateState.EMPTY, true)).isEqualTo(Action.AUTO);
    }

    @Test
    void unset_asks_a_person_at_a_terminal_and_updates_everywhere_else() {
        assertThat(decide("1.2.0", null, null, SelfUpdateState.EMPTY, true)).isEqualTo(Action.ASK);
        assertThat(decide("1.2.0", null, null, SelfUpdateState.EMPTY, false)).isEqualTo(Action.AUTO);
        assertThat(decide("1.2.0", "bogus", "bogus", SelfUpdateState.EMPTY, false))
                .isEqualTo(Action.AUTO);
    }
}
