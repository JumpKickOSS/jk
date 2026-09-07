// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.StringLength;

/** {@link MinimalToml#quote} is injective and {@link MinimalToml#unquote} is its inverse, on any string. */
class MinimalTomlPropertyTest {

    @Property(tries = 1000)
    void unquote_inverts_quote(@ForAll @StringLength(max = 200) String s) {
        assertThat(MinimalToml.unquote(MinimalToml.quote(s))).isEqualTo(s);
    }

    @Property(tries = 1000)
    void quoted_form_is_a_single_line_basic_string(@ForAll @StringLength(max = 200) String s) {
        String q = MinimalToml.quote(s);
        assertThat(q).startsWith("\"").endsWith("\"");
        assertThat(q)
                .as("no raw line terminator survives quoting")
                .doesNotContain("\n")
                .doesNotContain("\r");
    }
}
