// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.micronaut;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * JK-1666: {@code effective-aot.properties} is the file you read to answer "what config did AOT
 * actually run with?", and it is a declared step output. Two identical runs must produce identical
 * bytes.
 */
class MicronautAotPropertiesTest {

    @Test
    void two_renders_of_the_same_config_are_byte_identical() {
        assertThat(MicronautPlugin.renderProperties(props("b", "2", "a", "1")))
                .isEqualTo(MicronautPlugin.renderProperties(props("a", "1", "b", "2")));
    }

    @Test
    void keys_are_sorted_and_there_is_no_date_comment() {
        String rendered = MicronautPlugin.renderProperties(props("zeta", "1", "alpha", "2", "mid", "3"));

        assertThat(rendered).isEqualTo("""
                        # Effective Micronaut AOT configuration (jk)
                        alpha=2
                        mid=3
                        zeta=1
                        """);
    }

    @Test
    void values_needing_escapes_round_trip_through_properties_load() throws Exception {
        Properties original = props(
                "plain", "true",
                "with.equals", "a=b",
                "with.colon", "http://example.com",
                "with spaces", "  leading and trailing  ",
                "with.newline", "line1\nline2",
                "with.backslash", "C:\\path\\to",
                "with.hash", "#not-a-comment",
                "with.unicode", "café — ☕");

        Properties reloaded = new Properties();
        reloaded.load(new StringReader(MicronautPlugin.renderProperties(original)));

        assertThat(reloaded).isEqualTo(original);
    }

    private static Properties props(String... keyValues) {
        Properties p = new Properties();
        for (int i = 0; i < keyValues.length; i += 2) p.setProperty(keyValues[i], keyValues[i + 1]);
        return p;
    }
}
