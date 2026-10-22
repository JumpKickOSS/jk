// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.tomlj.Toml;

class ManifestGraphTest {

    private static GraphConfig parse(String toml) {
        return ManifestGraph.parse(Toml.parse(toml));
    }

    @Test
    void absentIsOffWithNoAliases() {
        assertThat(parse("name = \"a\"\n")).isEqualTo(GraphConfig.EMPTY);
    }

    @Test
    void readsTheMirrorSwitchAndTheAliases() {
        GraphConfig c = parse("""
                [graph]
                graphify = true

                [graph.aliases]
                Tariff = ["price", "rate card"]
                """);
        assertThat(c.graphify()).isTrue();
        assertThat(c.aliases()).containsEntry("Tariff", List.of("price", "rate card"));
    }

    @Test
    void refusesWhatItDoesNotKnow() {
        assertThatThrownBy(() -> parse("[graph]\ngraphifi = true\n")).hasMessageContaining("unknown key `graphifi`");
        assertThatThrownBy(() -> parse("[graph]\ngraphify = \"yes\"\n")).hasMessageContaining("boolean");
        assertThatThrownBy(() -> parse("[graph.aliases]\nTariff = \"price\"\n")).hasMessageContaining("list of words");
    }
}
