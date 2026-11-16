// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * A {@code JK_HOME} set as a Windows User environment variable is an install of its own; one set
 * for a process alone is private. The value is read from {@code reg query} and compared as Windows
 * compares paths.
 */
class PersistedHomeTest {

    @Test
    void the_value_is_read_from_reg_query_output() {
        String out = "\r\nHKEY_CURRENT_USER\\Environment\r\n    JK_HOME    REG_SZ    D:\\jk home\r\n\r\n";
        assertThat(PersistedHome.regValue(out, "JK_HOME")).isEqualTo("D:\\jk home");
        assertThat(PersistedHome.regValue(
                        "HKEY_CURRENT_USER\\Environment\n    jk_home    REG_EXPAND_SZ    %DEVDRIVE%\\jk\n", "JK_HOME"))
                .isEqualTo("%DEVDRIVE%\\jk");
    }

    @Test
    void no_value_names_no_home() {
        assertThat(PersistedHome.regValue(null, "JK_HOME")).isNull();
        assertThat(PersistedHome.regValue(
                        "ERROR: The system was unable to find the specified registry key or value.", "JK_HOME"))
                .isNull();
        assertThat(PersistedHome.regValue("    JK_HOME_OLD    REG_SZ    D:\\old\n", "JK_HOME"))
                .isNull();
    }

    @Test
    void the_persisted_home_compares_as_windows_compares_paths() {
        assertThat(PersistedHome.same("D:\\jk", "D:\\jk")).isTrue();
        assertThat(PersistedHome.same("D:\\jk", "d:\\JK\\"))
                .as("case and a trailing separator")
                .isTrue();
        assertThat(PersistedHome.same("D:\\jk", "D:/jk")).as("either separator").isTrue();
        assertThat(PersistedHome.same("\\\\server\\share\\jk", "\\\\SERVER\\share\\jk"))
                .as("a UNC home")
                .isTrue();
    }

    @Test
    void another_or_unusable_persisted_value_leaves_the_home_private() {
        assertThat(PersistedHome.same("C:\\Users\\me\\scratch-jk", "D:\\jk")).isFalse();
        assertThat(PersistedHome.same("D:\\jk", null)).isFalse();
        assertThat(PersistedHome.same("D:\\jk", "  ")).isFalse();
        assertThat(PersistedHome.same("jk", "jk"))
                .as("a relative value names no home")
                .isFalse();
        assertThat(PersistedHome.same("D:\\jk2", "D:\\jk")).isFalse();
    }
}
