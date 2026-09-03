// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.TestStamp;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link TaskForecaster#present} verifies CAS payloads — but run-tests
 * green markers store scalar counts, not digests. Those must still count as present.
 */
class TaskForecasterPresentTest {

    @Test
    void marker_only_run_tests_record_is_present(@TempDir Path tmp) throws Exception {
        Path cache = tmp.resolve("cache");
        Files.createDirectories(cache);
        ActionCache ac = new ActionCache(new Cas(cache.resolve("cas")), cache.resolve("actions"));
        String key = "test-stamp-key-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        ac.storeWithOutputs(
                "run-tests@deadbeef",
                key,
                Map.of(),
                Map.of(
                        "tests.total", "0",
                        "tests.succeeded", "0",
                        "tests.skipped", "0"));
        assertThat(TaskForecaster.present(ac, key)).isTrue();
    }

    /** A red run stores the same marker shape with a failed count: present, but not green. */
    @Test
    void red_run_tests_record_is_present_but_not_green(@TempDir Path tmp) throws Exception {
        Path cache = tmp.resolve("cache");
        Files.createDirectories(cache);
        ActionCache ac = new ActionCache(new Cas(cache.resolve("cas")), cache.resolve("actions"));
        String key = "test-stamp-key-" + "c".repeat(49);
        ac.storeWithOutputs("run-tests@deadbeef", key, Map.of(), TestStamp.outcome(12, 11, 0, 1));
        var record = TaskForecaster.presentRecord(ac, key);
        assertThat(record).isPresent();
        assertThat(TestStamp.green(record.get())).isFalse();
        ac.storeWithOutputs("run-tests@deadbeef", key, Map.of(), TestStamp.outcome(12, 12, 0, 0));
        assertThat(TestStamp.green(TaskForecaster.presentRecord(ac, key).orElseThrow()))
                .isTrue();
    }

    @Test
    void package_record_with_missing_payload_is_absent(@TempDir Path tmp) throws Exception {
        Path cache = tmp.resolve("cache");
        Files.createDirectories(cache);
        Cas cas = new Cas(cache.resolve("cas"));
        ActionCache ac = new ActionCache(cas, cache.resolve("actions"));
        String key = "pkg-key-" + "b".repeat(56);
        // Store a real package-like record, then delete its CAS blob: a payload can go missing
        // while its record survives (promotion into the store CAS, a hand-deleted blob).
        Path jar = tmp.resolve("out/app.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "payload-bytes");
        String sha = Hashing.sha256Hex(Files.readAllBytes(jar));
        cas.put(Files.readAllBytes(jar), sha);
        ac.storeWithOutputs("package-jar@x", key, Map.of(), Map.of("app.jar", sha));
        assertThat(TaskForecaster.present(ac, key)).isTrue();
        Files.delete(cas.pathFor(sha));
        assertThat(TaskForecaster.present(ac, key)).isFalse();
    }

    @Test
    void missing_record_is_absent(@TempDir Path tmp) throws Exception {
        Path cache = tmp.resolve("cache");
        Files.createDirectories(cache);
        ActionCache ac = new ActionCache(new Cas(cache.resolve("cas")), cache.resolve("actions"));
        assertThat(TaskForecaster.present(ac, "no-such-key")).isFalse();
    }

    @Test
    void isSha256Hex_shape() {
        assertThat(TaskForecaster.isSha256Hex("0")).isFalse();
        assertThat(TaskForecaster.isSha256Hex("a".repeat(64))).isTrue();
        assertThat(TaskForecaster.isSha256Hex("A".repeat(64))).isTrue();
        assertThat(TaskForecaster.isSha256Hex("g".repeat(64))).isFalse();
    }
}
