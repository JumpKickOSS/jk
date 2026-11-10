// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compile.CompileResult.Diagnostic;
import cc.jumpkick.compile.CompileResult.Severity;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.task.CompileWarnings;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What a module the build does not run says, and what a module that dropped a step stops saying. */
class StepWarningsTest {

    @Test
    void a_module_the_build_skips_says_its_kept_warnings_in_build_order(@TempDir Path dir) throws Exception {
        Path module = module(dir);
        Path a = Files.writeString(
                Files.createDirectories(module.resolve("src/a")).resolve("A.java"), "");
        keep(module, "compile-test", "javac", warning(a, "test warning"));
        keep(module, "compile-java", "javac", warning(a, a + ":1: warning: main warning"));
        keep(module, "compile-test+ksp", "warn", warning(null, "ksp says"));

        List<String> said = said(module, true);
        assertThat(said)
                .containsExactly(
                        "compile-java javac " + a + ":1: warning: main warning",
                        "compile-test warn ksp says",
                        "compile-test javac test warning");
        assertThat(said(module, false)).as("a build that compiles no tests").hasSize(1);
    }

    @Test
    void a_step_the_plan_no_longer_has_stops_saying_its_warnings(@TempDir Path dir) throws Exception {
        Path module = module(dir);
        keep(module, "compile-kotlin", "kotlinc", warning(null, "k"));
        keep(module, "compile-java", "javac", warning(null, "j"));
        keep(module, "compile-test", "javac", warning(null, "t"));

        StepWarnings.forgetStepsNotIn(module, List.of("compile-java"));
        assertThat(said(module, true))
                .as("a plan that skipped tests leaves their warnings")
                .containsExactly("compile-java javac j", "compile-test javac t");

        StepWarnings.forgetStepsNotIn(module, List.of("compile-java", "compile-test-fixtures"));
        StepWarnings.forgetStepsNotIn(module, List.of("compile-java", "compile-test"));
        assertThat(said(module, true)).containsExactly("compile-java javac j", "compile-test javac t");
        StepWarnings.forgetStepsNotIn(module, List.of("compile-kotlin", "compile-test"));
        assertThat(said(module, true)).containsExactly("compile-test javac t");
    }

    private static List<String> said(Path module, boolean tests) {
        List<String> out = new ArrayList<>();
        StepWarnings.replayModule(module, "com.example:app", tests, new WorkspaceBuildListener() {
            @Override
            public void onModuleWarning(Path dir, String coord, String step, String code, String message) {
                out.add(step + " " + code + " " + message);
            }
        });
        return out;
    }

    private static void keep(Path module, String file, String tool, Diagnostic warning) {
        Path incremental = BuildLayout.moduleTargetDir(module).resolve("incremental");
        CompileWarnings.writeCurrent(CompileWarnings.currentFile(incremental, file), tool, List.of(warning));
    }

    private static Diagnostic warning(@Nullable Path source, String message) {
        return new Diagnostic(Severity.WARNING, source, 1, 0, message);
    }

    private static Path module(Path dir) throws Exception {
        Path module = Files.createDirectories(dir.resolve("app"));
        Files.writeString(module.resolve("jk.toml"), "name = \"app\"\n");
        return module;
    }
}
