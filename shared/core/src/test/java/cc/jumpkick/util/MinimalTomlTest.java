// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MinimalTomlTest {

    @Test
    void wraps_a_plain_value_in_quotes() {
        assertThat(MinimalToml.quote("1.2.3")).isEqualTo("\"1.2.3\"");
    }

    @Test
    void escapes_backslash_and_double_quote() {
        // A Windows-style path and an embedded quote — the two chars the old two-replace quoters got.
        assertThat(MinimalToml.quote("C:\\Program Files\\jdk")).isEqualTo("\"C:\\\\Program Files\\\\jdk\"");
        assertThat(MinimalToml.quote("say \"hi\"")).isEqualTo("\"say \\\"hi\\\"\"");
    }

    @Test
    void escapes_newline_tab_and_carriage_return() {
        // The class of value the two-replace quoters silently emitted as invalid TOML.
        assertThat(MinimalToml.quote("a\nb\tc\rd")).isEqualTo("\"a\\nb\\tc\\rd\"");
    }

    @Test
    void escapes_other_control_chars_as_unicode() {
        String controls = "" + (char) 0x00 + (char) 0x07 + (char) 0x1f;
        assertThat(MinimalToml.quote(controls)).isEqualTo("\"\\u0000\\u0007\\u001f\"");
    }

    @Test
    void leaves_printable_unicode_untouched() {
        assertThat(MinimalToml.quote("café ☕")).isEqualTo("\"café ☕\"");
    }

    @Test
    void empty_string_is_two_quotes() {
        assertThat(MinimalToml.quote("")).isEqualTo("\"\"");
    }
}
