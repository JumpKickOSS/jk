// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sbt.internal.inc.FileAnalysisStore;
import xsbti.compile.AnalysisContents;

/**
 * javac refuses {@code --add-exports} or {@code --add-reads} of a system module together with
 * {@code --release}, so a module whose args export one (Hadoop's annotations reach {@code
 * jdk.javadoc.internal.tool}) compiles with {@code -source}/{@code -target} at its level, as the
 * Maven compiler plugin does when a POM writes {@code <source>}/{@code <target>}.
 */
class ZincJavaCompilerSystemModuleExportTest {

    private static final List<String> EXPORTS = List.of(
            "--add-modules", "jdk.javadoc", "--add-exports", "jdk.javadoc/jdk.javadoc.internal.tool=ALL-UNNAMED");

    @Test
    void an_export_of_a_system_module_compiles_with_source_and_target_instead_of_release(@TempDir Path dir)
            throws Exception {
        Path src = dir.resolve("src/a/UsesInternal.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package a;
                public class UsesInternal {
                    public static Class<?> tool() { return jdk.javadoc.internal.tool.Main.class; }
                }
                """);
        Path workdir = dir.resolve("zinc-work");

        ZincJavaCompiler.Result result = ZincJavaCompiler.compileJava(new JavaCompileJob(
                List.of(src), List.of(), dir.resolve("classes"), workdir, null, 17, EXPORTS, List.of()));

        assertThat(result.success()).as("diagnostics: %s", result.diagnostics()).isTrue();
        assertThat(dir.resolve("classes/a/UsesInternal.class")).isRegularFile();
        Optional<AnalysisContents> stored =
                FileAnalysisStore.binary(workdir.resolve("zinc").toFile()).get();
        assertThat(stored).isPresent();
        String[] options = stored.get().getMiniSetup().options().javacOptions();
        assertThat(options).containsSequence("-source", "17", "-target", "17").doesNotContain("--release");
    }
}
