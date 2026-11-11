// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** {@code [test] cache}: per class unless the module says its suite reruns whole. */
class ManifestTestCacheTest {

    private static boolean wholeModule(String toml) {
        return JkBuildParser.parse("name = \"m\"\ngroup = \"g\"\nversion = \"1.0\"\n" + toml)
                .build()
                .testCacheModule();
    }

    @Test
    void per_class_by_default_and_when_said() {
        assertThat(wholeModule("")).isFalse();
        assertThat(wholeModule("[test]\ncache = \"class\"\n")).isFalse();
    }

    @Test
    void module_reruns_the_suite_whole() {
        assertThat(wholeModule("[test]\ncache = \"module\"\n")).isTrue();
    }

    @Test
    void anything_else_is_refused() {
        assertThatThrownBy(() -> wholeModule("[test]\ncache = \"suite\"\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[test].cache must be \"class\" or \"module\"");
    }
}
