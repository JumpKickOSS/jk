// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.shrink;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The post-shrink gate. A class reached only by name leaves no link error when it disappears —
 * the loader skips it and the application runs incomplete — so the build has to be where this
 * is caught.
 */
class ShrunkJarAuditTest {

    @Test
    void a_marker_indexed_class_that_r8_dropped_fails_the_build(@TempDir Path dir) throws Exception {
        Path input = jar(
                dir.resolve("in.jar"),
                Map.of(
                        "META-INF/micronaut/com.acme.Spi/com.acme.$Bean$Definition", "",
                        "com/acme/$Bean$Definition.class", "x"));
        Path output =
                jar(dir.resolve("out.jar"), Map.of("META-INF/micronaut/com.acme.Spi/com.acme.$Bean$Definition", ""));

        assertThatThrownBy(() -> ShrunkJarPackager.auditByNameIndexes(List.of(input), output))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("com.acme.$Bean$Definition")
                .hasMessageContaining("-keep class com.acme.$Bean$Definition { *; }");
    }

    @Test
    void a_service_named_class_that_r8_dropped_fails_the_build(@TempDir Path dir) throws Exception {
        Path input = jar(
                dir.resolve("in.jar"),
                Map.of(
                        "META-INF/services/org.slf4j.spi.SLF4JServiceProvider", "com.acme.Provider\n",
                        "com/acme/Provider.class", "x"));
        Path output = jar(
                dir.resolve("out.jar"),
                Map.of("META-INF/services/org.slf4j.spi.SLF4JServiceProvider", "com.acme.Provider\n"));

        assertThatThrownBy(() -> ShrunkJarPackager.auditByNameIndexes(List.of(input), output))
                .hasMessageContaining("com.acme.Provider");
    }

    @Test
    void a_kept_class_passes(@TempDir Path dir) throws Exception {
        Map<String, String> both = Map.of(
                "META-INF/services/com.acme.Spi", "com.acme.Impl\n",
                "com/acme/Impl.class", "x");

        assertThatCode(() -> ShrunkJarPackager.auditByNameIndexes(
                        List.of(jar(dir.resolve("in.jar"), both)), jar(dir.resolve("out.jar"), both)))
                .doesNotThrowAnyException();
    }

    @Test
    void a_name_the_input_never_resolved_is_not_r8s_doing(@TempDir Path dir) throws Exception {
        // An optional dependency nobody bundled: the service file names it, no jar ever carried
        // it. Absent before and after, so it is not a removal and must not fail the build.
        Path input = jar(dir.resolve("in.jar"), Map.of("META-INF/services/com.acme.Spi", "com.optional.Missing\n"));
        Path output = jar(dir.resolve("out.jar"), Map.of("META-INF/services/com.acme.Spi", "com.optional.Missing\n"));

        assertThatCode(() -> ShrunkJarPackager.auditByNameIndexes(List.of(input), output))
                .doesNotThrowAnyException();
    }

    @Test
    void the_message_caps_the_list_and_says_how_many_more(@TempDir Path dir) throws Exception {
        var inputEntries = new java.util.LinkedHashMap<String, String>();
        var serviceBody = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            serviceBody.append("com.acme.Impl").append(i).append('\n');
            inputEntries.put("com/acme/Impl" + i + ".class", "x");
        }
        inputEntries.put("META-INF/services/com.acme.Spi", serviceBody.toString());
        Path input = jar(dir.resolve("in.jar"), inputEntries);
        Path output = jar(dir.resolve("out.jar"), Map.of("META-INF/services/com.acme.Spi", serviceBody.toString()));

        assertThatThrownBy(() -> ShrunkJarPackager.auditByNameIndexes(List.of(input), output))
                .hasMessageContaining("R8 removed 25 classes")
                .hasMessageContaining("… and 5 more");
    }

    private static Path jar(Path path, Map<String, String> entries) throws IOException {
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                jos.putNextEntry(new JarEntry(e.getKey()));
                jos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                jos.closeEntry();
            }
        }
        return path;
    }
}
