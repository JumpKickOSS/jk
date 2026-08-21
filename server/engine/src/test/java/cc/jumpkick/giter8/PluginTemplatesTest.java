// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.giter8;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.manifest.PluginDescriptors;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginTemplatesTest {

    @Test
    void materialize_java_hello_from_jar(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("plug.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("jk-plugin.toml"));
            out.write("""
                    [plugin]
                    id = "demo-plug"
                    table = "demo-plug"
                    version = "1"
                    """.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            put(out, "templates/java/demo-plug/hello.g8/.jk-template.toml", """
                    language = "java"
                    framework = "demo-plug"
                    name = "hello"
                    description = "Demo hello"
                    layouts = ["traditional"]
                    """);
            put(out, "templates/java/demo-plug/hello.g8/default.properties", "name=x\npackage=com.x\n");
            put(out, "templates/java/demo-plug/hello.g8/src/main/g8/jk.toml", "name = \"$name$\"\n");
            put(out, "templates/kotlin/demo-plug/hello.g8/.jk-template.toml", """
                    language = "kotlin"
                    framework = "demo-plug"
                    name = "hello"
                    description = "Demo hello"
                    layouts = ["traditional"]
                    """);
            put(out, "templates/kotlin/demo-plug/hello.g8/default.properties", "name=k\n");
        }
        var d = PluginDescriptors.parse("""
                [plugin]
                id = "demo-plug"
                table = "demo-plug"
                version = "1"
                """, "demo-plug.toml");
        PluginTableRegistry.putBuiltIn(d, jar);
        var listed = PluginTemplates.list();
        assertThat(listed.stream().map(TemplateSpec::id)).contains("java/demo-plug/hello", "kotlin/demo-plug/hello");
        Path extracted = PluginTemplates.materialize("demo-plug", "java", "demo-plug", "hello");
        Path dest = dir.resolve("out");
        Giter8Apply.apply(extracted, dest, Map.of("name", "widget"));
        assertThat(dest.resolve("jk.toml")).content().contains("name = \"widget\"");
    }

    @Test
    void lists_templates_not_kinds(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("plug.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("jk-plugin.toml"));
            out.write("""
                    [plugin]
                    id = "kind-plug"
                    table = "kind-plug"
                    version = "1"
                    """.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            put(out, "templates/java/kind-plug/hello.g8/.jk-template.toml", meta("java", "kind-plug", "hello"));
            put(out, "templates/java/kind-plug/hello.g8/default.properties", "name=d\n");
            put(out, "templates/java/kind-plug/webmvc.g8/.jk-template.toml", meta("java", "kind-plug", "webmvc"));
            put(out, "templates/java/kind-plug/webmvc.g8/default.properties", "name=w\n");
            put(out, "templates/kotlin/kind-plug/webmvc.g8/.jk-template.toml", meta("kotlin", "kind-plug", "webmvc"));
            put(out, "templates/kotlin/kind-plug/webmvc.g8/default.properties", "name=k\n");
        }
        var d = PluginDescriptors.parse("""
                [plugin]
                id = "kind-plug"
                table = "kind-plug"
                version = "1"
                """, "kind-plug.toml");
        PluginTableRegistry.putBuiltIn(d, jar);
        var picker = Giter8TemplateIndex.picker(List.of());
        assertThat(picker.stream().map(TemplateSpec::id))
                .contains("java/kind-plug/hello", "java/kind-plug/webmvc", "kotlin/kind-plug/webmvc");
        assertThat(picker.stream()
                        .filter(s -> s.id().equals("java/kind-plug/webmvc"))
                        .findFirst())
                .get()
                .extracting(TemplateSpec::source)
                .isEqualTo(TemplateSpec.SOURCE_PLUGIN);
    }

    private static String meta(String lang, String fw, String name) {
        return """
                language = "%s"
                framework = "%s"
                name = "%s"
                description = "%s"
                layouts = ["traditional"]
                """.formatted(lang, fw, name, name);
    }

    private static void put(JarOutputStream out, String name, String body) throws Exception {
        out.putNextEntry(new JarEntry(name));
        out.write(body.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }
}
