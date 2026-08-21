// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.giter8;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Giter8ShortNamesTest {

    @Test
    void normalize_layout() {
        assertThat(Giter8ShortNames.normalizeLayout(null)).isEqualTo(Giter8ShortNames.LAYOUT_TRADITIONAL);
        assertThat(Giter8ShortNames.normalizeLayout("")).isEqualTo(Giter8ShortNames.LAYOUT_TRADITIONAL);
        assertThat(Giter8ShortNames.normalizeLayout("nope")).isEqualTo(Giter8ShortNames.LAYOUT_TRADITIONAL);
        assertThat(Giter8ShortNames.normalizeLayout("simple")).isEqualTo(Giter8ShortNames.LAYOUT_SIMPLE);
        assertThat(Giter8ShortNames.normalizeLayout("mill")).isEqualTo(Giter8ShortNames.LAYOUT_SIMPLE);
        assertThat(Giter8ShortNames.normalizeLayout("custom")).isEqualTo(Giter8ShortNames.LAYOUT_CUSTOM);
    }

    @Test
    void known_layout_tokens() {
        assertThat(Giter8ShortNames.isKnownLayoutToken("simple")).isTrue();
        assertThat(Giter8ShortNames.isKnownLayoutToken("traditional")).isTrue();
        assertThat(Giter8ShortNames.isKnownLayoutToken("custom")).isTrue();
        assertThat(Giter8ShortNames.isKnownLayoutToken("nope")).isFalse();
    }
}
