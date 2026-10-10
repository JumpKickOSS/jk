// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.intellij;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The packaged plugin.xml carries the module's version and the build range, and nothing else changes. */
class PluginXmlTest {

    @Test
    void a_descriptor_without_either_element_gains_both_after_its_name() {
        String xml = """
                <!-- a comment -->
                <idea-plugin>
                  <id>cc.example</id>
                  <name>Example</name>
                  <description><![CDATA[<b>bold</b>]]></description>
                </idea-plugin>
                """;
        assertThat(PluginXml.patch(xml, "1.2.3", "262", null)).isEqualTo("""
                <!-- a comment -->
                <idea-plugin>
                  <id>cc.example</id>
                  <name>Example</name>
                  <version>1.2.3</version>
                  <idea-version since-build="262"/>
                  <description><![CDATA[<b>bold</b>]]></description>
                </idea-plugin>
                """);
    }

    @Test
    void existing_elements_are_replaced_in_place() {
        String xml = """
                <idea-plugin>
                  <version>0.0.1</version>
                  <idea-version since-build="241" until-build="252.*"/>
                </idea-plugin>
                """;
        assertThat(PluginXml.patch(xml, "2.0", "262", "263.*")).isEqualTo("""
                <idea-plugin>
                  <version>2.0</version>
                  <idea-version since-build="262" until-build="263.*"/>
                </idea-plugin>
                """);
    }

    @Test
    void a_bare_root_takes_the_elements_first() {
        assertThat(PluginXml.patch("<idea-plugin>\n</idea-plugin>\n", "1", "262", null))
                .isEqualTo(
                        "<idea-plugin>\n  <version>1</version>\n  <idea-version since-build=\"262\"/>\n</idea-plugin>\n");
    }
}
