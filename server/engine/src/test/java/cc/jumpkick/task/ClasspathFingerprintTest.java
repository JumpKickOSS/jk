// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClasspathFingerprintTest {

    private static Path write(Path p, String content) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
        return p;
    }

    @Test
    void cas_entry_is_keyed_by_its_hash_path_not_its_bytes(@TempDir Path dir) throws IOException {
        // A CAS blob's path already encodes its content; the path is the fingerprint.
        Path cas = write(dir.resolve("cache/sha256/ab/cd/rest"), "anything");
        String fp = ClasspathFingerprint.entry(cas);
        assertThat(fp).startsWith("cas:").contains("/sha256/");
    }

    @Test
    void local_non_archive_file_is_keyed_by_raw_content(@TempDir Path dir) throws IOException {
        Path f = write(dir.resolve("target/blob.bin"), "V1");
        String v1 = ClasspathFingerprint.entry(f);
        Files.writeString(f, "V1"); // same bytes
        assertThat(ClasspathFingerprint.entry(f)).isEqualTo(v1);
        // Different size so size+mtime identity cannot reuse a memo for a same-tick rewrite.
        Files.writeString(f, "V2-longer");
        assertThat(ClasspathFingerprint.entry(f)).isNotEqualTo(v1);
    }

    @Test
    void directory_is_keyed_by_its_tree_content(@TempDir Path dir) throws IOException {
        Path classes = Files.createDirectories(dir.resolve("classes"));
        write(classes.resolve("a/A.class"), "AAAA");
        String before = ClasspathFingerprint.entry(classes);
        write(classes.resolve("a/B.class"), "BBBB"); // a new class file
        assertThat(ClasspathFingerprint.entry(classes)).isNotEqualTo(before);
    }

    @Test
    void compile_outputs_plus_resources_match_live_classes_tree(@TempDir Path dir) throws Exception {
        // Live tree after compile + copy-resources.
        Path classes = Files.createDirectories(dir.resolve("classes"));
        write(classes.resolve("a/A.class"), "AAAA");
        write(classes.resolve("app.properties"), "x=1\n");
        String live = ClasspathFingerprint.entry(classes);

        // Same tree reconstructed after clean (action record + resource roots).
        Path res = Files.createDirectories(dir.resolve("resources"));
        write(res.resolve("app.properties"), "x=1\n");
        Map<String, String> compileOut =
                Map.of("a/A.class", cc.jumpkick.util.Hashing.sha256Hex(classes.resolve("a/A.class")));
        String reconstructed = ClasspathFingerprint.entryFromCompileAndResources(compileOut, List.of(res));
        assertThat(reconstructed).isEqualTo(live);
    }

    @Test
    void stamp_files_are_excluded_from_output_digest_fingerprints(@TempDir Path dir) throws Exception {
        Path classes = Files.createDirectories(dir.resolve("classes"));
        write(classes.resolve("A.class"), "AA");
        write(classes.resolve(FreshnessStamp.JAVA_STAMP), "stamp-noise");
        String live = ClasspathFingerprint.entry(classes);
        Map<String, String> outs = new LinkedHashMap<>();
        outs.put("A.class", cc.jumpkick.util.Hashing.sha256Hex(classes.resolve("A.class")));
        outs.put(FreshnessStamp.JAVA_STAMP, "deadbeef");
        assertThat(ClasspathFingerprint.entryFromOutputDigests(outs)).isEqualTo(live);
    }

    @Test
    void jar_is_keyed_by_raw_bytes_matching_cas_and_content_change_busts(@TempDir Path dir) throws IOException {
        // Packagers emit byte-reproducible jars, so raw SHA matches the CAS digest restored
        // after clean. Fingerprint is file:<raw> — a real content change still busts it.
        Path j1 = writeJar(dir.resolve("a.jar"), new String[][] {{"A.class", "AA"}, {"B.class", "BB"}}, 1000);
        String fp1 = ClasspathFingerprint.entry(j1);
        assertThat(fp1).startsWith("file:");
        // Byte-identical rewrite (new path copy) keeps the same token value.
        Path j1b = dir.resolve("a-copy.jar");
        Files.copy(j1, j1b);
        assertThat(ClasspathFingerprint.entry(j1b)).isEqualTo(fp1);

        Path j3 = writeJar(dir.resolve("c.jar"), new String[][] {{"A.class", "AA"}, {"B.class", "CHANGED"}}, 1000);
        assertThat(ClasspathFingerprint.entry(j3))
                .as("an entry's content change → different fingerprint")
                .isNotEqualTo(fp1);
    }

    private static Path writeJar(Path jar, String[][] entries, long time) throws IOException {
        try (var zos = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (String[] e : entries) {
                var ze = new ZipEntry(e[0]);
                ze.setTime(time);
                zos.putNextEntry(ze);
                zos.write(e[1].getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return jar;
    }

    @Test
    void directory_fingerprint_ignores_freshness_stamps(@TempDir Path dir) throws IOException {
        Path classes = Files.createDirectories(dir.resolve("classes"));
        write(classes.resolve("a/A.class"), "AAAA");
        write(classes.resolve(".jstamp"), "stamp-1");
        String before = ClasspathFingerprint.entry(classes);
        // jk rewrites the stamps every build — build-host metadata, not code, so
        // the fingerprint must ignore them.
        write(classes.resolve(".jstamp"), "stamp-2-different");
        write(classes.resolve(".kstamp"), "k");
        assertThat(ClasspathFingerprint.entry(classes)).isEqualTo(before);
        // A real class change still busts it (size change avoids same-tick mtime memo).
        write(classes.resolve("a/A.class"), "BBBBBB");
        assertThat(ClasspathFingerprint.entry(classes)).isNotEqualTo(before);
    }

    @Test
    void directory_fingerprint_tracks_embed_sha_resources(@TempDir Path dir) throws IOException {
        // [build.embed-sha] writes META-INF/jk-<worker>-sha256.txt holding a
        // worker jar's SHA. Now that worker jars are byte-reproducible the value
        // is stable across no-op rebuilds, and a genuine worker change must
        // ripple in — so unlike the stamps, this resource counts.
        Path classes = Files.createDirectories(dir.resolve("classes"));
        write(classes.resolve("a/A.class"), "AAAA");
        write(classes.resolve("META-INF/jk-git-client-sha256.txt"), "sha-1");
        String before = ClasspathFingerprint.entry(classes);
        write(classes.resolve("META-INF/jk-git-client-sha256.txt"), "sha-2-worker-changed");
        assertThat(ClasspathFingerprint.entry(classes)).isNotEqualTo(before);
    }

    @Test
    void missing_entry_has_a_distinct_token(@TempDir Path dir) throws IOException {
        assertThat(ClasspathFingerprint.entry(dir.resolve("gone.jar"))).startsWith("missing:");
    }

    @Test
    void settled_jar_fingerprint_is_memoized_and_a_content_change_invalidates(@TempDir Path dir) throws Exception {
        // A jar whose mtime has settled takes the FileHashMemo stat fast-path on the
        // second read; a content change (new size/mtime) must still re-fingerprint.
        Path jar = writeJar(dir.resolve("dep.jar"), new String[][] {{"A.class", "AA"}}, 1000);
        FileTime old = FileTime.fromMillis(System.currentTimeMillis() - 60_000);
        Files.setLastModifiedTime(jar, old);
        Path cache = dir.resolve("cache");
        cc.jumpkick.config.SessionContext.where(
                cc.jumpkick.config.Session.defaults().withCacheDir(cache), () -> {
                    String fp1 = ClasspathFingerprint.entry(jar);
                    assertThat(Files.isDirectory(cache.resolve("hash-memo")))
                            .as("settled fingerprint recorded on disk")
                            .isTrue();
                    assertThat(ClasspathFingerprint.entry(jar))
                            .as("memoized read agrees with the computed fingerprint")
                            .isEqualTo(fp1);
                    writeJar(jar, new String[][] {{"A.class", "CHANGED-CONTENT"}}, 2000);
                    Files.setLastModifiedTime(jar, FileTime.fromMillis(old.toMillis() + 5_000));
                    assertThat(ClasspathFingerprint.entry(jar))
                            .as("content change re-fingerprints despite the memo")
                            .isNotEqualTo(fp1);
                    return null;
                });
    }

    @Test
    void jar_fingerprint_is_raw_content_and_cas_seed_is_trusted(@TempDir Path dir) throws Exception {
        Path jar = writeJar(dir.resolve("dep.jar"), new String[][] {{"A.class", "AA"}}, 1000);
        Path cache = dir.resolve("cache");
        cc.jumpkick.config.SessionContext.where(
                cc.jumpkick.config.Session.defaults().withCacheDir(cache), () -> {
                    try {
                        String fp = ClasspathFingerprint.entry(jar);
                        assertThat(fp).startsWith("file:");
                        // CAS restore seeds raw digest — entry must not re-hash.
                        String hex = fp.substring("file:".length());
                        FileHashMemo.clearThreadCache();
                        FileHashMemo.rememberContent(jar, hex);
                        FileHashMemo.resetStats();
                        long reads = FileHashMemo.contentReads();
                        assertThat(ClasspathFingerprint.entry(jar)).isEqualTo(fp);
                        assertThat(FileHashMemo.contentReads() - reads).isZero();
                        return null;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    @Test
    void of_is_order_independent(@TempDir Path dir) throws IOException {
        Path a = write(dir.resolve("a.jar"), "A");
        Path b = write(dir.resolve("b.jar"), "B");
        assertThat(ClasspathFingerprint.of(List.of(a, b))).isEqualTo(ClasspathFingerprint.of(List.of(b, a)));
    }
}
