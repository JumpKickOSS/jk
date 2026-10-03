// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.node.NodeRelease;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/** {@code jk update --major} moves a manifest's own {@code node} major, and nothing else. */
class NodeUpdatesTest {

    private static final List<NodeRelease> RELEASES = List.of(
            new NodeRelease("27.0.0", "11.9.0", null, false),
            new NodeRelease("26.1.0", "11.8.0", "Lithium", false),
            new NodeRelease("24.21.0", "11.6.0", "Krypton", false));

    private static final String HEAD = "name = \"web\"\ngroup = \"g\"\nversion = \"1.0\"\n";

    @Test
    void a_major_moves_to_the_newest_lts_major_never_a_current_line() {
        NodeUpdates.Move move = Objects.requireNonNull(NodeUpdates.major(HEAD + "node = 24\n", () -> RELEASES));

        assertThat(move.table()).isEqualTo("node");
        assertThat(move.from()).isEqualTo("24");
        assertThat(move.to()).isEqualTo("26");
        assertThat(move.text()).contains("node = 26\n").doesNotContain("node = 24");
    }

    @Test
    void the_table_form_moves_its_version_key() {
        NodeUpdates.Move move =
                Objects.requireNonNull(NodeUpdates.major(HEAD + "\n[node]\nversion = 24\n", () -> RELEASES));

        assertThat(move.table()).isEqualTo("node.version");
        assertThat(move.text()).contains("[node]").contains("version = 26\n");
    }

    @Test
    void a_pin_a_keyword_the_newest_major_and_an_undeclared_node_stay() {
        assertThat(NodeUpdates.major(HEAD + "node = \"=24.20.0\"\n", () -> RELEASES))
                .isNull();
        assertThat(NodeUpdates.major(HEAD + "node = \"lts\"\n", () -> RELEASES)).isNull();
        assertThat(NodeUpdates.major(HEAD + "node = 26\n", () -> RELEASES)).isNull();
        assertThat(NodeUpdates.major(HEAD, () -> {
                    throw new AssertionError("no node, no catalog read");
                }))
                .isNull();
    }
}
