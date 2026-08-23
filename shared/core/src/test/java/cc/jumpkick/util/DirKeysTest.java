// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

class DirKeysTest {

    @Test
    void windows_shaped_paths_are_slash_folded_anywhere() {
        assertThat(DirKeys.slashes("C:\\ws\\app")).isEqualTo("C:/ws/app");
        assertThat(DirKeys.slashes("\\\\server\\share\\ws")).isEqualTo("//server/share/ws");
        assertThat(DirKeys.key("c:\\ws\\app")).isEqualTo("C:/ws/app");
        assertThat(DirKeys.key("C:/ws/app")).isEqualTo("C:/ws/app");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void posix_backslash_names_pass_through_verbatim() {
        // On POSIX a backslash is a legal filename character — a file literally named a\b must
        // neither display as nor key equal to a real a/b path.
        assertThat(DirKeys.slashes("/home/x/a\\b")).isEqualTo("/home/x/a\\b");
        assertThat(DirKeys.key("/home/x/a\\b")).isEqualTo("/home/x/a\\b");
    }

    @Test
    void drive_letter_case_is_one_key() {
        // NTFS is case-insensitive and launchers disagree about drive case (cd c:\ws) — one
        // directory must be one journal/metrics/bind key.
        assertThat(DirKeys.key("c:/ws/app")).isEqualTo(DirKeys.key("C:\\ws\\app"));
    }
}
