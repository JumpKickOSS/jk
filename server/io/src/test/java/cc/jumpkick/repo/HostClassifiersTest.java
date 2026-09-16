// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** One host, one row of Maven's platform words: OpenJFX's and os-maven-plugin's. */
class HostClassifiersTest {

    @ParameterizedTest
    @CsvSource({
        "Linux, amd64, linux, linux-x86_64",
        "Linux, aarch64, linux-aarch64, linux-aarch_64",
        "Mac OS X, x86_64, mac, osx-x86_64",
        "Mac OS X, aarch64, mac-aarch64, osx-aarch_64",
        "Windows 11, amd64, win, windows-x86_64",
    })
    void a_host_maps_to_openjfx_and_os_maven_plugin_words(
            String osName, String osArch, String javafx, String detected) {
        Map<String, String> table = HostClassifiers.properties(osName, osArch);
        assertThat(table.get("javafx.platform")).isEqualTo(javafx);
        assertThat(table.get("os.detected.classifier")).isEqualTo(detected);
        assertThat(table.get("os.detected.name") + "-" + table.get("os.detected.arch"))
                .isEqualTo(detected);
    }

    @Test
    void windows_on_arm_has_os_maven_plugin_words_but_no_openjfx_word() {
        Map<String, String> table = HostClassifiers.properties("Windows 11", "aarch64");
        assertThat(table.get("os.detected.classifier")).isEqualTo("windows-aarch_64");
        assertThat(table).doesNotContainKey("javafx.platform");
    }

    @Test
    void a_host_maven_has_no_word_for_gets_an_empty_table() {
        assertThat(HostClassifiers.properties("SunOS", "sparcv9")).isEmpty();
        assertThat(HostClassifiers.properties("Linux", "riscv64")).isEmpty();
        assertThat(HostClassifiers.properties(null, null)).isEmpty();
    }

    @Test
    void the_running_host_is_in_the_table() {
        assertThat(HostClassifiers.properties()).containsKeys("os.detected.classifier", "javafx.platform");
        assertThat(HostClassifiers.names("javafx.platform")).isTrue();
        assertThat(HostClassifiers.names("spring.version")).isFalse();
    }
}
