// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Hashing;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A second engine pruning the same cache root can unlink a blob between {@code restore}'s
 * presence check and the copy that reads it. That must degrade to a cache miss, not escape as a
 * build failure.
 */
class ActionCacheMissingBlobRestoreTest {

    @AfterEach
    void reset() {
        SessionContext.reset();
    }

    @Test
    void restore_reports_a_miss_when_a_blob_vanishes_mid_copy(@TempDir Path tmp) throws Exception {
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        SessionContext.install(Session.defaults().withCacheDir(cache));

        Path classes = Files.createDirectories(tmp.resolve("classes"));
        // The CAS is planted under the restore target so restore's own clear step unlinks the
        // blob after the presence check and before the copy opens it — the interleaving a
        // concurrent prune produces, reached without a second process.
        Cas cas = new Cas(classes.resolve("cas"));
        ActionCache ac = new ActionCache(cas, cache.resolve("actions"));

        Path source = tmp.resolve("A.class");
        Files.writeString(source, "class-bytes");
        String sha = Hashing.sha256Hex(source);
        cas.putFile(source, sha);
        var record = ac.storeWithOutputs("compile-main", "key", Map.of(), Map.of("A.class", sha));

        assertThat(ac.restore(record, classes))
                .as("a blob unlinked mid-restore is a cache miss, not a NoSuchFileException")
                .isFalse();
        assertThat(classes.resolve("A.class"))
                .as("a failed restore leaves no half-copied output for the fallback run")
                .doesNotExist();
    }
}
