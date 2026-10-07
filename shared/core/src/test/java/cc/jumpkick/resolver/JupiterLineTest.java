// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Jupiter 5 rides Platform 1, Jupiter 6 rides Platform 6. */
class JupiterLineTest {

    @Test
    void jupiter_five_ships_with_platform_one_and_six_shares_the_number() {
        assertThat(JupiterLine.platformVersion("5.9.0")).isEqualTo("1.9.0");
        assertThat(JupiterLine.platformVersion("5.13.4")).isEqualTo("1.13.4");
        assertThat(JupiterLine.platformVersion("5.14.0-M1")).isEqualTo("1.14.0-M1");
        assertThat(JupiterLine.platformVersion("6.1.3")).isEqualTo("6.1.3");
    }

    @Test
    void a_managed_jupiter_names_the_launcher_line_nothing_else_manages() {
        assertThat(JupiterLine.launcherForManaged(Map.of("org.junit.jupiter:junit-jupiter", "6.0.2")))
                .isEqualTo("6.0.2");
        assertThat(JupiterLine.launcherForManaged(Map.of("org.junit.jupiter:junit-jupiter-api", "5.10.3")))
                .isEqualTo("1.10.3");
        assertThat(JupiterLine.launcherForManaged(Map.of("com.example:other", "1.0")))
                .isNull();
    }
}
