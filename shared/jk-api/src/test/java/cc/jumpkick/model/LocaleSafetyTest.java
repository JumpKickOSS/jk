// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Identifier parses are locale-blind. Under {@code tr}/{@code az}, {@code 'i'} ⇄ {@code 'I'} do
 * not round-trip — {@code "TRADITIONAL".toLowerCase()} is {@code "tradıtıonal"} — so any parse
 * that forgets {@code Locale.ROOT} is wrong for an entire locale family. Guard G47 keeps new
 * sites out; this pins the behaviour of the parses that drifted.
 */
class LocaleSafetyTest {

    @Test
    void parses_survive_a_turkish_default_locale() {
        Locale prev = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            assertThat(DependencyKind.parse("MAIN")).isEqualTo(DependencyKind.MAIN);
            assertThat(Layout.parse("TRADITIONAL")).isEqualTo(Layout.TRADITIONAL);
            assertThat(Layout.parse("Simple")).isEqualTo(Layout.SIMPLE);
        } finally {
            Locale.setDefault(prev);
        }
    }
}
