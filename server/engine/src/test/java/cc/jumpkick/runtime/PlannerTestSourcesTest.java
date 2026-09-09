// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * javac is driven from the primary suite root plus an explicit file list. Every selected Java
 * source outside that root — the other suites' roots and {@code [test] extra-src} — must be on the
 * list, or a {@code test} + {@code integration} selection compiles {@code src/test/java} alone and
 * the integration suite silently runs nothing.
 */
class PlannerTestSourcesTest {

    @Test
    void namesEverySelectedSourceOutsideThePrimaryRoot(@TempDir Path module) {
        Path unitRoot = module.resolve("src/test/java");
        Path unit = unitRoot.resolve("app/WidgetTest.java");
        Path integration = module.resolve("src/integration/java/app/WidgetIT.java");
        Path extra = module.resolve("shared/Helper.java");
        var sources = new PlannerTest.TestSources(
                unitRoot, List.of(unit, integration, extra), List.of(extra), List.of(), List.of(), List.of());

        assertThat(sources.javaOutsidePrimaryRoot()).containsExactly(integration, extra);
    }

    @Test
    void aSingleSuiteSelectionNamesNothingExtra(@TempDir Path module) {
        Path root = module.resolve("src/integration/java");
        Path it = root.resolve("app/WidgetIT.java");
        var sources = new PlannerTest.TestSources(root, List.of(it), List.of(), List.of(), List.of(), List.of());

        assertThat(sources.javaOutsidePrimaryRoot()).isEmpty();
    }
}
