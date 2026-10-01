// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.management.ThreadMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
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
        Path first = tmp.resolve("first.jar");
        Path second = tmp.resolve("second.jar");
        JavadocJar.writeTree(root, first);
        Map<String, String> entries = entries(first);
        assertThat(entries).containsKeys("META-INF/MANIFEST.MF", "index.html", "element-list", "com/example/One.html");
        assertThat(entries.get("element-list")).isEqualTo("com.example\n");
        JavadocJar.writeTree(root, second);
        assertThat(Files.readAllBytes(second)).isEqualTo(Files.readAllBytes(first));
    }

    @Test
    void a_module_with_nothing_to_document_gets_a_readme_saying_why() throws IOException {
        Path jar = tmp.resolve("readme.jar");
        JavadocJar.writeReadme("The module has no Java sources.", jar);
        Map<String, String> entries = entries(jar);
        assertThat(entries.keySet()).containsExactly("META-INF/MANIFEST.MF", JavadocJar.README);
        assertThat(entries.get(JavadocJar.README))
                .contains("intentionally empty")
                .contains("The module has no Java sources.");
    }

    /**
     * The jar is streamed to disk, so the heap a module's documentation costs does not grow with
     * the tree: 16 MiB of incompressible pages are zipped for well under that in allocation.
     */
    @Test
    void a_large_tree_is_zipped_without_holding_the_jar_in_memory() throws IOException {
        var threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assumeTrue(threads.isThreadAllocatedMemorySupported());
        Path root = Files.createDirectories(tmp.resolve("large"));
        Random random = new Random(42);
        byte[] page = new byte[256 * 1024];
        for (int i = 0; i < 64; i++) {
            random.nextBytes(page);
            Files.write(root.resolve("page-" + i + ".html"), page);
        }
        Path jar = tmp.resolve("large.jar");
        JavadocJar.writeTree(root, jar);

        long before = threads.getCurrentThreadAllocatedBytes();
        JavadocJar.writeTree(root, jar);
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;

        assertThat(Files.size(jar)).isGreaterThan(16L << 20);
        assertThat(allocated).as("bytes allocated while zipping 16 MiB").isLessThan(4L << 20);
    }

    private static Map<String, String> entries(Path jar) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(jar))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                out.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }
}
