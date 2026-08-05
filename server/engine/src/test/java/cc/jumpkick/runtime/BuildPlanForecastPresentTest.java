// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.task.ActionCache;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JK-1529 extended {@link BuildPlanForecast#present} to verify CAS payloads — but run-tests
 * green markers store scalar counts, not digests. Those must still count as present.
 */
class BuildPlanForecastPresentTest {

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
        assertThat(BuildPlanForecast.present(ac, key)).isTrue();
    }

    @Test
    void package_record_with_missing_payload_is_absent(@TempDir Path tmp) throws Exception {
        Path cache = tmp.resolve("cache");
        Files.createDirectories(cache);
        Cas cas = new Cas(cache.resolve("cas"));
        ActionCache ac = new ActionCache(cas, cache.resolve("actions"));
        String key = "pkg-key-" + "b".repeat(56);
        // Store a real package-like record, then delete its CAS blob (LRU eviction).
        Path jar = tmp.resolve("out/app.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "payload-bytes");
        String sha = cc.jumpkick.util.Hashing.sha256Hex(Files.readAllBytes(jar));
        cas.put(Files.readAllBytes(jar), sha);
        ac.storeWithOutputs("package-jar@x", key, Map.of(), Map.of("app.jar", sha));
        assertThat(BuildPlanForecast.present(ac, key)).isTrue();
        Files.delete(cas.pathFor(sha));
        assertThat(BuildPlanForecast.present(ac, key)).isFalse();
    }

    @Test
    void missing_record_is_absent(@TempDir Path tmp) throws Exception {
        Path cache = tmp.resolve("cache");
        Files.createDirectories(cache);
        ActionCache ac = new ActionCache(new Cas(cache.resolve("cas")), cache.resolve("actions"));
        assertThat(BuildPlanForecast.present(ac, "no-such-key")).isFalse();
    }

    @Test
    void isSha256Hex_shape() {
        assertThat(BuildPlanForecast.isSha256Hex("0")).isFalse();
        assertThat(BuildPlanForecast.isSha256Hex("a".repeat(64))).isTrue();
        assertThat(BuildPlanForecast.isSha256Hex("A".repeat(64))).isTrue();
        assertThat(BuildPlanForecast.isSha256Hex("g".repeat(64))).isFalse();
    }
}
