// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class XmlEntitiesTest {

    @Test
    void a_tabled_entity_becomes_its_numeric_reference() {
        assertThat(XmlEntities.numeric("<n>Laugst&oslash;l &copy; &euro;</n>"))
                .isEqualTo("<n>Laugst&#x00f8;l &#x00a9; &#x20ac;</n>");
    }

    @Test
    void xml_builtins_numeric_references_and_unknown_names_pass_through() {
        String xml = "<n>&amp;&lt;&gt;&quot;&apos;&#248;&#xF8;&bogus;&;&</n>";
        assertThat(XmlEntities.numeric(xml)).isSameAs(xml);
    }

    @Test
    void cdata_and_comments_are_left_alone() {
        String xml = "<p><![CDATA[&oslash;]]><!-- &oslash; --><n>&oslash;</n></p>";
        assertThat(XmlEntities.numeric(xml)).isEqualTo("<p><![CDATA[&oslash;]]><!-- &oslash; --><n>&#x00f8;</n></p>");
    }

    @Test
    void bytes_outside_ascii_survive_the_rewrite() {
        byte[] utf8 = "<n>café &oslash;</n>".getBytes(StandardCharsets.UTF_8);
        byte[] out = XmlEntities.numeric(utf8);
        assertThat(new String(out, StandardCharsets.UTF_8)).isEqualTo("<n>café &#x00f8;</n>");
        byte[] plain = "<n>café</n>".getBytes(StandardCharsets.UTF_8);
        assertThat(XmlEntities.numeric(plain)).isSameAs(plain);
    }

    @Test
    void the_table_is_the_html_four_set() {
        assertThat(XmlEntities.knows("nbsp")).isTrue();
        assertThat(XmlEntities.knows("diams")).isTrue();
        assertThat(XmlEntities.knows("thetasym")).isTrue();
        assertThat(XmlEntities.knows("amp")).isFalse();
        assertThat(XmlEntities.knows("bogus")).isFalse();
    }
}
