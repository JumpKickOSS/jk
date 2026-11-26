// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.command.Exit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link PluginLoader}'s fork dispatch end-to-end on the workers this build made: each
 * is launched as {@code <java> -cp <launch classpath> PluginMain <args>}, the classpath rebuilt from
 * the worker's POM as every engine fork rebuilds it. A worker jar holds its own classes only, so
 * this is what proves the libraries its POM names (the plugin SDK, {@code jk-host}) reach the
 * fork. (jk has no in-process plugin path — every worker is its own JVM.)
 */
@Tag("integration")
class PluginLoaderTest {

    /** The java-compiler worker jar, handed to the test JVM by the run-tests step through this system property. */
    private static Path javaCompilerJar() {
        String prop = System.getProperty(PluginJar.JAVA_COMPILER.jarProperty());
        Assumptions.assumeTrue(prop != null, "jk.java.plugin.jar not set — skipping fork test");
        return Path.of(prop);
    }

    /** The JDK this test JVM runs under, as the home {@link PluginLoader#run} launches from. */
    private static Path javaHome() {
        return Path.of(System.getProperty("java.home"));
    }

    @Test
    void forks_a_worker_and_a_bad_spec_exits_nonzero() throws Exception {
        Path jar = javaCompilerJar();
        List<String> protocol = new ArrayList<>();
        List<String> passthrough = new ArrayList<>();
        // The forked worker loads the java-compiler plugin and is handed a nonexistent spec file —
        // it must exit non-zero having reached the plugin, never on a class its classpath lacks.
        int code = PluginLoader.run(
                        javaHome(),
                        WorkerLaunchClasspath.resolve(jar),
                        List.of(),
                        "##JKJC:",
                        List.of("@/nonexistent/spec.txt"),
                        protocol::add,
                        passthrough::add)
                .code();
        assertThat(code).isNotEqualTo(0);
        assertThat(String.join("\n", passthrough))
                .doesNotContain("NoClassDefFoundError")
                .doesNotContain("ClassNotFoundException");
    }

    /**
     * Every worker the run-tests step hands this JVM starts from its launch classpath: PluginMain
     * runs, installs its log, finds the worker's plugins and refuses a prefix none of them answers
     * (the line reaches the host with the protocol-shaped prefix taken out).
     * A classpath short of a library PluginMain or the plugin needs dies before that line.
     */
    @Test
    void every_worker_starts_plugin_main_from_its_launch_classpath() throws Exception {
        List<String> started = new ArrayList<>();
        for (PluginJar worker : PluginJar.values()) {
            // Their plugin classes link the compiler their launch adds beside the POM closure: the
            // Kotlin Build Tools API (KotlinPluginSetup), the project's Groovy (GroovyPluginSetup).
            if (worker == PluginJar.KOTLIN_COMPILER || worker == PluginJar.GROOVY_COMPILER) continue;
            String prop = System.getProperty(worker.jarProperty());
            if (prop == null || !Files.isRegularFile(Path.of(prop))) continue;
            List<String> lines = new ArrayList<>();
            int code = PluginLoader.run(
                            javaHome(),
                            WorkerLaunchClasspath.resolve(Path.of(prop)),
                            List.of("-Djk.plugin.prefix=##JKNONE:"),
                            "##JKNONE:",
                            List.of(),
                            lines::add,
                            lines::add)
                    .code();
            String out = String.join("\n", lines);
            assertThat(out)
                    .as(worker.artifactId() + " launched from its POM closure")
                    .contains("no plugin with protocol prefix");
            assertThat(code).as(worker.artifactId()).isEqualTo(Exit.SOFTWARE);
            started.add(worker.artifactId());
        }
        Assumptions.assumeFalse(started.isEmpty(), "no worker jar handed to this JVM");
    }
}
