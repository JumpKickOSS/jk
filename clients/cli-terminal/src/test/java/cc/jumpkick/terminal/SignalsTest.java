// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The reflective {@code sun.misc.Signal} plumbing: the returns-true case is the test that goes red
 * when the reflection breaks (a renamed member, a metadata regression); the refusal case pins that
 * an unknown signal is a quiet {@code false}, never a throw.
 */
class SignalsTest {

    @Test
    void register_returns_true_on_this_jvm() {
        assertThat(Signals.register("INT", () -> {})).isTrue();
    }

    @Test
    void unknown_signal_returns_false_without_throwing() {
        assertThat(Signals.register("NOSUCHSIG", () -> {})).isFalse();
    }
}
