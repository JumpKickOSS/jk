// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class DeterministicPropertiesTest {

    @Test
    void separator_heavy_keys_and_values_round_trip_through_properties_load() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("key=with:every\\sep#and! bang", "value=with:sep\\and#hash!");
        entries.put(" leading.space.key", " leading and trailing  ");
        entries.put("controls", "line1\nline2\ttab\rcr\fff");
        entries.put("unicode", "café — ☕");
        entries.put("plain", "true");

        Properties reloaded = new Properties();
        reloaded.load(new StringReader(DeterministicProperties.render(entries)));

        assertThat(reloaded).containsExactlyInAnyOrderEntriesOf(entries);
    }

    @Test
    void two_renders_of_the_same_map_are_byte_identical_with_no_date_comment() {
        Map<String, String> a = new LinkedHashMap<>();
        a.put("zeta", "1");
        a.put("alpha", "2");
        Map<String, String> b = new LinkedHashMap<>();
        b.put("alpha", "2");
        b.put("zeta", "1");

        String rendered = DeterministicProperties.render(a);
        assertThat(rendered).isEqualTo(DeterministicProperties.render(b));
        assertThat(rendered).isEqualTo("alpha=2\nzeta=1\n");
        assertThat(rendered).doesNotContain("#");
    }
}
