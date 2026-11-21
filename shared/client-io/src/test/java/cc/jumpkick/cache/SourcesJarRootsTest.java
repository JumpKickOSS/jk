// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
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

        byte[] jar = written(
                tmp,
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

        byte[] jar = written(tmp, List.of(tmp.resolve("src/main/java")));

        List<String> names = new ArrayList<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = zin.getNextEntry()) != null; ) names.add(e.getName());
        }
        assertThat(names).contains("p/A.java", "p/.jk-notes.toml").noneMatch(n -> n.startsWith(".jk-scratch"));
    }

    /** A source that will not read fails the write and leaves the jar already there, with no staging file beside it. */
    @Test
    void a_failed_write_leaves_the_jar_as_it_was(@TempDir Path tmp) throws Exception {
        Path java = Files.createDirectories(tmp.resolve("src/main/java/p"));
        Path source = Files.writeString(java.resolve("A.java"), "package p; class A {}");
        Path out = Files.createDirectories(tmp.resolve("out"));
        Path jar = Files.writeString(out.resolve("a-sources.jar"), "previous");
        assumeTrue(Files.getFileAttributeView(source, PosixFileAttributeView.class) != null, "POSIX modes");
        Files.setPosixFilePermissions(source, Set.of());
        assumeFalse(Files.isReadable(source), "a mode-000 file this user can still read");

        assertThatThrownBy(() -> SourcesJar.write(List.of(tmp.resolve("src/main/java")), jar))
                .isInstanceOf(IOException.class);

        assertThat(Files.readString(jar)).isEqualTo("previous");
        try (Stream<Path> files = Files.list(out)) {
            assertThat(files).containsExactly(jar);
        }
    }

    private static byte[] written(Path tmp, List<Path> roots) throws IOException {
        Path jar = tmp.resolve("out/a-sources.jar");
        SourcesJar.write(roots, jar);
        return Files.readAllBytes(jar);
    }
}
