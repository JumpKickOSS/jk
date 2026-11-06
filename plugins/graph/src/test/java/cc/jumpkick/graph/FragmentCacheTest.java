// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.ActionRecordText;
import cc.jumpkick.cache.GraphCacheKeys;
import cc.jumpkick.codegraph.Fragment;
import cc.jumpkick.host.ActionTree;
import cc.jumpkick.host.CacheTree;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FragmentCacheTest {

    private static final String REL = "src/main/java/demo/A.java";

    /** A fragment a graph question reads from the cache is marked used, so the prune ranks it by that read. */
    @Test
    void a_read_from_disk_refreshes_the_records_age(@TempDir Path cacheRoot) throws IOException {
        String key = stored(cacheRoot);
        Path record = record(cacheRoot, key);
        backdate(record, Duration.ofDays(2));

        assertThat(FragmentCache.at(cacheRoot).declarations(key, REL)).isNotNull();

        assertThat(ageOf(record)).isLessThan(Duration.ofMinutes(1));
    }

    /** A record marked within the refresh interval is not written again, from disk or from memory. */
    @Test
    void reads_within_the_refresh_interval_write_nothing(@TempDir Path cacheRoot) throws IOException {
        String key = stored(cacheRoot);
        Path record = record(cacheRoot, key);
        long marked = backdate(record, ActionRecordText.READ_REFRESH.dividedBy(2));

        FragmentCache cache = FragmentCache.at(cacheRoot);
        assertThat(cache.declarations(key, REL)).isNotNull();
        assertThat(Files.getLastModifiedTime(record).toMillis()).isEqualTo(marked);

        backdate(record, Duration.ofDays(2));
        cache.declarations(key, REL);
        cache.declarations(key, REL);
        assertThat(ageOf(record))
                .as("held in memory and marked by this cache lately")
                .isGreaterThan(Duration.ofDays(1));
    }

    private static String stored(Path cacheRoot) {
        String sha = "0".repeat(64);
        String key = GraphCacheKeys.declarations(REL, sha, "");
        FragmentCache.at(cacheRoot).putDeclarations(key, REL, sha, "", new Fragment(List.of(), List.of()));
        return key;
    }

    private static Path record(Path cacheRoot, String key) {
        Path record = ActionTree.KEYS.under(CacheTree.ACTIONS.under(cacheRoot)).resolve(key);
        assertThat(record).isRegularFile();
        return record;
    }

    private static long backdate(Path file, Duration age) throws IOException {
        long at = System.currentTimeMillis() - age.toMillis();
        Files.setLastModifiedTime(file, FileTime.fromMillis(at));
        return at;
    }

    private static Duration ageOf(Path file) throws IOException {
        return Duration.ofMillis(
                System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis());
    }
}
