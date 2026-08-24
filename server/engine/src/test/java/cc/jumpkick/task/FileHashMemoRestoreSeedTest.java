// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** CAS restore must seed FileHashMemo so TestStamp does not re-hash the classes tree. */
class FileHashMemoRestoreSeedTest {

    @AfterEach
    void reset() {
        FileHashMemo.reset();
        SessionContext.reset();
    }

    @Test
    void restore_seeds_memo_so_directory_fingerprint_does_not_reread(@TempDir Path tmp) throws Exception {
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        SessionContext.install(Session.defaults().withCacheDir(cache));

        Path store = Files.createDirectories(tmp.resolve("store"));
        Path actions = Files.createDirectories(cache.resolve("actions"));
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        Path a = classes.resolve("A.class");
        Files.writeString(a, "class-bytes-AAAA");
        Cas cas = new Cas(store);
        ActionCache ac = new ActionCache(cas, actions);
        String key = "k1";
        ac.store("compile-main", key, Map.of(), classes);

        // Wipe and restore (jk clean → cache hit).
        Files.delete(a);
        var rec = ac.lookup(key).orElseThrow();
        FileHashMemo.resetStats();
        FileHashMemo.reset();
        ac.restore(rec, classes);

        long readsBefore = FileHashMemo.contentReads();
        String fp1 = ClasspathFingerprint.entry(classes);
        long readsAfterFirst = FileHashMemo.contentReads();
        String fp2 = ClasspathFingerprint.entry(classes);
        long readsAfterSecond = FileHashMemo.contentReads();

        assertThat(fp1).isEqualTo(fp2);
        // Second fingerprint must not re-read file contents.
        assertThat(readsAfterSecond).isEqualTo(readsAfterFirst);
        // Seeded restore: first fingerprint should not re-read file contents either.
        assertThat(readsAfterFirst - readsBefore)
                .as("restore must seed FileHashMemo with CAS digests")
                .isZero();
    }

    @Test
    void restore_of_jar_seeds_raw_file_token_so_entry_does_not_rehash(@TempDir Path tmp) throws Exception {
        // After clean→restore, jar fingerprints must use the CAS raw digest (file:) so TestStamp
        // does not zip-walk fat worker jars. Packagers are byte-reproducible, so raw == content.
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        SessionContext.install(Session.defaults().withCacheDir(cache));

        Path store = Files.createDirectories(tmp.resolve("store"));
        Path actions = Files.createDirectories(cache.resolve("actions"));
        Path out = Files.createDirectories(tmp.resolve("out"));
        Path jar = out.resolve("mod.jar");
        try (var zos = new ZipOutputStream(Files.newOutputStream(jar))) {
            zos.putNextEntry(new ZipEntry("A.class"));
            zos.write("AAAA".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        String before = ClasspathFingerprint.entry(jar);
        assertThat(before).startsWith("file:");

        Cas cas = new Cas(store);
        ActionCache ac = new ActionCache(cas, actions);
        ac.store("package-jar", "pkg-key", Map.of("classes", "x"), out);

        Files.delete(jar);
        FileHashMemo.resetStats();
        FileHashMemo.reset();
        assertThat(ac.restoreArtifacts(ac.lookup("pkg-key").orElseThrow(), out)).isTrue();

        long readsBefore = FileHashMemo.contentReads();
        String after = ClasspathFingerprint.entry(jar);
        long readsAfter = FileHashMemo.contentReads();
        assertThat(after)
                .as("restored jar must keep the same raw file: fingerprint")
                .isEqualTo(before)
                .startsWith("file:");
        assertThat(readsAfter - readsBefore)
                .as("CAS seed must prevent re-reading the jar")
                .isZero();
    }
}
