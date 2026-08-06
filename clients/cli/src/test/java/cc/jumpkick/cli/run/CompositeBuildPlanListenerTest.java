// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import cc.jumpkick.run.BuildPlanListener;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Regression (JK-1578): {@code of} must tolerate null on EITHER side. The workspace test
 * headless path passes a null session mirror as the first argument when the transcript is
 * off — composing it blindly NPE'd on the first wire event.
 */
class CompositeBuildPlanListenerTest {

    private static final class Recording implements BuildPlanListener {
        final List<String> events = new ArrayList<>();

        @Override
        public void stepStart(String step, String group, int ticks) {
            events.add("start:" + step);
        }
    }

    @Test
    void null_first_returns_second() {
        Recording second = new Recording();
        BuildPlanListener l = CompositeBuildPlanListener.of(null, second);
        l.stepStart("compile-java", null, 1);
        assertThat(second.events).containsExactly("start:compile-java");
    }

    @Test
    void null_second_returns_first() {
        Recording first = new Recording();
        BuildPlanListener l = CompositeBuildPlanListener.of(first, null);
        l.stepStart("compile-java", null, 1);
        assertThat(first.events).containsExactly("start:compile-java");
    }

    @Test
    void both_null_is_a_safe_noop() {
        BuildPlanListener l = CompositeBuildPlanListener.of(null, null);
        assertThatCode(() -> l.stepStart("compile-java", null, 1)).doesNotThrowAnyException();
    }

    @Test
    void both_present_fan_out_in_order() {
        Recording first = new Recording();
        Recording second = new Recording();
        BuildPlanListener l = CompositeBuildPlanListener.of(first, second);
        l.stepStart("run-tests", "test", 3);
        assertThat(first.events).containsExactly("start:run-tests");
        assertThat(second.events).containsExactly("start:run-tests");
    }
}
