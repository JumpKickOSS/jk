// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ClasspathFingerprint;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The parts of the packaging forecast that only running production can settle: whether a
 * fingerprint the forecast computes is spelled the same way the live build spelled it, and whether
 * an action record can actually restore.
 *
 * <p>Which <em>facts</em> the two sides hash is not asserted here and must not be — the tests that
 * tried asserted two hand-typed {@code List.of(...)} literals in this file against each other,
 * never reading a token out of {@code PlannerPackage}, {@code PlannerTails} or
 * {@link TaskForecaster}, and stayed green through six live drifts. That is the
 * {@code checkForecastKeyParity} guard in {@code server/engine/build.gradle.kts}, which
 * reads the real token bags.
 */
class TaskForecasterPackageKeyTest {

    @Test
    void post_clean_sibling_fingerprints_in_the_live_file_form(@TempDir Path tmp) throws Exception {
        // a jk-clean-wiped sibling recovered from the CAS must fingerprint as
        // "file:<sha>" (what the live step stored for the on-disk jar), not "cas:<blob path>" —
        // otherwise the post-clean assembly forecast can never key-match.
        // the pinned sha names a payload blob in the CACHE-tier pool the action records
        // write to — recovery must consult the action cache's own CAS, not the artifact store.
        byte[] bytes = "sibling-jar-bytes".getBytes(StandardCharsets.UTF_8);
        String sha = Hashing.sha256Hex(bytes);
        Path cacheRoot = tmp.resolve("cache");
        var actionCache = new ActionCache(JkStores.cacheCas(cacheRoot), cacheRoot.resolve("actions"));
        actionCache.cas().put(bytes, sha);

        Path wiped = tmp.resolve("target/sibling.jar"); // does not exist (post-clean)
        String recovered = PackagingKeys.fingerprintJarOrCached(
                wiped, actionCache, Map.of(wiped.toAbsolutePath().normalize(), sha));

        // Live-build form: the same content on disk.
        Path onDisk = tmp.resolve("sibling.jar");
        Files.write(onDisk, bytes);
        assertThat(recovered).isEqualTo(ClasspathFingerprint.entry(onDisk));
        assertThat(recovered).startsWith("file:");
    }

    @Test
    void present_requires_payload_blobs_not_just_the_record(@TempDir Path tmp) throws Exception {
        // A cache-CAS payload can go missing while its record lives on (promotion into the store
        // CAS, a hand-deleted blob). A record whose blobs are gone cannot restore, so the forecast
        // must report RUN, not CACHED.
        Path cacheRoot = tmp.resolve("cache");
        var ac = new ActionCache(JkStores.cacheCas(cacheRoot), cacheRoot.resolve("actions"));
        byte[] bytes = "payload".getBytes(StandardCharsets.UTF_8);
        String sha = Hashing.sha256Hex(bytes);
        Path blob = ac.cas().put(bytes, sha);
        ac.storeWithOutputs("task@x", "key-1", Map.of(), Map.of("lib.jar", sha), Map.of());

        assertThat(TaskForecaster.present(ac, "key-1")).isTrue();
        Files.delete(blob); // the payload is gone; the record is not
        assertThat(TaskForecaster.present(ac, "key-1")).isFalse();
        assertThat(TaskForecaster.present(ac, "no-such-key")).isFalse();
    }

    @Test
    void estimate_eta_is_zero_when_plan_is_fully_cached(@TempDir Path tmp) {
        // Empty plan modules → 0; fully-cached modules skipped in estimateEtaMillis.
        ExplainPlan empty = new ExplainPlan(List.of(), Map.of(), 1, List.of());
        long eta = BuildService.estimateEtaMillis(
                empty, tmp, tmp.resolve("cache"), 1, null, null, false, false, true, false);
        assertThat(eta).isZero();
    }

    @Test
    void forecast_dep_fingerprint_is_spelled_the_way_the_build_spells_it(@TempDir Path tmp) throws Exception {
        // Both assembly sites emit a "deps:" token, and the guard checks that they both do. What it
        // cannot check is that the two sides compute the same string for it: the build calls
        // ClasspathFingerprint.of, the forecast calls fingerprintDepJars because it also has to
        // recover jars a `jk clean` wiped. When those two disagree the key never matches and explain
        // shows a permanent "repackage".
        Path a = Files.writeString(tmp.resolve("a.jar"), "a-bytes");
        Path b = Files.writeString(tmp.resolve("b.jar"), "b-bytes");
        List<Path> depJars = List.of(b, a); // declaration order, not sorted: both sides must sort

        assertThat(PackagingKeys.fingerprintDepJars(depJars, null, Map.of()))
                .isEqualTo(ClasspathFingerprint.of(depJars));
    }
}
