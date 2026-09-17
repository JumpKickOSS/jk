// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Equal short strings share one instance; long ones and {@code null} pass through. */
class InternedTest {

    @Test
    void equal_strings_share_one_instance() {
        String a = new String("org.apache.commons:commons-lang3");
        String b = new String("org.apache.commons:commons-lang3");
        assertThat(a).isNotSameAs(b);
        assertThat(Interned.of(a)).isSameAs(Interned.of(b));
        assertThat(Interned.of(a)).isEqualTo(a);
    }

    @Test
    void null_and_long_strings_pass_through() {
        assertThat(Interned.ofNullable(null)).isNull();
        String longOne = "x".repeat(Interned.MAX_LENGTH + 1);
        assertThat(Interned.of(longOne)).isSameAs(longOne);
        assertThat(Interned.of(new String(longOne))).isNotSameAs(longOne);
    }
}
