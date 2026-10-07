// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.hibernate;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the plugin tells the engine, read off the describe protocol the engine fingerprints: the
 * step exists only under {@code enhance = true}, replaces the classes dir, and is keyed by the
 * classes, the runtime classpath that carries the project's Hibernate, and the switches.
 */
class HibernatePluginDescribeTest {

    @Test
    void enhance_declares_a_classes_transform_keyed_by_classes_classpath_and_switches(@TempDir Path dir)
            throws Exception {
        String task = taskLine(dir, true).orElseThrow(() -> new AssertionError("no hibernate-enhance task"));
        assertThat(task).contains("\"transformsClasses\":\"classes\"");
        assertThat(task).contains("\"classes\"", "\"runtime-classpath\"", "\"config\"");
    }

    @Test
    void no_step_without_enhance(@TempDir Path dir) throws Exception {
        assertThat(taskLine(dir, false)).isEmpty();
    }

    @Test
    void the_switches_default_to_the_hibernate_plugins_defaults() {
        assertThat(HibernatePlugin.switches(new PluginConfig("hibernate", Map.of("enhance", true))))
                .isEqualTo(new HibernateEnhancer.Switches(true, true, false, false));
        assertThat(HibernatePlugin.switches(new PluginConfig(
                        "hibernate",
                        Map.of(
                                "lazy-initialization", false,
                                "dirty-tracking", false,
                                "association-management", true,
                                "extended-enhancement", true))))
                .isEqualTo(new HibernateEnhancer.Switches(false, false, true, true));
    }

    private static Optional<String> taskLine(Path dir, boolean enhance) throws Exception {
        Path spec = dir.resolve("describe.spec");
        List<String> lines = new ArrayList<>(List.of(
                "{\"t\":\"op\",\"op\":\"describe\",\"plugin\":\"jk-hibernate\"}",
                "{\"t\":\"config\",\"key\":\"enhance\",\"kind\":\"bool\",\"value\":" + enhance + "}",
                "{\"t\":\"project\",\"group\":\"com.example\",\"name\":\"app\",\"version\":\"1\","
                        + "\"javaRelease\":25,\"nativeDeclared\":false,\"kotlin\":false}"));
        Files.write(spec, lines);
        var buffer = new ByteArrayOutputStream();
        var writer = new ProtocolWriter(new PrintStream(buffer, true, StandardCharsets.UTF_8), "##JKHIB:");
        assertThat(new HibernatePlugin().run(List.of(spec.toString()), writer)).isZero();
        return List.of(buffer.toString(StandardCharsets.UTF_8).split("\n")).stream()
                .filter(l -> l.contains("\"t\":\"task\"") && l.contains("\"name\":\"hibernate-enhance\""))
                .findFirst();
    }
}
