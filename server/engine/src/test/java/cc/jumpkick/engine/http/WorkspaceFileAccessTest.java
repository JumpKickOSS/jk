// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.builds.ProjectIdentity;
import cc.jumpkick.engine.http.WorkspaceFileAccess.ReadResult;
import cc.jumpkick.testing.Symlinks;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceFileAccessTest {

    @Test
    void happy_path_lists_and_reads_allow_listed_sources(@TempDir Path root) throws Exception {
        writeJkToml(root, "demo");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/Main.java"), "package demo;\nclass Main {}\n");
        Files.writeString(root.resolve("jk.toml"), """
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
        assertThat(WorkspaceFileAccess.normalizeRel("src/Ma\0in.java")).isNull(); // NUL byte
        assertThat(WorkspaceFileAccess.read(root, "../x.java")).isInstanceOf(ReadResult.BadRequest.class);
        assertThat(WorkspaceFileAccess.read(root, "src/Ma\0in.java")).isInstanceOf(ReadResult.BadRequest.class);
        assertThat(WorkspaceFileAccess.read(root, "")).isInstanceOf(ReadResult.BadRequest.class);
    }

    @Test
    void symlinked_directory_escape_is_not_found(@TempDir Path root, @TempDir Path outside) throws Exception {
        // the real-path containment must also catch a symlinked PARENT directory —
        // src/link/Secret.java where link -> outside.
        writeJkToml(root, "demo");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(outside.resolve("Secret.java"), "class Secret {}");
        Symlinks.create(root.resolve("src/link"), outside);
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
    void hidden_unsupported_and_gradle_output_paths_are_not_servable(@TempDir Path root) throws Exception {
        writeJkToml(root, "demo");
        Files.createDirectories(root.resolve("build"));
        Files.createDirectories(root.resolve(".env-dir"));
        Files.writeString(root.resolve("build/Foo.java"), "class Foo {}");
        Files.writeString(root.resolve(".env"), "SECRET=1");
        Files.writeString(root.resolve("build.gradle"), "plugins {}");
        Files.writeString(root.resolve("notes.txt"), "nope");

        assertThat(WorkspaceFileAccess.servable(root, "build/Foo.java")).isFalse();
        assertThat(WorkspaceFileAccess.servable(root, ".env")).isFalse();
        assertThat(WorkspaceFileAccess.servable(root, "build.gradle")).isFalse();
        assertThat(WorkspaceFileAccess.servable(root, "notes.txt")).isFalse();
        assertThat(WorkspaceFileAccess.read(root, "build/Foo.java")).isInstanceOf(ReadResult.NotFound.class);
        assertThat(WorkspaceFileAccess.read(root, ".env")).isInstanceOf(ReadResult.NotFound.class);

        var list = WorkspaceFileAccess.list(root);
        assertThat(list.files())
                .extracting(WorkspaceFileAccess.ListedFile::path)
                .containsExactly("jk.toml");
    }

    @Test
    void target_allowlisted_files_are_listed_and_readable(@TempDir Path root) throws Exception {
        writeJkToml(root, "demo");
        Files.createDirectories(root.resolve("target/reports"));
        Files.writeString(root.resolve("target/report.md"), "# report\n");
        Files.writeString(root.resolve("target/reports/flow.mmd"), "graph TD; A-->B;\n");
        Files.writeString(root.resolve("target/Gen.java"), "class Gen {}");
        // Unknown extensions under target stay out of the tree (classes, binaries, …).
        Files.writeString(root.resolve("target/Foo.class"), "not-really-a-class");

        assertThat(WorkspaceFileAccess.isSkippedOutputDir(root.resolve("target")))
                .isFalse();
        assertThat(WorkspaceFileAccess.servable(root, "target/report.md")).isTrue();
        assertThat(WorkspaceFileAccess.servable(root, "target/reports/flow.mmd"))
                .isTrue();
        assertThat(WorkspaceFileAccess.servable(root, "target/Gen.java")).isTrue();
        assertThat(WorkspaceFileAccess.servable(root, "target/Foo.class")).isFalse();

        var list = WorkspaceFileAccess.list(root);
        assertThat(list.files())
                .extracting(WorkspaceFileAccess.ListedFile::path)
                .contains("jk.toml", "target/report.md", "target/reports/flow.mmd", "target/Gen.java")
                .doesNotContain("target/Foo.class");
        assertThat(WorkspaceFileAccess.read(root, "target/report.md")).isInstanceOf(ReadResult.Ok.class);
        assertThat(WorkspaceFileAccess.read(root, "target/Foo.class")).isInstanceOf(ReadResult.NotFound.class);
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
                .isFalse();
        assertThat(WorkspaceFileAccess.servable(root, "build/src/A.java")).isTrue();
        assertThat(WorkspaceFileAccess.servable(root, "build/target/A.java")).isTrue();

        var list = WorkspaceFileAccess.list(root);
        assertThat(list.files())
                .extracting(WorkspaceFileAccess.ListedFile::path)
                .contains("build/jk.toml", "build/src/A.java", "build/target/A.java");
        assertThat(WorkspaceFileAccess.read(root, "build/src/A.java")).isInstanceOf(ReadResult.Ok.class);
        assertThat(WorkspaceFileAccess.read(root, "build/target/A.java")).isInstanceOf(ReadResult.Ok.class);
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
    void preview_and_image_extensions_are_servable(@TempDir Path root) throws Exception {
        writeJkToml(root, "demo");
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs/diagram.mmd"), "graph TD; A-->B");
        Files.writeString(root.resolve("docs/g.dot"), "digraph { a -> b }");
        Files.writeString(root.resolve("docs/note.adoc"), "= Title");
        Files.writeString(root.resolve("docs/box.d2"), "a -> b");
        Files.write(root.resolve("docs/logo.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 1, 2});
        assertThat(WorkspaceFileAccess.langOf("diagram.mmd")).isEqualTo("mermaid");
        assertThat(WorkspaceFileAccess.langOf("g.dot")).isEqualTo("graphviz");
        assertThat(WorkspaceFileAccess.langOf("note.adoc")).isEqualTo("asciidoc");
        assertThat(WorkspaceFileAccess.langOf("box.d2")).isEqualTo("d2");
        assertThat(WorkspaceFileAccess.langOf("logo.png")).isEqualTo("image");
        assertThat(WorkspaceFileAccess.read(root, "docs/diagram.mmd")).isInstanceOf(ReadResult.Ok.class);
        assertThat(WorkspaceFileAccess.read(root, "docs/logo.png")).isInstanceOf(ReadResult.Binary.class);
        var raw = WorkspaceFileAccess.readRaw(root, "docs/logo.png");
        assertThat(raw).isInstanceOf(WorkspaceFileAccess.RawResult.Ok.class);
        var body = ((WorkspaceFileAccess.RawResult.Ok) raw).body();
        assertThat(body.contentType()).isEqualTo("image/png");
        assertThat(body.bytes()).hasSize(7);
    }

    @Test
    void text_config_and_script_extensions_are_listed_readable_and_writable(@TempDir Path root) throws Exception {
        writeJkToml(root, "demo");
        Files.createDirectories(root.resolve("src/main/resources"));
        Files.createDirectories(root.resolve("scripts"));
        Files.writeString(root.resolve("src/main/resources/logback.xml"), "<configuration/>\n");
        Files.writeString(root.resolve("pom.xml"), "<project/>\n");
        Files.writeString(root.resolve("config.yaml"), "a: 1\n");
        Files.writeString(root.resolve("config.yml"), "b: 2\n");
        Files.writeString(root.resolve("schema.sql"), "select 1;\n");
        Files.writeString(root.resolve("app.properties"), "k=v\n");
        Files.writeString(root.resolve("scripts/setup.sh"), "#!/bin/sh\necho hi\n");
        Files.writeString(root.resolve("scripts/run.bash"), "#!/usr/bin/env bash\n");
        Files.writeString(root.resolve("scripts/env.zsh"), "#!/usr/bin/env zsh\n");
        Files.writeString(root.resolve("src/Main.scala"), "object Main\n");
        Files.writeString(root.resolve("src/Scratch.sc"), "1 + 1\n");

        assertThat(WorkspaceFileAccess.langOf("logback.xml")).isEqualTo("xml");
        assertThat(WorkspaceFileAccess.langOf("POM.XML")).isEqualTo("xml");
        assertThat(WorkspaceFileAccess.langOf("config.yaml")).isEqualTo("yaml");
        assertThat(WorkspaceFileAccess.langOf("config.YML")).isEqualTo("yaml");
        assertThat(WorkspaceFileAccess.langOf("schema.sql")).isEqualTo("sql");
        assertThat(WorkspaceFileAccess.langOf("app.properties")).isEqualTo("properties");
        assertThat(WorkspaceFileAccess.langOf("setup.sh")).isEqualTo("shell");
        assertThat(WorkspaceFileAccess.langOf("run.bash")).isEqualTo("shell");
        assertThat(WorkspaceFileAccess.langOf("env.zsh")).isEqualTo("shell");
        assertThat(WorkspaceFileAccess.langOf("Main.scala")).isEqualTo("scala");
        assertThat(WorkspaceFileAccess.langOf("Scratch.sc")).isEqualTo("scala");

        var list = WorkspaceFileAccess.list(root);
        assertThat(list.files())
                .extracting(WorkspaceFileAccess.ListedFile::path)
                .contains(
                        "pom.xml",
                        "config.yaml",
                        "config.yml",
                        "schema.sql",
                        "app.properties",
                        "scripts/setup.sh",
                        "scripts/run.bash",
                        "scripts/env.zsh",
                        "src/Main.scala",
                        "src/Scratch.sc",
                        "src/main/resources/logback.xml");

        assertThat(WorkspaceFileAccess.read(root, "config.yaml")).isInstanceOf(ReadResult.Ok.class);
        assertThat(WorkspaceFileAccess.read(root, "schema.sql")).isInstanceOf(ReadResult.Ok.class);
        assertThat(WorkspaceFileAccess.read(root, "scripts/setup.sh")).isInstanceOf(ReadResult.Ok.class);
        assertThat(WorkspaceFileAccess.read(root, "src/Main.scala")).isInstanceOf(ReadResult.Ok.class);

        var written = WorkspaceFileAccess.write(root, "app.properties", "k=v2\n");
        assertThat(written).isInstanceOf(WorkspaceFileAccess.WriteResult.Ok.class);
        assertThat(Files.readString(root.resolve("app.properties"))).isEqualTo("k=v2\n");
    }

    @Test
    void write_replaces_text_file_atomically(@TempDir Path root) throws Exception {
        writeJkToml(root, "demo");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/Main.java"), "class Main {}\n");
        var written = WorkspaceFileAccess.write(root, "src/Main.java", "class Main { int x; }\n");
        assertThat(written).isInstanceOf(WorkspaceFileAccess.WriteResult.Ok.class);
        var ok = (WorkspaceFileAccess.WriteResult.Ok) written;
        assertThat(ok.body().path()).isEqualTo("src/Main.java");
        assertThat(ok.body().lines()).isEqualTo(1);
        assertThat(ok.body().etag()).isNotBlank();
        assertThat(Files.readString(root.resolve("src/Main.java"))).isEqualTo("class Main { int x; }\n");
    }

    @Test
    void write_with_stale_etag_is_conflict(@TempDir Path root) throws Exception {
        writeJkToml(root, "demo");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/Main.java"), "class Main {}\n");
        var read = (WorkspaceFileAccess.ReadResult.Ok) WorkspaceFileAccess.read(root, "src/Main.java");
        String etag = read.body().etag();
        Files.writeString(root.resolve("src/Main.java"), "class Main { changed; }\n");
        var conflict = WorkspaceFileAccess.write(root, "src/Main.java", "class Main { x; }\n", etag);
        assertThat(conflict).isInstanceOf(WorkspaceFileAccess.WriteResult.Conflict.class);
        var ok = WorkspaceFileAccess.write(
                root,
                "src/Main.java",
                "class Main { x; }\n",
                ((WorkspaceFileAccess.WriteResult.Conflict) conflict).currentEtag());
        assertThat(ok).isInstanceOf(WorkspaceFileAccess.WriteResult.Ok.class);
    }

    @Test
    void concurrent_same_etag_writes_yield_one_ok_and_one_conflict(@TempDir Path root) throws Exception {
        // Compare-then-write is atomic per path: without the per-path monitor, two PUTs carrying
        // the same etag could both pass the comparison and the second silently clobbered the
        // first — the exact lost update the etag exists to prevent. Repeat rounds to give the
        // race a real chance; the invariant must hold every time.
        writeJkToml(root, "demo");
        Files.createDirectories(root.resolve("src"));
        for (int round = 0; round < 50; round++) {
            Files.writeString(root.resolve("src/Main.java"), "class Main { /* v" + round + " */ }\n");
            var read = (WorkspaceFileAccess.ReadResult.Ok) WorkspaceFileAccess.read(root, "src/Main.java");
            String etag = read.body().etag();

            var results = new WorkspaceFileAccess.WriteResult[2];
            var barrier = new CyclicBarrier(2);
            Thread a = new Thread(() -> {
                await(barrier);
                results[0] = WorkspaceFileAccess.write(root, "src/Main.java", "class A {}\n", etag);
            });
            Thread b = new Thread(() -> {
                await(barrier);
                results[1] = WorkspaceFileAccess.write(root, "src/Main.java", "class B {}\n", etag);
            });
            a.start();
            b.start();
            a.join();
            b.join();

            long oks = Arrays.stream(results)
                    .filter(r -> r instanceof WorkspaceFileAccess.WriteResult.Ok)
                    .count();
            long conflicts = Arrays.stream(results)
                    .filter(r -> r instanceof WorkspaceFileAccess.WriteResult.Conflict)
                    .count();
            assertThat(oks).as("round %d: exactly one writer wins", round).isEqualTo(1);
            assertThat(conflicts)
                    .as("round %d: the loser learns it lost", round)
                    .isEqualTo(1);
            String onDisk = Files.readString(root.resolve("src/Main.java"));
            String winner = results[0] instanceof WorkspaceFileAccess.WriteResult.Ok ? "class A {}\n" : "class B {}\n";
            assertThat(onDisk)
                    .as("round %d: the winner's bytes are on disk", round)
                    .isEqualTo(winner);
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void write_rejects_images_and_traversal(@TempDir Path root) throws Exception {
        writeJkToml(root, "demo");
        Files.createDirectories(root.resolve("docs"));
        Files.write(root.resolve("docs/logo.png"), new byte[] {1, 2, 3});
        assertThat(WorkspaceFileAccess.write(root, "docs/logo.png", "nope"))
                .isInstanceOf(WorkspaceFileAccess.WriteResult.NotWritable.class);
        assertThat(WorkspaceFileAccess.write(root, "../x.java", "x"))
                .isInstanceOf(WorkspaceFileAccess.WriteResult.BadRequest.class);
        assertThat(WorkspaceFileAccess.write(root, "src/Missing.java", "x"))
                .isInstanceOf(WorkspaceFileAccess.WriteResult.NotFound.class);
    }

    @Test
    void non_utf8_files_fall_back_to_latin1_with_the_encoding_flagged(@TempDir Path root) throws Exception {
        // a Latin-1 source previously decoded with silent U+FFFD substitution and no
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
    void latin1_read_write_round_trip_preserves_bytes(@TempDir Path root) throws Exception {
        // a save under the encoding the file was read with must re-encode to the
        // original bytes, never silently transcode the file to UTF-8.
        writeJkToml(root, "demo");
        Files.createDirectories(root.resolve("src"));
        byte[] latin1 = "class Café { /* naïve — déjà vu */ }".getBytes(StandardCharsets.ISO_8859_1);
        Files.write(root.resolve("src/Latin.java"), latin1);
        var body = ((ReadResult.Ok) WorkspaceFileAccess.read(root, "src/Latin.java")).body();
        assertThat(body.encoding()).isEqualTo("iso-8859-1");
        var saved = WorkspaceFileAccess.write(root, "src/Latin.java", body.content(), body.etag(), body.encoding());
        assertThat(saved).isInstanceOf(WorkspaceFileAccess.WriteResult.Ok.class);
        assertThat(Files.readAllBytes(root.resolve("src/Latin.java"))).isEqualTo(latin1);
        // Unchanged content ⇒ unchanged etag: the round trip is byte-exact, not just lossless.
        assertThat(((WorkspaceFileAccess.WriteResult.Ok) saved).body().etag()).isEqualTo(body.etag());
    }

    @Test
    void latin1_write_rejects_unrepresentable_and_unknown_encodings(@TempDir Path root) throws Exception {
        writeJkToml(root, "demo");
        Files.createDirectories(root.resolve("src"));
        Files.write(root.resolve("src/Latin.java"), "x".getBytes(StandardCharsets.ISO_8859_1));
        var snowman = WorkspaceFileAccess.write(root, "src/Latin.java", "class ☃ {}", null, "iso-8859-1");
        assertThat(snowman).isInstanceOf(WorkspaceFileAccess.WriteResult.BadRequest.class);
        assertThat(((WorkspaceFileAccess.WriteResult.BadRequest) snowman).error())
                .contains("iso-8859-1");
        var unknown = WorkspaceFileAccess.write(root, "src/Latin.java", "x", null, "utf-16");
        assertThat(unknown).isInstanceOf(WorkspaceFileAccess.WriteResult.BadRequest.class);
        assertThat(((WorkspaceFileAccess.WriteResult.BadRequest) unknown).error())
                .contains("unsupported encoding");
        // Blank/utf-8 spellings keep the default path.
        assertThat(WorkspaceFileAccess.write(root, "src/Latin.java", "y", null, "UTF-8"))
                .isInstanceOf(WorkspaceFileAccess.WriteResult.Ok.class);
        assertThat(Files.readString(root.resolve("src/Latin.java"))).isEqualTo("y");
    }

    @Test
    void nul_probe_marks_binary(@TempDir Path root) throws Exception {
        writeJkToml(root, "demo");
        Files.createDirectories(root.resolve("src"));
        Files.write(root.resolve("src/Weird.java"), new byte[] {'c', 'l', 'a', 's', 's', 0, 'X'});
        assertThat(WorkspaceFileAccess.read(root, "src/Weird.java")).isInstanceOf(ReadResult.Binary.class);
        // The scan covers the whole buffer — a NUL far past the first 8 KiB still marks binary
        // instead of falling through to the Latin-1 mojibake path.
        byte[] late = new byte[64 * 1024];
        Arrays.fill(late, (byte) 'a');
        late[late.length - 1] = 0;
        Files.write(root.resolve("src/Late.java"), late);
        assertThat(WorkspaceFileAccess.read(root, "src/Late.java")).isInstanceOf(ReadResult.Binary.class);
    }

    @Test
    void symlink_escape_is_not_found(@TempDir Path root, @TempDir Path outside) throws Exception {
        writeJkToml(root, "demo");
        Files.createDirectories(root.resolve("src"));
        Path secret = outside.resolve("secret.java");
        Files.writeString(secret, "class Secret {}");
        Symlinks.create(root.resolve("src/Leak.java"), secret);
        assertThat(WorkspaceFileAccess.read(root, "src/Leak.java")).isInstanceOf(ReadResult.NotFound.class);
        // list/read parity: the escaping link must not be listed either …
        assertThat(WorkspaceFileAccess.list(root).files())
                .noneMatch(f -> f.path().equals("src/Leak.java"));
        // … while an in-root symlink stays listed and readable.
        Files.writeString(root.resolve("src/Real.java"), "class Real {}");
        Symlinks.create(root.resolve("src/Alias.java"), root.resolve("src/Real.java"));
        assertThat(WorkspaceFileAccess.list(root).files())
                .anyMatch(f -> f.path().equals("src/Alias.java"));
        assertThat(WorkspaceFileAccess.read(root, "src/Alias.java")).isInstanceOf(ReadResult.Ok.class);
    }

    @Test
    void symlinked_out_root_manifest_is_not_listed(@TempDir Path root, @TempDir Path outside) throws Exception {
        // The pre-seed must pay the same real-path containment as every BFS entry — an
        // escaping root jk.toml would otherwise be listed (and default-opened) only to 404.
        Files.writeString(outside.resolve("jk.toml"), "name = \"demo\"\n");
        Symlinks.create(root.resolve("jk.toml"), outside.resolve("jk.toml"));
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/Main.java"), "class Main {}");
        assertThat(WorkspaceFileAccess.list(root).files())
                .extracting(WorkspaceFileAccess.ListedFile::path)
                .containsExactly("src/Main.java");
        assertThat(WorkspaceFileAccess.read(root, "jk.toml")).isInstanceOf(ReadResult.NotFound.class);
        // An in-root symlinked manifest still pre-seeds first, exactly once.
        Files.delete(root.resolve("jk.toml"));
        Files.writeString(root.resolve("real.toml"), "name = \"demo\"\n");
        Symlinks.create(root.resolve("jk.toml"), root.resolve("real.toml"));
        var list = WorkspaceFileAccess.list(root);
        assertThat(list.files())
                .extracting(WorkspaceFileAccess.ListedFile::path)
                .containsExactly("jk.toml", "real.toml", "src/Main.java");
        assertThat(WorkspaceFileAccess.read(root, "jk.toml")).isInstanceOf(ReadResult.Ok.class);
    }

    @Test
    void depth_cap_reports_truncation(@TempDir Path root) throws Exception {
        // files below MAX_WALK_DEPTH are readable via deep link but invisible in the
        // tree — the UI must at least see the truncation hint.
        writeJkToml(root, "demo");
        StringBuilder relDir = new StringBuilder();
        Path deep = root;
        for (int i = 0; i < WorkspaceFileAccess.MAX_WALK_DEPTH + 2; i++) {
            deep = deep.resolve("d" + i);
            relDir.append(relDir.isEmpty() ? "" : "/").append("d").append(i);
        }
        Files.createDirectories(deep);
        Files.writeString(deep.resolve("Deep.java"), "class Deep {}");
        var list = WorkspaceFileAccess.list(root);
        assertThat(list.truncated()).isTrue();
        assertThat(list.files())
                .extracting(WorkspaceFileAccess.ListedFile::path)
                .doesNotContain(relDir + "/Deep.java");
        assertThat(WorkspaceFileAccess.read(root, relDir + "/Deep.java")).isInstanceOf(ReadResult.Ok.class);
    }

    @Test
    void in_root_directory_symlinks_are_listed_and_cycles_terminate(@TempDir Path root) throws Exception {
        // read() serves files under an in-root dir symlink, so list must show them.
        writeJkToml(root, "demo");
        Files.createDirectories(root.resolve("real"));
        Files.writeString(root.resolve("real/A.java"), "class A {}");
        Symlinks.create(root.resolve("linkdir"), root.resolve("real"));
        // A link back to the root would cycle the BFS without the visited set.
        Symlinks.create(root.resolve("real/loop"), root);
        var list = WorkspaceFileAccess.list(root);
        // The target tree is walked exactly once, via whichever name the directory stream
        // yielded first — either spelling is a correct listing, and reads serve both.
        assertThat(list.files())
                .extracting(WorkspaceFileAccess.ListedFile::path)
                .anyMatch(p -> p.equals("real/A.java") || p.equals("linkdir/A.java"));
        assertThat(WorkspaceFileAccess.read(root, "linkdir/A.java")).isInstanceOf(ReadResult.Ok.class);
        assertThat(WorkspaceFileAccess.read(root, "real/A.java")).isInstanceOf(ReadResult.Ok.class);
    }

    @Test
    void escaping_directory_symlinks_stay_unlisted_and_unreadable(@TempDir Path root, @TempDir Path outside)
            throws Exception {
        writeJkToml(root, "demo");
        Files.writeString(outside.resolve("Secret.java"), "class Secret {}");
        Symlinks.create(root.resolve("esc"), outside);
        var list = WorkspaceFileAccess.list(root);
        assertThat(list.files())
                .extracting(WorkspaceFileAccess.ListedFile::path)
                .noneMatch(p -> p.startsWith("esc/"));
        assertThat(WorkspaceFileAccess.read(root, "esc/Secret.java")).isInstanceOf(ReadResult.NotFound.class);
    }

    @Test
    void list_caps_and_stays_sorted(@TempDir Path root) throws Exception {
        writeJkToml(root, "demo");
        Files.createDirectories(root.resolve("src"));
        int n = WorkspaceFileAccess.MAX_LIST_FILES + 10;
        for (int i = 0; i < n; i++) {
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
        // Truncation must keep the workspace-root jk.toml even when cap+ files sort before it.
        writeJkToml(root, "demo");
        Files.createDirectories(root.resolve("aaa"));
        int n = WorkspaceFileAccess.MAX_LIST_FILES + 100;
        for (int i = 0; i < n; i++) {
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
        int n = WorkspaceFileAccess.MAX_LIST_FILES + 100;
        for (int i = 0; i < n; i++) {
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
    void target_output_never_crowds_sources_out_of_the_cap(@TempDir Path root) throws Exception {
        // target/ output fills only the capacity hand-written sources leave over, so a
        // report-heavy workspace lists every src/ file even past the cap.
        writeJkToml(root, "demo");
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve("target"));
        int n = WorkspaceFileAccess.MAX_LIST_FILES + 50;
        for (int i = 0; i < n; i++) {
            Files.writeString(root.resolve(String.format("target/r-%04d.xml", i)), "<r/>");
        }
        for (int i = 0; i < 40; i++) {
            Files.writeString(root.resolve(String.format("src/Z%02d.java", i)), "class Z {}");
        }
        var list = WorkspaceFileAccess.list(root);
        assertThat(list.truncated()).isTrue();
        assertThat(list.files()).hasSize(WorkspaceFileAccess.MAX_LIST_FILES);
        assertThat(list.files().stream()
                        .filter(f -> f.path().startsWith("src/"))
                        .count())
                .isEqualTo(40);
        assertThat(list.files()).anyMatch(f -> f.path().equals("jk.toml"));
        assertThat(list.files()).anyMatch(f -> f.path().startsWith("target/"));
    }

    /**
     * The sandbox root is one of the checkouts the id records: implied while there is one, named
     * by {@code dir} once a second worktree builds under the same id, and never a tree the id does
     * not list.
     */
    @Test
    void resolve_root_is_one_of_the_ids_live_checkouts(@TempDir Path tmp, @TempDir Path buildsDir) throws Exception {
        System.setProperty("jk.env.JK_STATE_DIR", buildsDir.toString());
        try {
            String id = "aabbccddeeff00112233445566778899";
            Path a = tmp.resolve("wt-a").toAbsolutePath().normalize();
            Path b = tmp.resolve("wt-b").toAbsolutePath().normalize();
            writeJkToml(a, "demo");
            writeJkToml(b, "demo");
            Path home = ProjectBuilds.projectHome(id);
            ProjectIdentity.IdentityFile.write(home, identityAt(id, a));
            assertThat(WorkspaceFileAccess.resolveRoot(id, null)).isEqualTo(new WorkspaceFileAccess.Root.Ok(a));

            ProjectIdentity.IdentityFile.write(home, identityAt(id, b));
            assertThat(WorkspaceFileAccess.resolveRoot(id, null))
                    .isEqualTo(new WorkspaceFileAccess.Root.Ambiguous(List.of(a, b)));
            assertThat(WorkspaceFileAccess.resolveRoot(id, b.toString())).isEqualTo(new WorkspaceFileAccess.Root.Ok(b));
            assertThat(WorkspaceFileAccess.resolveRoot(
                            id, tmp.resolve("wt-b/../wt-a").toString()))
                    .as("a selector is compared as a path, not a string")
                    .isEqualTo(new WorkspaceFileAccess.Root.Ok(a));
            Path elsewhere = Files.createDirectories(tmp.resolve("elsewhere"));
            writeJkToml(elsewhere, "demo");
            assertThat(WorkspaceFileAccess.resolveRoot(id, elsewhere.toString()))
                    .as("a jk.toml elsewhere does not widen the sandbox")
                    .isEqualTo(new WorkspaceFileAccess.Root.NotACheckout(elsewhere.toString(), List.of(a, b)));

            // The deleted worktree stops counting without anyone rewriting the file.
            Files.delete(a.resolve("jk.toml"));
            Files.delete(a);
            assertThat(WorkspaceFileAccess.resolveRoot(id, null)).isEqualTo(new WorkspaceFileAccess.Root.Ok(b));

            assertThat(WorkspaceFileAccess.resolveRoot("zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz", null))
                    .isEqualTo(new WorkspaceFileAccess.Root.Unknown());
            assertThat(WorkspaceFileAccess.resolveRoot("", null)).isEqualTo(new WorkspaceFileAccess.Root.Unknown());
        } finally {
            System.clearProperty("jk.env.JK_STATE_DIR");
        }
    }

    private static ProjectIdentity identityAt(String id, Path checkout) {
        return new ProjectIdentity(id, "g:demo", checkout, ProjectIdentity.Source.LOCK, null, null);
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
                group = "g"
                name = "%s"
                version = "1"
                """.formatted(name));
    }
}
