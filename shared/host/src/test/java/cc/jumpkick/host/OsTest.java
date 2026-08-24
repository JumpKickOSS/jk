// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OsTest {
    @Test
    void windowsPredicateDoesNotMatchDarwin() {
        String os = System.getProperty("os.name", "");
        if (os.startsWith("Mac") || os.startsWith("Darwin")) {
            assertThat(Os.isWindows()).isFalse();
            assertThat(Os.isDarwin()).isTrue();
        }
        if (os.startsWith("Linux")) {
            assertThat(Os.isWindows()).isFalse();
            assertThat(Os.isLinux()).isTrue();
            assertThat(Os.isDarwin()).isFalse();
        }
        // "win" is a substring of Darwin — the helper must use "windows"
        assertThat("Darwin".toLowerCase().contains("win")).isTrue();
        assertThat("Darwin".toLowerCase().contains("windows")).isFalse();
    }
}
