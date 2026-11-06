// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Every root lands at the jar's top level, and a path two roots both hold is written once, from the first. */
class SourcesJarRootsTest {

    @Test
    void roots_merge_and_a_shared_path_is_taken_from_the_first(@TempDir Path tmp) throws Exception {
        Path java = Files.createDirectories(tmp.resolve("src/main/java/p"));
        Path scala = Files.createDirectories(tmp.resolve("src/main/scala/p"));
        Files.writeString(java.resolve("A.java"), "package p; class A {}");
        Files.writeString(java.resolve("package-info.java"), "package p;");
        Files.writeString(scala.resolve("B.scala"), "package p\nclass B");
        Files.writeString(scala.resolve("package-info.java"), "/** second */ package p;");

        byte[] jar = SourcesJar.build(
                List.of(tmp.resolve("src/main/java"), tmp.resolve("src/main/kotlin"), tmp.resolve("src/main/scala")));

        List<String> names = new ArrayList<>();
        String packageInfo = null;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = zin.getNextEntry()) != null; ) {
                names.add(e.getName());
                if (e.getName().equals("p/package-info.java")) packageInfo = new String(zin.readAllBytes());
            }
        }
        assertThat(names).contains("p/A.java", "p/B.scala");
        assertThat(names).filteredOn("p/package-info.java"::equals).hasSize(1);
        assertThat(packageInfo).isEqualTo("package p;");
    }

    /**
     * The jar holds what the step's key fingerprints each root by: dot-files included, a {@code
     * .jk-*} scratch directory left out.
     */
    @Test
    void dot_files_ship_and_a_scratch_directory_does_not(@TempDir Path tmp) throws Exception {
        Path java = Files.createDirectories(tmp.resolve("src/main/java/p"));
        Files.writeString(java.resolve("A.java"), "package p; class A {}");
        Files.writeString(java.resolve(".jk-notes.toml"), "n = 1");
        Path scratch = Files.createDirectories(tmp.resolve("src/main/java/.jk-scratch"));
        Files.writeString(scratch.resolve("X.java"), "class X {}");

        byte[] jar = SourcesJar.build(List.of(tmp.resolve("src/main/java")));

        List<String> names = new ArrayList<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = zin.getNextEntry()) != null; ) names.add(e.getName());
        }
        assertThat(names).contains("p/A.java", "p/.jk-notes.toml").noneMatch(n -> n.startsWith(".jk-scratch"));
    }
}
