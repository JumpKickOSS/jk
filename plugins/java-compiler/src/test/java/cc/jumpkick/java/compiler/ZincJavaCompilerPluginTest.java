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

/** A javac plugin named in the options is found on the processor path and receives its options. */
class ZincJavaCompilerPluginTest {

    @Test
    void a_plugin_option_with_spaces_reaches_javac_as_one_argument(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src"));
        Files.writeString(src.resolve("A.java"), "package a; public class A {}");
        Path record = dir.resolve("probe.txt");
        // javac's plugin syntax: one argument, whitespace-separated inside — the worker must not
        // split it, and javac must see the plugin on the processor path, where its jar lives.
        String option = "-Xplugin:" + ProbePlugin.NAME + " " + record + " -Xep:NullAway:ERROR alpha";

        ZincJavaCompiler.Result r = ZincJavaCompiler.compileJava(new JavaCompileJob(
                List.of(src.resolve("A.java")),
                List.of(),
                dir.resolve("classes"),
                dir.resolve("zinc-work"),
                null,
                25,
                List.of(option),
                probeProcessorPath(dir)));

        assertThat(r.success()).as(r.diagnostics().toString()).isTrue();
        assertThat(Files.readAllLines(record)).containsExactly("-Xep:NullAway:ERROR", "alpha");
    }

    /**
     * A processor-path directory holding {@link ProbePlugin}'s class and its {@code META-INF/services}
     * registration, laid out the way its jar would be. Assembled from classpath resources, so it
     * does not depend on where the test harness put the compiled test classes.
     */
    private static List<Path> probeProcessorPath(Path dir) throws IOException {
        Path root = dir.resolve("probe-plugin");
        String classFile = ProbePlugin.class.getName().replace('.', '/') + ".class";
        try (InputStream in = ProbePlugin.class.getClassLoader().getResourceAsStream(classFile)) {
            assertThat(in)
                    .as("the probe's class bytes are on the test classpath")
                    .isNotNull();
            Path target = root.resolve(classFile);
            Files.createDirectories(target.getParent());
            Files.copy(in, target);
        }
        Path services = Files.createDirectories(root.resolve("META-INF/services"));
        Files.writeString(services.resolve("com.sun.source.util.Plugin"), ProbePlugin.class.getName() + "\n");
        return List.of(root);
    }
}
