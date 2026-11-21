// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** A failure whose causes name each other is read once, not walked forever. */
class CyclicCauseTest {

    private static IllegalStateException cycle() {
        IllegalStateException outer = new IllegalStateException("outer");
        IllegalArgumentException inner = new IllegalArgumentException("inner");
        outer.initCause(inner);
        inner.initCause(outer);
        return outer;
    }

    @Test
    void a_cyclic_failure_with_no_cancel_in_it_is_not_a_cancel() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> CompileCancel.rethrowIfCancelled(cycle()));
    }

    @Test
    void a_cancel_under_a_wrapper_is_still_found() {
        CompileCancel.Cancelled cancelled = new CompileCancel.Cancelled();
        assertThatThrownBy(
                        () -> CompileCancel.rethrowIfCancelled(new RuntimeException(new RuntimeException(cancelled))))
                .isSameAs(cancelled);
    }

    @Test
    void a_cyclic_crash_names_each_cause_once() {
        String text = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> ZincJavaCompiler.crashed(cycle()));

        assertThat(text)
                .startsWith("the compiler worker failed: java.lang.IllegalStateException: outer\n"
                        + "  caused by java.lang.IllegalArgumentException: inner\n\tat ");
    }
}
