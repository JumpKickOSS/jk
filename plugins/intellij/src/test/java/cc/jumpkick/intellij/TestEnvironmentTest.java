// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.intellij;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.plugin.testing.FakeBuildIo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The test JVM of an {@code [intellij]} module: the distribution's launcher flags, a sandbox
 * relative to the module, and the jars of every bundled plugin the named ones need.
 */
class TestEnvironmentTest {

    @TempDir
    Path tmp;

    @Test
    void the_step_writes_the_flags_and_the_required_plugins_jars() throws Exception {
        Path ide = new FakeIde(tmp.resolve("ide"))
                .plugin("com.intellij.java", "java", """
                        <idea-plugin><dependencies><plugin id="intellij.todo.plugin"/></dependencies></idea-plugin>
                        """)
                .plugin("intellij.todo.plugin", "todo", "<idea-plugin/>")
                .module("intellij.todo.impl", "moduleV2", "plugins/todo/lib/modules/todo-impl.jar", "<idea-plugin/>")
                .write();
        FakeBuildIo io = new FakeBuildIo(tmp.resolve("io"), "intellij")
                .extra(IntellijPlugin.IDE, ide)
                .config("version", "262.10968.63")
                .config("plugins", List.of("java"));

        TestEnvironment.run(io);

        Path out = io.scratch().resolve(IntellijPlugin.TEST_ENV_DIR);
        assertThat(Files.readAllLines(out.resolve("jvm.args")))
                .contains(
                        "-Djava.system.class.loader=com.intellij.util.lang.PathClassLoader",
                        "-Didea.system.path=target/intellij-sandbox/262.10968.63/system-test",
                        "-Didea.force.use.core.classloader=true",
                        "-Djava.awt.headless=true");
        Path todo = ide.resolve("plugins/todo/lib").toAbsolutePath().normalize();
        assertThat(Files.readAllLines(out.resolve("classpath")))
                .as("the named plugin is on the classpath already; what it needs joins it")
                .containsExactly(
                        todo.resolve("modules/todo-impl.jar").toString(),
                        todo.resolve("todo.jar").toString());
    }

    @Test
    void a_plugin_the_distribution_does_not_bundle_is_named(@TempDir Path ideDir) throws IOException {
        Path ide = new FakeIde(ideDir).write();
        FakeBuildIo io = new FakeBuildIo(tmp.resolve("io"), "intellij")
                .extra(IntellijPlugin.IDE, ide)
                .config("version", "262.10968.63")
                .config("plugins", List.of("gradle"));

        assertThatThrownBy(() -> TestEnvironment.run(io))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("[intellij] plugins names `gradle`");
    }
}
