// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every javac diagnostic this worker reports carries javac's own key for it, beside the text
 * Zinc renders: the results file matches its repair hints on the key, so a diagnostic that lost
 * its key on the way out of the worker would fall back to the shape of its message.
 */
class DiagnosticKeyTest {

    @Test
    void a_missing_symbol_and_a_missing_package_carry_javacs_keys(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("src/a/A.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package a;
                import com.acme.nowhere.Missing;
                public class A { void run() { missing(); } }
                """);

        ZincJavaCompiler.Result r = ZincJavaCompiler.compileJava(new JavaCompileJob(
                List.of(src), List.of(), dir.resolve("classes"), dir.resolve("work"), null, 25, List.of(), List.of()));

        assertThat(r.success()).isFalse();
        assertThat(r.diagnostics())
                .as("diagnostics: %s", r.diagnostics())
                .extracting(ZincJavaCompiler.Diag::key)
                .contains("compiler.err.doesnt.exist", "compiler.err.cant.resolve.location.args");
        ZincJavaCompiler.Diag missing = r.diagnostics().stream()
                .filter(d -> d.key().equals("compiler.err.cant.resolve.location.args"))
                .findFirst()
                .orElseThrow();
        assertThat(missing.kind()).isEqualTo("ERROR");
        assertThat(missing.message()).contains("cannot find symbol").contains("symbol:");
    }
}
