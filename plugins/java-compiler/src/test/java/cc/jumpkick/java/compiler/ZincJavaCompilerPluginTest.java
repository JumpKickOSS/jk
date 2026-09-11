// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
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
                probeProcessorPath()));

        assertThat(r.success()).as(r.diagnostics().toString()).isTrue();
        assertThat(Files.readAllLines(record)).containsExactly("-Xep:NullAway:ERROR", "alpha");
    }

    /** The test output roots holding {@link ProbePlugin} and its service registration. */
    private static List<Path> probeProcessorPath() throws Exception {
        Path classes = Path.of(ProbePlugin.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        String service = "META-INF/services/com.sun.source.util.Plugin";
        URL registration = ProbePlugin.class.getClassLoader().getResource(service);
        assertThat(registration)
                .as("the probe's service registration is on the test classpath")
                .isNotNull();
        String location = Path.of(registration.toURI()).toString();
        Path resources = Path.of(location.substring(0, location.length() - service.length()));
        return classes.equals(resources) ? List.of(classes) : List.of(classes, resources);
    }
}
