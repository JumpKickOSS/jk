// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code [test] fast-method-budget}: off unless set, a duration when it is. */
class ManifestTestBudgetTest {

    @TempDir
    Path tmp;

    private long parse(String toml) throws Exception {
        Path f = tmp.resolve("jk.toml");
        Files.writeString(f, "name = \"m\"\ngroup = \"g\"\nversion = \"1.0\"\n" + toml);
        return JkBuildParser.parse(f).build().testFastMethodBudgetMs();
    }

    @Test
    void there_is_no_budget_by_default() throws Exception {
        assertThat(parse("")).isZero();
        assertThat(parse("[test]\nassertions = true\n")).isZero();
    }

    @Test
    void the_budget_is_a_duration() throws Exception {
        assertThat(parse("[test]\nfast-method-budget = \"2s\"\n")).isEqualTo(2_000);
        assertThat(parse("[test]\nfast-method-budget = \"750ms\"\n")).isEqualTo(750);
        assertThat(parse("[test]\nfast-method-budget = 3\n")).isEqualTo(3_000);
    }

    @Test
    void anything_else_is_refused() {
        assertThatThrownBy(() -> parse("[test]\nfast-method-budget = \"fast\"\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[test].fast-method-budget must be a positive duration");
        assertThatThrownBy(() -> parse("[test]\nfast-method-budget = \"0s\"\n"))
                .isInstanceOf(JkBuildParseException.class);
    }
}
