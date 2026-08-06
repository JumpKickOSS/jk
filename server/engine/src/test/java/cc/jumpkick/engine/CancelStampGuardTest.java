// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.runtime.ModuleOutcome;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * JK-1521: the user-cancel stamp guard. An EOF after a reported module failure is the
 * terminal-read race and must not re-label the failure as cancelled — but an explicit signal
 * (BUILD_CANCEL, dashboard cancel, wall deadline) is a genuine abort even when a module already
 * failed, and the journal must say cancelled.
 */
class CancelStampGuardTest {

    private static EngineServer.BuildAccumulator acc() {
        return new EngineServer.BuildAccumulator("build", "/w", "g:w", "cli");
    }

    private static ModuleOutcome failedModule() {
        return new ModuleOutcome("g:a", Path.of("/w/a"), false, 1, 10, true);
    }

    @Test
    void eof_after_module_failure_does_not_stamp() {
        var a = acc();
        a.addModule(failedModule());
        a.markUserCancelled(false);
        assertThat(a.wasCancelled()).isFalse();
    }

    @Test
    void explicit_cancel_after_module_failure_stamps() {
        var a = acc();
        a.addModule(failedModule());
        a.markUserCancelled(true);
        assertThat(a.wasCancelled()).isTrue();
        // And the resolved journal bit agrees: stamped failure + user stamp → cancelled.
        assertThat(EngineServer.resolveCancelledFlag(false, a.wasCancelled(), true))
                .isTrue();
    }

    @Test
    void no_stamp_of_any_kind_once_the_outcome_is_set() {
        var a = acc();
        a.setOutcome(true, 0);
        a.markUserCancelled(true);
        assertThat(a.wasCancelled()).isFalse();

        var failed = acc();
        failed.setOutcome(false, 1);
        failed.markUserCancelled(true);
        assertThat(failed.wasCancelled()).isFalse();
    }

    @Test
    void explicit_and_eof_both_stamp_a_clean_running_build() {
        var a = acc();
        a.markUserCancelled(false);
        assertThat(a.wasCancelled()).isTrue();

        var b = acc();
        b.markUserCancelled(true);
        assertThat(b.wasCancelled()).isTrue();
    }
}
