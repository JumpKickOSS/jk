// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.builds.ProjectIdentity;
import cc.jumpkick.engine.http.WorkspaceFileAccess.ReadResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceFileAccessTest {

    @Test
    void happy_path_lists_and_reads_allow_listed_sources(@TempDir Path root) throws Exception {
        writeJkToml(root, "demo");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/Main.java"), "package demo;\nclass Main {}\n");
        Files.writeString(root.resolve("jk.toml"), """
                [project]
                group = "g"
                name = "demo"
                version = "1"
                """);

        var list = WorkspaceFileAccess.list(root);
        assertThat(list.truncated()).isFalse();
        assertThat(list.files())
                .extracting(WorkspaceFileAccess.ListedFile::path)
                .containsExactly("jk.toml", "src/Main.java");

        var read = WorkspaceFileAccess.read(root, "src/Main.java");
        assertThat(read).isInstanceOf(ReadResult.Ok.class);
        var body = ((ReadResult.Ok) read).body();
        assertThat(body.lang()).isEqualTo("java");
        assertThat(body.content()).contains("package demo;");
        assertThat(body.lines()).isEqualTo(2);
    }

    @Test
    void rejects_traversal_absolute_and_dot_segments(@TempDir Path root) {
        assertThat(WorkspaceFileAccess.normalizeRel("../secret.java")).isNull();
        assertThat(WorkspaceFileAccess.normalizeRel("/etc/passwd")).isNull();
        assertThat(WorkspaceFileAccess.normalizeRel("src/./Main.java")).isNull();
        assertThat(WorkspaceFileAccess.normalizeRel("src//Main.java")).isNull();
        assertThat(WorkspaceFileAccess.normalizeRel("src\\Main.java")).isNull();
        assertThat(WorkspaceFileAccess.normalizeRel("src/Ma\0in.java")).isNull(); // NUL byte (JK-1955)
        assertThat(WorkspaceFileAccess.read(root, "../x.java")).isInstanceOf(ReadResult.BadRequest.class);
        assertThat(WorkspaceFileAccess.read(root, "src/Ma\0in.java")).isInstanceOf(ReadResult.BadRequest.class);
        assertThat(WorkspaceFileAccess.read(root, "")).isInstanceOf(ReadResult.BadRequest.class);
    }

    @Test
    void symlinked_directory_escape_is_not_found(@TempDir Path root, @TempDir Path outside) throws Exception {
        // JK-1955: the real-path containment must also catch a symlinked PARENT directory —
        // src/link/Secret.java where link -> outside.
        writeJkToml(root, "demo");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(outside.resolve("Secret.java"), "class Secret {}");
        try {
            Files.createSymbolicLink(root.resolve("src/link"), outside);
        } catch (UnsupportedOperationException | IOException unsupported) {
            return;
        }
        assertThat(WorkspaceFileAccess.read(root, "src/link/Secret.java")).isInstanceOf(ReadResult.NotFound.class);
    }

    @Test
    void empty_file_reads_as_ok_with_no_content(@TempDir Path root) throws Exception {
        writeJkToml(root, "demo");
        Files.createDirectories(root.resolve("src"));
        Files.write(root.resolve("src/Empty.java"), new byte[0]);
        var read = WorkspaceFileAccess.read(root, "src/Empty.java");
        assertThat(read).isInstanceOf(ReadResult.Ok.class);
        var body = ((ReadResult.Ok) read).body();
        assertThat(body.content()).isEmpty();
        assertThat(body.bytes()).isZero();
        assertThat(body.lines()).isZero();
    }

    @Test
    void file_deleted_between_list_and_read_is_not_found(@TempDir Path root) throws Exception {
        writeJkToml(root, "demo");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/Gone.java"), "class Gone {}");
        assertThat(WorkspaceFileAccess.list(root).files())
                .anyMatch(f -> f.path().equals("src/Gone.java"));
        Files.delete(root.resolve("src/Gone.java"));
        assertThat(WorkspaceFileAccess.read(root, "src/Gone.java")).isInstanceOf(ReadResult.NotFound.class);
    }

    @Test
    void hidden_unsupported_and_output_paths_are_not_servable(@TempDir Path root) throws Exception {
        writeJkToml(root, "demo");
        Files.createDirectories(root.resolve("target"));
        Files.createDirectories(root.resolve(".env-dir"));
        Files.writeString(root.resolve("target/Foo.java"), "class Foo {}");
        Files.writeString(root.resolve(".env"), "SECRET=1");
        Files.writeString(root.resolve("build.gradle"), "plugins {}");
        Files.writeString(root.resolve("notes.txt"), "nope");

        assertThat(WorkspaceFileAccess.servable(root, "target/Foo.java")).isFalse();
        assertThat(WorkspaceFileAccess.servable(root, ".env")).isFalse();
        assertThat(WorkspaceFileAccess.servable(root, "build.gradle")).isFalse();
        assertThat(WorkspaceFileAccess.servable(root, "notes.txt")).isFalse();
        assertThat(WorkspaceFileAccess.read(root, "target/Foo.java")).isInstanceOf(ReadResult.NotFound.class);
        assertThat(WorkspaceFileAccess.read(root, ".env")).isInstanceOf(ReadResult.NotFound.class);

        var list = WorkspaceFileAccess.list(root);
        assertThat(list.files())
                .extracting(WorkspaceFileAccess.ListedFile::path)
                .containsExactly("jk.toml");
    }

    @Test
    void package_named_build_is_servable(@TempDir Path root) throws Exception {
        writeJkToml(root, "demo");
        Path pkg = root.resolve("src/main/java/com/example/build");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("Util.java"), "class Util {}");
        assertThat(WorkspaceFileAccess.servable(root, "src/main/java/com/example/build/Util.java"))
                .isTrue();
        assertThat(WorkspaceFileAccess.read(root, "src/main/java/com/example/build/Util.java"))
                .isInstanceOf(ReadResult.Ok.class);
    }

    @Test
    void member_directory_named_build_with_jk_toml_is_listed_and_readable(@TempDir Path root) throws Exception {
        writeJkToml(root, "ws");
        Path member = root.resolve("build");
        Files.createDirectories(member.resolve("src"));
        writeJkToml(member, "lib");
        Files.writeString(member.resolve("src/A.java"), "class A {}");
        Files.createDirectories(member.resolve("target"));
        Files.writeString(member.resolve("target/A.java"), "class Gen {}");

        assertThat(WorkspaceFileAccess.isSkippedOutputDir(member)).isFalse();
        assertThat(WorkspaceFileAccess.isSkippedOutputDir(member.resolve("target")))
                .isTrue();
        assertThat(WorkspaceFileAccess.servable(root, "build/src/A.java")).isTrue();
        assertThat(WorkspaceFileAccess.servable(root, "build/target/A.java")).isFalse();

        var list = WorkspaceFileAccess.list(root);
        assertThat(list.files())
                .extracting(WorkspaceFileAccess.ListedFile::path)
                .contains("build/jk.toml", "build/src/A.java")
                .doesNotContain("build/target/A.java");
        assertThat(WorkspaceFileAccess.read(root, "build/src/A.java")).isInstanceOf(ReadResult.Ok.class);
        assertThat(WorkspaceFileAccess.read(root, "build/target/A.java")).isInstanceOf(ReadResult.NotFound.class);
    }

    @Test
    void extension_match_is_case_insensitive(@TempDir Path root) throws Exception {
        writeJkToml(root, "demo");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/Main.JAVA"), "class Main {}");
        Files.writeString(root.resolve("src/Lib.Kt"), "class Lib");
        assertThat(WorkspaceFileAccess.langOf("Main.JAVA")).isEqualTo("java");
        assertThat(WorkspaceFileAccess.langOf("Lib.Kt")).isEqualTo("kotlin");
        assertThat(WorkspaceFileAccess.read(root, "src/Main.JAVA")).isInstanceOf(ReadResult.Ok.class);
    }

    @Test
    void oversize_file_is_too_large_without_reading_as_ok(@TempDir Path root) throws Exception {
        writeJkToml(root, "demo");
        Files.createDirectories(root.resolve("src"));
        Path big = root.resolve("src/Big.java");
        Files.write(big, new byte[WorkspaceFileAccess.MAX_FILE_BYTES + 1]);
        var read = WorkspaceFileAccess.read(root, "src/Big.java");
        assertThat(read).isInstanceOf(ReadResult.TooLarge.class);
        var too = (ReadResult.TooLarge) read;
        assertThat(too.bytes()).isEqualTo(WorkspaceFileAccess.MAX_FILE_BYTES + 1);
        assertThat(too.maxBytes()).isEqualTo(WorkspaceFileAccess.MAX_FILE_BYTES);
    }

    @Test
    void non_utf8_files_fall_back_to_latin1_with_the_encoding_flagged(@TempDir Path root) throws Exception {
        // JK-1954: a Latin-1 source previously decoded with silent U+FFFD substitution and no
        // indicator — corrupted content presented as the file's true text.
        writeJkToml(root, "demo");
        Files.createDirectories(root.resolve("src"));
        byte[] latin1 = "class Café {}".getBytes(StandardCharsets.ISO_8859_1);
        Files.write(root.resolve("src/Latin.java"), latin1);
        var read = WorkspaceFileAccess.read(root, "src/Latin.java");
        assertThat(read).isInstanceOf(ReadResult.Ok.class);
        var body = ((ReadResult.Ok) read).body();
        assertThat(body.encoding()).isEqualTo("iso-8859-1");
        assertThat(body.content()).contains("Café").doesNotContain("�");

        Files.writeString(root.resolve("src/Utf.java"), "class Café {}");
        var utf = ((ReadResult.Ok) WorkspaceFileAccess.read(root, "src/Utf.java")).body();
        assertThat(utf.encoding()).isEqualTo("utf-8");
        assertThat(utf.content()).contains("Café");
    }

    @Test
    void nul_probe_marks_binary(@TempDir Path root) throws Exception {
        writeJkToml(root, "demo");
        Files.createDirectories(root.resolve("src"));
        Files.write(root.resolve("src/Weird.java"), new byte[] {'c', 'l', 'a', 's', 's', 0, 'X'});
        assertThat(WorkspaceFileAccess.read(root, "src/Weird.java")).isInstanceOf(ReadResult.Binary.class);
    }

    @Test
    void symlink_escape_is_not_found(@TempDir Path root, @TempDir Path outside) throws Exception {
        writeJkToml(root, "demo");
        Files.createDirectories(root.resolve("src"));
        Path secret = outside.resolve("secret.java");
        Files.writeString(secret, "class Secret {}");
        try {
            Files.createSymbolicLink(root.resolve("src/Leak.java"), secret);
        } catch (UnsupportedOperationException | IOException unsupported) {
            return;
        }
        assertThat(WorkspaceFileAccess.read(root, "src/Leak.java")).isInstanceOf(ReadResult.NotFound.class);
        // list/read parity (JK-1952): the escaping link must not be listed either …
        assertThat(WorkspaceFileAccess.list(root).files())
                .noneMatch(f -> f.path().equals("src/Leak.java"));
        // … while an in-root symlink stays listed and readable.
        Files.writeString(root.resolve("src/Real.java"), "class Real {}");
        Files.createSymbolicLink(root.resolve("src/Alias.java"), root.resolve("src/Real.java"));
        assertThat(WorkspaceFileAccess.list(root).files())
                .anyMatch(f -> f.path().equals("src/Alias.java"));
        assertThat(WorkspaceFileAccess.read(root, "src/Alias.java")).isInstanceOf(ReadResult.Ok.class);
    }

    @Test
    void list_caps_and_stays_sorted(@TempDir Path root) throws Exception {
        writeJkToml(root, "demo");
        Files.createDirectories(root.resolve("src"));
        for (int i = 0; i < 2010; i++) {
            Files.writeString(root.resolve(String.format("src/f-%04d.java", i)), "class F {}");
        }
        var list = WorkspaceFileAccess.list(root);
        assertThat(list.truncated()).isTrue();
        assertThat(list.files()).hasSize(WorkspaceFileAccess.MAX_LIST_FILES);
        assertThat(list.files().getFirst().path()).isEqualTo("jk.toml");
        assertThat(list.files()).isSortedAccordingTo(Comparator.comparing(f -> f.path()));
    }

    @Test
    void truncation_never_drops_the_root_manifest(@TempDir Path root) throws Exception {
        // JK-1944: the old walk sorted lexically and kept the first 2000, so 2000+ files sorting
        // before "jk.toml" amputated the default file (empty pane) and the tail of the alphabet.
        writeJkToml(root, "demo");
        Files.createDirectories(root.resolve("aaa"));
        for (int i = 0; i < 2100; i++) {
            Files.writeString(root.resolve(String.format("aaa/f-%04d.java", i)), "class F {}");
        }
        var list = WorkspaceFileAccess.list(root);
        assertThat(list.truncated()).isTrue();
        assertThat(list.files()).hasSize(WorkspaceFileAccess.MAX_LIST_FILES);
        assertThat(list.files()).anyMatch(f -> f.path().equals("jk.toml"));
    }

    @Test
    void truncation_trims_the_deepest_leaves_first(@TempDir Path root) throws Exception {
        // Breadth-first: when the cap hits, deep subtrees are what goes missing — not an
        // arbitrary alphabetic slice spanning every depth.
        writeJkToml(root, "demo");
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve("a/b"));
        for (int i = 0; i < 2100; i++) {
            Files.writeString(root.resolve(String.format("src/f-%04d.java", i)), "class F {}");
        }
        for (int i = 0; i < 20; i++) {
            Files.writeString(root.resolve(String.format("a/b/deep-%02d.java", i)), "class D {}");
        }
        var list = WorkspaceFileAccess.list(root);
        assertThat(list.truncated()).isTrue();
        assertThat(list.files()).noneMatch(f -> f.path().startsWith("a/b/"));
        assertThat(list.files()).anyMatch(f -> f.path().equals("jk.toml"));
    }

    @Test
    void resolve_root_uses_identity_only(@TempDir Path checkout, @TempDir Path buildsDir) throws Exception {
        writeJkToml(checkout, "demo");
        System.setProperty("jk.env.JK_BUILDS_DIR", buildsDir.toString());
        try {
            var identity = ProjectIdentity.resolve(checkout);
            ProjectIdentity.IdentityFile.write(ProjectBuilds.projectHome(identity.id()), identity);
            assertThat(WorkspaceFileAccess.resolveRoot(identity.id()))
                    .contains(checkout.toAbsolutePath().normalize());
            assertThat(WorkspaceFileAccess.resolveRoot("zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"))
                    .isEmpty();
            assertThat(WorkspaceFileAccess.resolveRoot("")).isEmpty();
        } finally {
            System.clearProperty("jk.env.JK_BUILDS_DIR");
        }
    }

    @Test
    void count_lines_drops_trailing_newline_segment() {
        assertThat(WorkspaceFileAccess.countLines("")).isZero();
        assertThat(WorkspaceFileAccess.countLines("one")).isEqualTo(1);
        assertThat(WorkspaceFileAccess.countLines("a\nb")).isEqualTo(2);
        assertThat(WorkspaceFileAccess.countLines("a\nb\n")).isEqualTo(2);
    }

    private static void writeJkToml(Path dir, String name) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("jk.toml"), """
                [project]
                group = "g"
                name = "%s"
                version = "1"
                """.formatted(name));
    }
}
