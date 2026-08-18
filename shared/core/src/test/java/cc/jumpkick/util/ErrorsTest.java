// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ErrorsTest {

    @Test
    void message_less_exceptions_surface_their_class_not_the_literal_null() {
        assertThat(Errors.text(new NullPointerException())).contains("NullPointerException");
        assertThat(Errors.text(new IllegalStateException("boom"))).isEqualTo("boom");
        assertThat(Errors.text(new IllegalStateException("  "))).contains("IllegalStateException");
        assertThat(Errors.text(null)).isEqualTo("unknown error");
    }
}
