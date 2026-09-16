// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavadocJarTest {

    @TempDir
    Path tmp;

    @Test
    void zips_the_javadoc_tree_relative_to_its_root_and_deterministically() throws IOException {
        Path root = Files.createDirectories(tmp.resolve("javadoc"));
        Files.writeString(root.resolve("index.html"), "<html/>");
        Files.writeString(root.resolve("element-list"), "com.example\n");
        Files.createDirectories(root.resolve("com/example"));
        Files.writeString(root.resolve("com/example/One.html"), "<html/>");
        byte[] first = JavadocJar.fromTree(root);
        Map<String, String> entries = entries(first);
        assertThat(entries).containsKeys("META-INF/MANIFEST.MF", "index.html", "element-list", "com/example/One.html");
        assertThat(entries.get("element-list")).isEqualTo("com.example\n");
        assertThat(JavadocJar.fromTree(root)).isEqualTo(first);
    }

    @Test
    void a_module_with_nothing_to_document_gets_a_readme_saying_why() throws IOException {
        Map<String, String> entries = entries(JavadocJar.readmeOnly("The module has no Java sources."));
        assertThat(entries.keySet()).containsExactly("META-INF/MANIFEST.MF", JavadocJar.README);
        assertThat(entries.get(JavadocJar.README))
                .contains("intentionally empty")
                .contains("The module has no Java sources.");
    }

    private static Map<String, String> entries(byte[] jar) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                out.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }
}
