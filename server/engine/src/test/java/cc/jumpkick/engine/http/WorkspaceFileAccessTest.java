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
import java.util.concurrent.CyclicBarrier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceFileAccessTest {

    @Test
    void happy_path_reads_a_source(@TempDir Path root) throws Exception {
        writeJkToml(root, "demo");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/Main.java"), "package demo;\nclass Main {}\n");
        Files.writeString(root.resolve("jk.toml"), """
                group = "g"
                name = "demo"
                version = "1"
                """);

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
    void file_deleted_after_it_was_listed_is_not_found(@TempDir Path root) throws Exception {
        writeJkToml(root, "demo");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/Gone.java"), "class Gone {}");
        assertThat(WorkspaceFileAccess.read(root, "src/Gone.java")).isInstanceOf(ReadResult.Ok.class);
        Files.delete(root.resolve("src/Gone.java"));
        assertThat(WorkspaceFileAccess.read(root, "src/Gone.java")).isInstanceOf(ReadResult.NotFound.class);
    }

    @Test
    void every_file_is_servable_but_hidden_entries_node_modules_and_key_material(@TempDir Path root) throws Exception {
        writeJkToml(root, "demo");
        Files.createDirectories(root.resolve("build"));
        Files.writeString(root.resolve("build/Foo.java"), "class Foo {}");
        Files.writeString(root.resolve(".env"), "SECRET=1");
        Files.writeString(root.resolve("build.gradle"), "plugins {}");
        Files.writeString(root.resolve("notes.txt"), "plain\n");
        Files.writeString(root.resolve("Dockerfile"), "FROM scratch\n");
        Files.writeString(root.resolve("server.pem"), "-----BEGIN PRIVATE KEY-----");
        Files.writeString(root.resolve("id_ed25519"), "key");
        Files.writeString(root.resolve("id_ed25519.pub"), "ssh-ed25519 AAAA");

        assertThat(WorkspaceFileAccess.servable(root, "build/Foo.java")).isTrue();
        assertThat(WorkspaceFileAccess.servable(root, "build.gradle")).isTrue();
        assertThat(WorkspaceFileAccess.servable(root, "notes.txt")).isTrue();
        assertThat(WorkspaceFileAccess.servable(root, "Dockerfile")).isTrue();
        assertThat(WorkspaceFileAccess.servable(root, "id_ed25519.pub")).isTrue();
        assertThat(WorkspaceFileAccess.servable(root, ".env")).isFalse();
        assertThat(WorkspaceFileAccess.servable(root, "node_modules/x/index.js"))
                .isFalse();
        assertThat(WorkspaceFileAccess.servable(root, "server.pem")).isFalse();
        assertThat(WorkspaceFileAccess.servable(root, "id_ed25519")).isFalse();

        assertThat(WorkspaceFileAccess.read(root, "build/Foo.java")).isInstanceOf(ReadResult.Ok.class);
        var notes = (ReadResult.Ok) WorkspaceFileAccess.read(root, "notes.txt");
        assertThat(notes.body().lang()).isEqualTo("text");
        assertThat(((ReadResult.Ok) WorkspaceFileAccess.read(root, "Dockerfile"))
                        .body()
                        .lang())
                .isEqualTo("dockerfile");
        assertThat(WorkspaceFileAccess.read(root, ".env")).isInstanceOf(ReadResult.NotFound.class);
        assertThat(WorkspaceFileAccess.read(root, "server.pem")).isInstanceOf(ReadResult.NotFound.class);
        assertThat(WorkspaceFileAccess.readRaw(root, "id_ed25519"))
                .isInstanceOf(WorkspaceFileAccess.RawResult.NotFound.class);
        assertThat(WorkspaceFileAccess.write(root, "server.pem", "x"))
                .isInstanceOf(WorkspaceFileAccess.WriteResult.NotFound.class);
        assertThat(WorkspaceFileAccess.write(root, "notes.txt", "edited\n"))
                .isInstanceOf(WorkspaceFileAccess.WriteResult.Ok.class);
    }

    @Test
    void a_known_binary_is_refused_as_text_before_its_bytes_are_read(@TempDir Path root) throws Exception {
        writeJkToml(root, "demo");
        Files.createDirectories(root.resolve("target"));
        // Bytes that would pass the NUL scan: the extension alone says binary.
        Files.writeString(root.resolve("target/Foo.class"), "not-really-a-class");
        assertThat(WorkspaceFileAccess.langOf("Foo.class")).isEqualTo("binary");
        assertThat(WorkspaceFileAccess.read(root, "target/Foo.class")).isInstanceOf(ReadResult.Binary.class);
        assertThat(WorkspaceFileAccess.write(root, "target/Foo.class", "x"))
                .isInstanceOf(WorkspaceFileAccess.WriteResult.NotWritable.class);
        var raw = (WorkspaceFileAccess.RawResult.Ok) WorkspaceFileAccess.readRaw(root, "target/Foo.class");
        assertThat(raw.body().contentType()).isEqualTo("application/octet-stream");
    }

    @Test
    void a_language_is_named_by_role_then_extension_and_anything_else_is_text() {
        assertThat(WorkspaceFileAccess.langOf("app.ts")).isEqualTo("typescript");
        assertThat(WorkspaceFileAccess.langOf("View.TSX")).isEqualTo("typescript");
        assertThat(WorkspaceFileAccess.langOf("index.mjs")).isEqualTo("javascript");
        assertThat(WorkspaceFileAccess.langOf("site.css")).isEqualTo("css");
        assertThat(WorkspaceFileAccess.langOf("page.htm")).isEqualTo("html");
        assertThat(WorkspaceFileAccess.langOf("build.gradle")).isEqualTo("groovy");
        assertThat(WorkspaceFileAccess.langOf("Jenkinsfile")).isEqualTo("groovy");
        assertThat(WorkspaceFileAccess.langOf("transform.xslt")).isEqualTo("xml");
        assertThat(WorkspaceFileAccess.langOf("rows.csv")).isEqualTo("csv");
        assertThat(WorkspaceFileAccess.langOf("LICENSE")).isEqualTo("text");
        assertThat(WorkspaceFileAccess.langOf(".bashrc")).isEqualTo("text");
        assertThat(WorkspaceFileAccess.langOf("server.log")).isEqualTo("text");
        assertThat(WorkspaceFileAccess.langOf("lib.jar")).isEqualTo("binary");
        assertThat(WorkspaceFileAccess.isTextWritable("text")).isTrue();
        assertThat(WorkspaceFileAccess.isTextWritable("image")).isFalse();
        assertThat(WorkspaceFileAccess.isTextWritable("binary")).isFalse();
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
    void member_directory_named_build_with_jk_toml_is_source_not_output(@TempDir Path root) throws Exception {
        writeJkToml(root, "ws");
        Path member = root.resolve("build");
        Files.createDirectories(member.resolve("src"));
        writeJkToml(member, "lib");
        Files.writeString(member.resolve("src/A.java"), "class A {}");
        Files.createDirectories(member.resolve("target"));
        Files.writeString(member.resolve("target/A.java"), "class Gen {}");

        assertThat(WorkspaceFileAccess.isOutputDir(member)).isFalse();
        assertThat(WorkspaceFileAccess.isOutputDir(member.resolve("target"))).isTrue();
        assertThat(WorkspaceFileAccess.servable(root, "build/src/A.java")).isTrue();
        assertThat(WorkspaceFileAccess.servable(root, "build/target/A.java")).isTrue();

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
    void text_config_and_script_extensions_are_readable_and_writable(@TempDir Path root) throws Exception {
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
        // An in-root symlink stays readable.
        Files.writeString(root.resolve("src/Real.java"), "class Real {}");
        Symlinks.create(root.resolve("src/Alias.java"), root.resolve("src/Real.java"));
        assertThat(WorkspaceFileAccess.read(root, "src/Alias.java")).isInstanceOf(ReadResult.Ok.class);
    }

    /**
     * The sandbox root is one of the checkouts the id records: implied while there is one, named
     * by {@code dir} once a second worktree builds under the same id, and never a tree the id does
     * not list.
     */
    @Test
    void resolve_root_is_one_of_the_ids_live_checkouts(@TempDir Path tmp, @TempDir Path buildsDir) throws Exception {
        System.setProperty("jk.env.JK_STATE_DIR", buildsDir.toString());
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
                .isInstanceOfSatisfying(
                        WorkspaceFileAccess.Root.Ambiguous.class, several -> assertThat(several.checkouts())
                                .extracting(ProjectIdentity.Checkout::path)
                                .containsExactly(a, b));
        assertThat(WorkspaceFileAccess.resolveRoot(id, b.toString())).isEqualTo(new WorkspaceFileAccess.Root.Ok(b));
        assertThat(WorkspaceFileAccess.resolveRoot(
                        id, tmp.resolve("wt-b/../wt-a").toString()))
                .as("a selector is compared as a path, not a string")
                .isEqualTo(new WorkspaceFileAccess.Root.Ok(a));
        Path elsewhere = Files.createDirectories(tmp.resolve("elsewhere"));
        writeJkToml(elsewhere, "demo");
        assertThat(WorkspaceFileAccess.resolveRoot(id, elsewhere.toString()))
                .as("a jk.toml elsewhere does not widen the sandbox")
                .isInstanceOfSatisfying(WorkspaceFileAccess.Root.NotACheckout.class, wrong -> {
                    assertThat(wrong.dir()).isEqualTo(elsewhere.toString());
                    assertThat(wrong.checkouts())
                            .extracting(ProjectIdentity.Checkout::path)
                            .containsExactly(a, b);
                });

        // The deleted worktree stops counting without anyone rewriting the file.
        Files.delete(a.resolve("jk.toml"));
        Files.delete(a);
        assertThat(WorkspaceFileAccess.resolveRoot(id, null)).isEqualTo(new WorkspaceFileAccess.Root.Ok(b));

        assertThat(WorkspaceFileAccess.resolveRoot("zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz", null))
                .isEqualTo(new WorkspaceFileAccess.Root.Unknown());
        assertThat(WorkspaceFileAccess.resolveRoot("", null)).isEqualTo(new WorkspaceFileAccess.Root.Unknown());
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
