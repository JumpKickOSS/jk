// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The manual's kind and key tables are renderings of this tree's compiled schema: the guard suite
 * compares them from the tree's classes, and so does this test, so a hint edited in {@code
 * KindSchemas} without its row in {@code docs/user/guards.md} is red in the module's own tier.
 */
class GuardDocTablesTest {

    @Test
    void the_keys_table_in_the_manual_is_this_trees_rendering() throws IOException {
        List<String> doc = Files.readAllLines(RepoRoot.file(GuardDocTablesTest.class, "docs/user/guards.md"));
        assertThat(between(doc, "guard-schemas")).isEqualTo(GuardDocTables.schemas());
    }

    @Test
    void the_kinds_table_in_the_manual_is_this_trees_rendering() throws IOException {
        List<String> doc = Files.readAllLines(RepoRoot.file(GuardDocTablesTest.class, "docs/user/guards.md"));
        assertThat(between(doc, "guard-kinds")).isEqualTo(GuardDocTables.kinds());
    }

    @Test
    void a_table_carries_its_header_its_separator_and_pipe_free_cells() {
        List<String> schemas = GuardDocTables.schemas();
        assertThat(schemas.get(0)).isEqualTo("| kind | key | required | type | meaning |");
        assertThat(schemas.get(1)).isEqualTo("|---|---|---|---|---|");
        assertThat(schemas).anyMatch(l -> l.startsWith("| depend | convergence |  | bool | "));
        assertThat(schemas).allMatch(l -> l.startsWith("|") && l.endsWith("|"));
    }

    /** The lines strictly between the {@code <name>:start} and {@code <name>:end} markers. */
    private static List<String> between(List<String> lines, String name) {
        int start = lines.indexOf("<!-- " + name + ":start -->");
        int end = lines.indexOf("<!-- " + name + ":end -->");
        assertThat(start).as("start marker").isNotNegative();
        assertThat(end).as("end marker").isGreaterThan(start);
        return lines.subList(start + 1, end);
    }
}
