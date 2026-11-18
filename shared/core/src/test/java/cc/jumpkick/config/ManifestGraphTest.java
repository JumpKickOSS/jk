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
    void absentHasNoAliases() {
        assertThat(parse("name = \"a\"\n")).isEqualTo(GraphConfig.EMPTY);
    }

    @Test
    void readsTheAliases() {
        GraphConfig c = parse("""
                [graph.aliases]
                Tariff = ["price", "rate card"]
                """);
        assertThat(c.aliases()).containsEntry("Tariff", List.of("price", "rate card"));
    }

    @Test
    void readsWhatABuildsVerdictSaysOfProseLeftUnderAnOldName() {
        assertThat(parse("name = \"a\"\n").anchorTail()).isEqualTo(GraphConfig.TAIL_OFF);
        assertThat(parse("[graph]\nanchor-tail = \"silent\"\n").anchorTail()).isEqualTo(GraphConfig.TAIL_SILENT);
        assertThat(parse("[graph]\nanchor-tail = \"kinds\"\n").anchorTail()).isEqualTo(GraphConfig.TAIL_KINDS);
        assertThatThrownBy(() -> parse("[graph]\nanchor-tail = \"loud\"\n"))
                .hasMessageContaining("anchor-tail must be one of off, silent, count, kinds");
    }

    @Test
    void refusesWhatItDoesNotKnow() {
        assertThatThrownBy(() -> parse("[graph]\ngraphify = true\n"))
                .hasMessageContaining("unknown key `graphify`")
                .hasMessageEndingWith("expected aliases or anchor-tail");
        assertThatThrownBy(() -> parse("[graph.aliases]\nTariff = \"price\"\n")).hasMessageContaining("list of words");
    }
}
