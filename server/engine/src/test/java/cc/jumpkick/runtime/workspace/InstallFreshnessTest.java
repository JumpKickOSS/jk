// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A jar an install would ship as it is holds the classes compiled in its module's tree (JK-MAG71). */
class InstallFreshnessTest {

    private static JkBuild module() {
        return JkBuildParser.parse("""
                group   = "demo"
                name    = "lib"
                version = "1.0.0"
                java    = 25
                """);
    }

    private static void jar(Path jar, Map<String, byte[]> entries) throws Exception {
        Files.createDirectories(jar.getParent());
        try (OutputStream out = Files.newOutputStream(jar);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (var e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
    }

    private static void classFile(Path classes, String name, byte[] bytes) throws Exception {
        Path file = classes.resolve(name);
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    @Test
    void a_jar_packaged_from_its_classes_is_installed_as_it_is(@TempDir Path dir) throws Exception {
        BuildLayout layout = BuildLayout.of(dir, module());
        classFile(layout.classesDir(), "demo/Lib.class", new byte[] {1, 2, 3});
        jar(layout.mainJar(), Map.of("demo/Lib.class", new byte[] {1, 2, 3}, "app.properties", new byte[] {9}));

        assertThat(InstallFreshness.stale(dir, module())).isNull();
    }

    @Test
    void a_jar_holding_other_bytes_of_a_class_or_lacking_one_is_rebuilt(@TempDir Path dir) throws Exception {
        BuildLayout layout = BuildLayout.of(dir, module());
        classFile(layout.classesDir(), "demo/Lib.class", new byte[] {1, 2, 3, 4});
        classFile(layout.classesDir(), "demo/New.class", new byte[] {5});
        jar(layout.mainJar(), Map.of("demo/Lib.class", new byte[] {1, 2, 3}));

        assertThat(InstallFreshness.differing(layout.mainJar(), layout.classesDir()))
                .containsExactly("demo/Lib.class", "demo/New.class");
        assertThat(InstallFreshness.stale(dir, module()))
                .contains(layout.mainJar().getFileName().toString())
                .contains("2 classes differ")
                .contains("rebuilding it");
    }

    @Test
    void a_module_with_no_jar_or_classes_yet_has_nothing_to_compare(@TempDir Path dir) throws Exception {
        assertThat(InstallFreshness.stale(dir, module())).isNull();
        BuildLayout layout = BuildLayout.of(dir, module());
        classFile(layout.classesDir(), "demo/Lib.class", new byte[] {1});
        assertThat(InstallFreshness.stale(dir, module())).as("no jar yet").isNull();
    }
}
