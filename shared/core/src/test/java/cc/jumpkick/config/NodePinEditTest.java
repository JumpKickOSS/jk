// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Writing a manifest's Node.js version where TOML lets it live. */
class NodePinEditTest {

    private static final String HEAD = "name = \"web\"\ngroup = \"g\"\nversion = \"1.0\"\n";

    @Test
    void a_manifest_without_a_node_table_gets_the_root_key() {
        String out = NodePinEdit.apply(HEAD + "\n[dependencies]\n", "22");
        assertThat(out).contains("node = 22\n").contains("[dependencies]");
        assertThat(JkBuildParser.parse(out).project().nodeSpec().toString()).contains("22");
    }

    @Test
    void an_existing_root_key_is_rewritten() {
        assertThat(NodePinEdit.apply(HEAD + "node = 20\n", "\"lts\""))
                .contains("node = \"lts\"")
                .doesNotContain("20");
    }

    @Test
    void a_node_table_takes_the_version_inside_it() {
        String added = NodePinEdit.apply(HEAD + "\n[node]\nbuild = \"build\"\n", "24");
        assertThat(added).contains("[node]\nversion = 24\nbuild = \"build\"");
        String rewritten = NodePinEdit.apply(HEAD + "\n[node]\nversion = 22\n", "\"=24.21.0\"");
        assertThat(rewritten).contains("version = \"=24.21.0\"").doesNotContain("version = 22");
    }

    @Test
    void a_bare_major_is_written_as_a_number_and_anything_else_as_a_string() {
        assertThat(NodePinEdit.tomlValue("24")).isEqualTo("24");
        assertThat(NodePinEdit.tomlValue("=24.21.0")).isEqualTo("\"=24.21.0\"");
        assertThat(NodePinEdit.tomlValue("lts")).isEqualTo("\"lts\"");
        assertThat(NodePinEdit.tomlValue("\"lts\"")).isEqualTo("\"lts\"");
    }
}
