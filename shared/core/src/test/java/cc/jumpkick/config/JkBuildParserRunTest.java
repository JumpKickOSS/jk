// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** {@code [run]}: the file the running app logs to. */
class JkBuildParserRunTest {

    @Test
    void run_log_names_the_app_s_log_file() {
        assertThat(JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """

                        [run]
                        log = "logs/app.log"
                        """)
                        .build()
                        .runLog())
                .isEqualTo("logs/app.log");
        assertThat(JkBuildParser.parse(JkBuildParserFixtures.PROJECT).build().runLog())
                .isNull();
    }

    @Test
    void run_takes_only_log_and_log_is_a_file_name() {
        assertThatThrownBy(() -> JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """

                        [run]
                        port = 8080
                        """))
                .hasMessageContaining("port");
        assertThatThrownBy(() -> JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """

                        [run]
                        log = " "
                        """))
                .hasMessageContaining("[run] log");
        assertThatThrownBy(() -> JkBuildParser.parse(JkBuildParserFixtures.PROJECT + """

                        [run]
                        log = 3
                        """))
                .hasMessageContaining("[run] log must be a string");
    }
}
