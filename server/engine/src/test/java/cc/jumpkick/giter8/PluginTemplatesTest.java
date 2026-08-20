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
    void materialize_java_default_from_jar(@TempDir Path dir) throws Exception {
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
            out.putNextEntry(new JarEntry("templates/java/default/default.properties"));
            out.write("name=x\npackage=com.x\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry("templates/java/default/src/main/g8/jk.toml"));
            out.write("name = \"$name$\"\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry("templates/kotlin/default/default.properties"));
            out.write("name=k\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        var d = PluginDescriptors.parse("""
                [plugin]
                id = "demo-plug"
                table = "demo-plug"
                version = "1"
                """, "demo-plug.toml");
        PluginTableRegistry.putBuiltIn(d, jar);
        assertThat(PluginTemplates.isPluginTemplate("demo-plug")).isTrue();
        assertThat(PluginTemplates.langs("demo-plug")).containsExactly("java", "kotlin");
        assertThat(PluginTemplates.resolveLang("demo-plug", null)).contains("java");
        assertThat(PluginTemplates.resolveLang("demo-plug", "kotlin")).contains("kotlin");
        Path extracted = PluginTemplates.materialize("demo-plug", "java", "default");
        Path dest = dir.resolve("out");
        Giter8Apply.apply(extracted, dest, Map.of("name", "widget"));
        assertThat(dest.resolve("jk.toml")).content().contains("name = \"widget\"");
    }

    @Test
    void lists_kinds_per_lang_from_jar(@TempDir Path dir) throws Exception {
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
            out.putNextEntry(new JarEntry("templates/java/default/default.properties"));
            out.write("name=d\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry("templates/java/webmvc/default.properties"));
            out.write("name=w\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry("templates/kotlin/webmvc/default.properties"));
            out.write("name=k\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        var d = PluginDescriptors.parse("""
                [plugin]
                id = "kind-plug"
                table = "kind-plug"
                version = "1"
                """, "kind-plug.toml");
        PluginTableRegistry.putBuiltIn(d, jar);
        assertThat(PluginTemplates.kinds("kind-plug", "java")).containsExactly("default", "webmvc");
        assertThat(PluginTemplates.kinds("kind-plug", "kotlin")).containsExactly("webmvc");
        assertThat(PluginTemplates.installed()).anySatisfy(p -> {
            assertThat(p.id()).isEqualTo("kind-plug");
            assertThat(p.kindsByLang().get("java")).contains("webmvc");
        });
        var row = Giter8TemplateIndex.picker(List.of()).stream()
                .filter(e -> e.id().equals("kind-plug"))
                .findFirst()
                .orElseThrow();
        assertThat(row.plugin()).isTrue();
        assertThat(row.kinds().get("java")).containsExactly("default", "webmvc");
        assertThat(Giter8TemplateIndex.picker(List.of()).getFirst().plugin()).isTrue();
    }
}
