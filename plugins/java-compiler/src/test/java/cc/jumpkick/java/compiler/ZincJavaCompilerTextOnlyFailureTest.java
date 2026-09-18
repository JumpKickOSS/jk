// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A javac that reports failure without a diagnostic — an analysis plugin that wrote its report as
 * raw text and counted an error — still names what happened: the text javac wrote is the failure's
 * diagnostic, never a bare exit status.
 */
class ZincJavaCompilerTextOnlyFailureTest {

    @Test
    void the_text_javac_wrote_is_the_failure_s_diagnostic(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src"));
        Files.writeString(src.resolve("A.java"), "package a; public class A {}");
        String report = "the-analysis-plugin-could-not-start:missing-frobnicator";

        ZincJavaCompiler.Result r = ZincJavaCompiler.compileJava(new JavaCompileJob(
                List.of(src.resolve("A.java")),
                List.of(),
                dir.resolve("classes"),
                dir.resolve("zinc-work"),
                null,
                25,
                List.of("-Xplugin:" + TextOnlyFailurePlugin.NAME + " " + report),
                pluginProcessorPath(dir)));

        assertThat(r.success()).isFalse();
        assertThat(r.diagnostics()).as(r.diagnostics().toString()).isNotEmpty();
        assertThat(r.diagnostics().stream().map(ZincJavaCompiler.Diag::message))
                .as("the raw text javac wrote is the diagnostic")
                .anySatisfy(m -> assertThat(m).contains(report));
        assertThat(r.diagnostics().stream().map(ZincJavaCompiler.Diag::message))
                .noneSatisfy(m -> assertThat(m).contains("non-zero exit code"));
    }

    private static List<Path> pluginProcessorPath(Path dir) throws IOException {
        Path root = dir.resolve("text-only-plugin");
        String classFile = TextOnlyFailurePlugin.class.getName().replace('.', '/') + ".class";
        try (InputStream in = TextOnlyFailurePlugin.class.getClassLoader().getResourceAsStream(classFile)) {
            assertThat(in)
                    .as("the plugin's class bytes are on the test classpath")
                    .isNotNull();
            Path target = root.resolve(classFile);
            Files.createDirectories(target.getParent());
            Files.copy(in, target);
        }
        Path services = Files.createDirectories(root.resolve("META-INF/services"));
        Files.writeString(services.resolve("com.sun.source.util.Plugin"), TextOnlyFailurePlugin.class.getName() + "\n");
        return List.of(root);
    }
}
