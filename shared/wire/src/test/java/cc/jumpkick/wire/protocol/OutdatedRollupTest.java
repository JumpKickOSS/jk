// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class OutdatedRollupTest {

    private static OutdatedReport.Row row(String module, String coord, String display, String scope, String cur) {
        return new OutdatedReport.Row(module, coord, display, scope, cur, cur, "1.3.0", "");
    }

    @Test
    void one_coordinate_across_three_modules_is_one_row_with_the_spread_lowest_first() {
        OutdatedReport r = OutdatedReport.of(
                true,
                List.of(
                        row("acme:app", "org.tomlj:tomlj", "tomlj", "main", "1.2.0"),
                        row("acme:core", "org.tomlj:tomlj", "tomlj", "main", "1.1.1"),
                        row("acme:cli", "org.tomlj:tomlj", "", "test", "1.1.1"),
                        new OutdatedReport.Row(
                                "acme:core",
                                "org.antlr:antlr4-runtime",
                                "",
                                "main",
                                "4.13.2",
                                "4.13.2",
                                "4.13.2",
                                "")));

        List<OutdatedReport.Rollup> rollups = r.rollup();
        assertThat(rollups)
                .extracting(OutdatedReport.Rollup::label)
                .containsExactly("org.antlr:antlr4-runtime", "tomlj");

        OutdatedReport.Rollup tomlj = rollups.get(1);
        assertThat(tomlj.modules()).containsExactly("acme:app", "acme:core", "acme:cli");
        assertThat(tomlj.current())
                .containsExactly(new OutdatedReport.Spread("1.1.1", 2), new OutdatedReport.Spread("1.2.0", 1));
        assertThat(OutdatedReport.spreadText(tomlj.current())).isEqualTo("1.1.1 ×2 · 1.2.0 ×1");
        assertThat(tomlj.currentDiffers()).isTrue();
        assertThat(tomlj.scopes()).containsExactly("main", "test");
        assertThat(tomlj.latest()).isEqualTo("1.3.0");
        assertThat(tomlj.canMove()).isTrue();

        OutdatedReport.Rollup antlr = rollups.get(0);
        assertThat(antlr.currentDiffers()).isFalse();
        assertThat(OutdatedReport.spreadText(antlr.current())).isEqualTo("4.13.2");
        assertThat(antlr.canMove()).isFalse();
        assertThat(r.movableRollup()).containsExactly(tomlj);
    }

    @Test
    void a_coordinate_moves_when_any_module_behind_it_can_and_an_unlocked_pin_sorts_first() {
        OutdatedReport r = OutdatedReport.of(
                true,
                List.of(
                        new OutdatedReport.Row("acme:app", "g:a", "", "main", "2.0", "2.0", "2.0", ""),
                        new OutdatedReport.Row("acme:lib", "g:a", "", "main", "", "", "2.0", "")));
        OutdatedReport.Rollup a = r.rollup().getFirst();
        assertThat(a.canMove()).isTrue();
        assertThat(OutdatedReport.spreadText(a.current())).isEqualTo("— ×1 · 2.0 ×1");
    }
}
