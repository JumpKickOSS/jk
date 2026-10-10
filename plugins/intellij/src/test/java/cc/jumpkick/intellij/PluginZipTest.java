// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.intellij;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.plugin.testing.FakeBuildIo;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The installable zip: {@code <name>/lib/} holds the module's jar, plugin.xml patched, and its runtime jars. */
class PluginZipTest {

    @TempDir
    Path tmp;

    private FakeBuildIo io() throws IOException {
        FakeBuildIo io = new FakeBuildIo(tmp, "intellij")
                .project("cc.example", "example-plugin", "1.4.0", null)
                .config("version", "262.10968.63")
                .artifact("example-plugin-1.4.0.zip");
        FakeBuildIo.write(io.classesDir().resolve("cc/example/Action.class"), "classfile");
        FakeBuildIo.write(io.classesDir().resolve(".jstamp"), "stamp");
        return io;
    }

    @Test
    void the_zip_holds_the_patched_plugin_jar_and_the_runtime_jars() throws Exception {
        FakeBuildIo io = io();
        FakeBuildIo.write(io.classesDir().resolve(PluginZip.PLUGIN_XML), """
                <idea-plugin>
                  <id>cc.example</id>
                  <name>Example</name>
                </idea-plugin>
                """);
        io.entry("gson-2.13.2.jar", "com.google.code.gson", "gson", "2.13.2");

        PluginZip.produce(io);

        try (ZipFile zip = new ZipFile(io.artifactPath().toFile())) {
            assertThat(names(zip))
                    .containsExactly(
                            "example-plugin/lib/example-plugin-1.4.0.jar", "example-plugin/lib/gson-2.13.2.jar");
            try (ZipInputStream jar = new ZipInputStream(
                    zip.getInputStream(zip.getEntry("example-plugin/lib/example-plugin-1.4.0.jar")))) {
                List<String> entries = new ArrayList<>();
                String pluginXml = null;
                for (ZipEntry e = jar.getNextEntry(); e != null; e = jar.getNextEntry()) {
                    entries.add(e.getName());
                    if (e.getName().equals(PluginZip.PLUGIN_XML)) {
                        pluginXml = new String(jar.readAllBytes(), StandardCharsets.UTF_8);
                    }
                }
                assertThat(entries)
                        .as("no build stamp ships")
                        .containsExactly(PluginZip.PLUGIN_XML, "cc/example/Action.class");
                assertThat(pluginXml).contains("<version>1.4.0</version>", "<idea-version since-build=\"262\"/>");
            }
        }
        assertThat(io.classesDir().resolve(PluginZip.PLUGIN_XML))
                .as("the classes tree keeps the source descriptor")
                .content()
                .doesNotContain("<version>");
    }

    @Test
    void a_module_without_a_descriptor_or_a_build_number_is_refused() throws Exception {
        FakeBuildIo io = io();
        assertThatThrownBy(() -> PluginZip.produce(io))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("src/main/resources/META-INF/plugin.xml");

        FakeBuildIo.write(io.classesDir().resolve(PluginZip.PLUGIN_XML), "<idea-plugin>\n</idea-plugin>\n");
        io.config("version", "2026.2.3");
        assertThatThrownBy(() -> PluginZip.produce(io))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version = \"262.10968.63\"");

        io.config("since-build", "261");
        PluginZip.produce(io);
        assertThat(Files.exists(io.artifactPath())).isTrue();
    }

    private static List<String> names(ZipFile zip) {
        List<String> out = new ArrayList<>();
        for (ZipEntry e : Collections.list(zip.entries())) out.add(e.getName());
        return out;
    }

    @SuppressWarnings("unused")
    private static String text(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
}
