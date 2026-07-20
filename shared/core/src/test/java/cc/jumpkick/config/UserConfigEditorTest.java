// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UserConfigEditorTest {

    @Test
    void creates_global_block() {
        String out = UserConfigEditor.upsertNerdfont("", "true");
        assertThat(out).contains("[global]").contains("nerdfont = true");
    }

    @Test
    void replaces_existing_line() {
        String in = "[global]\nnerdfont = true\nother = 1\n";
        String out = UserConfigEditor.upsertNerdfont(in, "false");
        assertThat(out).contains("nerdfont = false");
        assertThat(out).doesNotContain("nerdfont = true");
        assertThat(out).contains("other = 1");
    }

    @Test
    void inserts_into_existing_global() {
        String in = "[global]\ncolor = auto\n";
        String out = UserConfigEditor.upsertNerdfont(in, "false");
        assertThat(out).contains("[global]").contains("nerdfont = false").contains("color = auto");
    }
}
