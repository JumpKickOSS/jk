// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.TestSelection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code --exclusive}: only the selected modules' suites run; every module tests without it. */
class PlannerTestExclusiveTest {

    @TempDir
    Path ws;

    @Test
    void without_exclusive_every_module_runs_its_suite() throws Exception {
        Path lib = Files.createDirectories(ws.resolve("lib"));
        assertThat(PlannerTest.selectedForTests(TestSelection.DEFAULT, lib)).isTrue();
    }

    @Test
    void with_exclusive_only_the_selected_modules_run_their_suites() throws Exception {
        Path app = Files.createDirectories(ws.resolve("app"));
        Path lib = Files.createDirectories(ws.resolve("lib"));
        TestSelection sel = TestSelection.DEFAULT.withOnlyModules(List.of(app.toString()));
        assertThat(PlannerTest.selectedForTests(sel, app)).isTrue();
        assertThat(PlannerTest.selectedForTests(sel, lib)).isFalse();
    }

    @Test
    void the_match_is_on_the_canonical_directory_not_its_spelling() throws Exception {
        Path app = Files.createDirectories(ws.resolve("app"));
        TestSelection sel = TestSelection.DEFAULT.withOnlyModules(
                List.of(ws.resolve("lib").resolve("..").resolve("app").toString()));
        Files.createDirectories(ws.resolve("lib"));
        assertThat(PlannerTest.selectedForTests(sel, app)).isTrue();
    }

    @Test
    void exclusive_composes_with_class_patterns() throws Exception {
        Path app = Files.createDirectories(ws.resolve("app"));
        TestSelection sel =
                TestSelection.DEFAULT.withClasses(List.of("OrdersTest")).withOnlyModules(List.of(app.toString()));
        assertThat(sel.classes()).containsExactly("OrdersTest");
        assertThat(sel.withClasses(List.of("BarTest")).onlyModules()).containsExactly(app.toString());
        assertThat(PlannerTest.selectedForTests(sel, app)).isTrue();
    }
}
