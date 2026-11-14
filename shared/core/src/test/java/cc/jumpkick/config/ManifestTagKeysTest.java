// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.tomlj.Toml;

/**
 * Suites choose tests: a profile or {@code [test]} naming a tag key is a config error that names the
 * suite table to write.
 */
class ManifestTagKeysTest {

    @Test
    void a_profile_that_chooses_tests_is_refused_with_the_suite_table() {
        for (String key : List.of("include-tags = [\"integration\"]", "exclude-tags = []", "all-suites = true")) {
            assertThatThrownBy(() -> ManifestTables.parseProfiles(Toml.parse("[profiles.integration]\n" + key + "\n")))
                    .isInstanceOf(JkBuildParseException.class)
                    .hasMessageContaining("[profiles.integration] has no key " + key.substring(0, key.indexOf(' ')))
                    .hasMessageContaining("[test.suites.<name>] tags")
                    .hasMessageContaining("jk test -s <name>");
        }
    }

    @Test
    void a_test_table_tag_filter_is_refused_with_the_suite_table() {
        for (String key : List.of("include-tags", "exclude-tags")) {
            assertThatThrownBy(() -> JkBuildParser.parse("""
                            group = "t"
                            name = "m"
                            version = "1.0.0"

                            [test]
                            %s = ["slow"]
                            """.formatted(key)))
                    .isInstanceOf(JkBuildParseException.class)
                    .hasMessageContaining("[test] has no key " + key)
                    .hasMessageContaining("[test.suites.<name>] tags");
        }
    }
}
