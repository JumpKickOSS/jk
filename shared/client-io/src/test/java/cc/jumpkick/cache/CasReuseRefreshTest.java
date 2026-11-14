// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Hashing;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A store that finds its blob already present marks it used, so a prune that scanned before the
 * store does not delete it with an old key; and it writes that mark at most once per window, so a
 * warm build storing an unchanged tree costs a stat per blob, not a write.
 */
class CasReuseRefreshTest {

    private static final byte[] PAYLOAD = "class bytes".getBytes(StandardCharsets.UTF_8);

    @Test
    void a_store_hit_refreshes_a_blob_older_than_the_window(@TempDir Path root) throws Exception {
        Cas cas = new Cas(root);
        Path blob = cas.put(PAYLOAD);
        Path source = Files.write(root.resolve("A.class"), PAYLOAD);
        for (Runnable store : new Runnable[] {
            () -> put(cas), () -> putFile(cas, source), () -> putStream(cas),
        }) {
            backdate(blob, Duration.ofDays(60));
            store.run();
            assertThat(age(blob)).isLessThan(Duration.ofMinutes(1));
        }
    }

    @Test
    void within_the_window_a_store_hit_writes_nothing(@TempDir Path root) throws Exception {
        Cas cas = new Cas(root);
        Path blob = cas.put(PAYLOAD);
        FileTime marked = backdate(blob, Cas.REUSE_REFRESH.minusMinutes(5));

        for (int i = 0; i < 100; i++) put(cas);

        assertThat(Files.getLastModifiedTime(blob)).isEqualTo(marked);
    }

    private static FileTime backdate(Path blob, Duration age) throws Exception {
        FileTime then = FileTime.fromMillis(System.currentTimeMillis() - age.toMillis());
        Files.setLastModifiedTime(blob, then);
        return Files.getLastModifiedTime(blob);
    }

    private static Duration age(Path blob) throws Exception {
        return Duration.ofMillis(
                System.currentTimeMillis() - Files.getLastModifiedTime(blob).toMillis());
    }

    private static void put(Cas cas) {
        try {
            cas.put(PAYLOAD);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void putFile(Cas cas, Path source) {
        try {
            cas.putFile(source, Hashing.sha256Hex(PAYLOAD));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void putStream(Cas cas) {
        try {
            cas.putStream(new ByteArrayInputStream(PAYLOAD));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
