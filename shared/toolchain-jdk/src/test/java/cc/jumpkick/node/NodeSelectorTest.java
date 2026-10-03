// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.ToolchainSpec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class NodeSelectorTest {

    static List<NodeRelease> recordedIndex() throws IOException {
        try (InputStream in = Objects.requireNonNull(NodeSelectorTest.class.getResourceAsStream("index.json"))) {
            return NodeRelease.parseIndex(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static String select(String spec) throws IOException {
        return NodeSelector.select(recordedIndex(), NodeSpec.parse(spec)).version();
    }

    @Test
    void the_index_reads_versions_without_their_v_and_lts_codenames() throws IOException {
        NodeRelease krypton = recordedIndex().get(2);
        assertThat(krypton).isEqualTo(new NodeRelease("24.21.0", "11.6.0", "Krypton", true));
        assertThat(recordedIndex().get(1).lts()).isNull();
    }

    @Test
    void each_spec_form_selects_its_release() throws IOException {
        assertThat(select("24")).isEqualTo("24.21.0");
        assertThat(select("24.20")).isEqualTo("24.20.0");
        assertThat(select("24.20.0")).isEqualTo("24.20.0");
        assertThat(select("=24.20.0")).isEqualTo("24.20.0");
        assertThat(select("v22.22.0")).isEqualTo("22.22.0");
        assertThat(select("lts")).isEqualTo("24.21.0");
        assertThat(select("lts/jod")).isEqualTo("22.22.0");
        assertThat(select("lts/Krypton")).isEqualTo("24.21.0");
    }

    @Test
    void a_pre_release_is_never_selected() throws IOException {
        assertThat(select("latest")).isEqualTo("26.1.0");
        assertThatThrownBy(() -> select("27")).hasMessageContaining("no Node release matches 27");
    }

    @Test
    void a_version_the_index_does_not_list_is_refused_with_the_newest_of_its_major() {
        assertThatThrownBy(() -> select("24.99.0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("newest 24.x is 24.21.0");
    }

    @Test
    void a_discovered_install_satisfies_a_suggestion_of_its_major_and_a_pin_only_exactly() throws IOException {
        List<NodeRelease> index = recordedIndex();
        assertThat(NodeSelector.satisfies(NodeSpec.parse("24"), "24.0.1", index))
                .isTrue();
        assertThat(NodeSelector.satisfies(NodeSpec.parse("24.21.0"), "24.3.0", index))
                .isTrue();
        assertThat(NodeSelector.satisfies(NodeSpec.parse("=24.0.0"), "24.21.0", index))
                .isFalse();
        assertThat(NodeSelector.satisfies(NodeSpec.parse("=24.21.0"), "24.21.0", index))
                .isTrue();
        assertThat(NodeSelector.satisfies(NodeSpec.parse("24"), "22.22.0", index))
                .isFalse();
        assertThat(NodeSelector.satisfies(NodeSpec.parse("lts/jod"), "22.22.0", index))
                .isTrue();
        assertThat(NodeSelector.satisfies(NodeSpec.parse("lts"), "26.1.0", index))
                .isFalse();
    }

    @Test
    void specs_that_name_no_release_are_refused() {
        assertThatThrownBy(() -> NodeSpec.parse("=24")).hasMessageContaining("pins nothing");
        assertThatThrownBy(() -> NodeSpec.parse("hydrogen")).hasMessageContaining("names no Node release");
        assertThatThrownBy(() -> NodeSpec.parse(" ")).hasMessageContaining("empty");
    }

    @Test
    void a_toolchain_key_reads_as_its_spec() {
        assertThat(NodeSpec.of(ToolchainSpec.parse("node", "24")).toString()).isEqualTo("24");
        assertThat(NodeSpec.of(ToolchainSpec.parse("node", "=24.21.0")))
                .isEqualTo(new NodeSpec(NodeSpec.Kind.EXACT, "24.21.0", true));
        assertThat(NodeSpec.of(ToolchainSpec.parse("node", "lts")).kind()).isEqualTo(NodeSpec.Kind.LTS);
    }

    @Test
    void the_platform_follows_node_s_archive_names() {
        assertThat(NodePlatform.of("Linux", "amd64", "glibc").archiveName("24.21.0"))
                .isEqualTo("node-v24.21.0-linux-x64.tar.gz");
        assertThat(NodePlatform.of("Linux", "aarch64", "musl").key()).isEqualTo("linux-arm64-musl");
        assertThat(NodePlatform.of("Mac OS X", "aarch64", "libc").key()).isEqualTo("darwin-arm64");
        assertThat(NodePlatform.of("Windows 11", "amd64", "c_std_lib").archiveName("24.21.0"))
                .isEqualTo("node-v24.21.0-win-x64.zip");
    }
}
