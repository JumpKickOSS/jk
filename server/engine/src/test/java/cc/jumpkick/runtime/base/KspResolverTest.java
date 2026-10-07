// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.config.JkBuildParser;
import org.junit.jupiter.api.Test;

/** jk's KSP2 release is written down: the default, and a module's {@code [build] ksp-version} over it. */
class KspResolverTest {

    private static final String PROJECT = """
            name    = "demo"
            group   = "com.demo"
            version = "0.1.0"
            java    = 25
            kotlin  = "2.4.10"
            """;

    @Test
    void the_default_is_pinned() {
        assertThat(KspResolver.DEFAULT_VERSION).isEqualTo("2.3.12");
        assertThat(KspResolver.versionFor(JkBuildParser.parse(PROJECT))).isEqualTo(KspResolver.DEFAULT_VERSION);
    }

    @Test
    void a_module_pin_is_the_version_its_round_runs() {
        var pinned = JkBuildParser.parse(PROJECT + """

                [build]
                ksp-version = "2.3.10"
                """);
        assertThat(KspResolver.versionFor(pinned)).isEqualTo("2.3.10");
        var exact = JkBuildParser.parse(PROJECT + """

                [build]
                ksp-version = "=2.3.11"
                """);
        assertThat(KspResolver.versionFor(exact)).isEqualTo("2.3.11");
    }

    @Test
    void a_floating_ksp_version_is_refused() {
        assertThatThrownBy(() -> JkBuildParser.parse(PROJECT + """

                        [build]
                        ksp-version = "^2.3"
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("ksp-version must be an exact version");
    }
}
