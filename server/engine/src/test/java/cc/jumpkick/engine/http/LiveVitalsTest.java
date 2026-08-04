// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Change-gate and dual-surface fingerprint for live SSE vitals (JK-1495 / JK-1497). */
class LiveVitalsTest {

    @Test
    void presentStatus_ignores_sub_mib_free_ram_jitter() {
        StatusSnapshot a = snap(5L * 1024 * 1024 * 1024 + 100, 0.41);
        StatusSnapshot b = snap(5L * 1024 * 1024 * 1024 + 900_000, 0.41);
        assertThat(LiveVitals.PresentStatus.of(a)).isEqualTo(LiveVitals.PresentStatus.of(b));
    }

    @Test
    void presentStatus_notices_pipeline_and_load_changes() {
        StatusSnapshot base = snap(5L * 1024 * 1024 * 1024, 0.10);
        StatusSnapshot moreJobs = new StatusSnapshot(
                base.version(),
                base.pid(),
                base.startedAtMillis(),
                base.activeRequests(),
                base.activePipelines() + 1,
                base.heapUsedBytes(),
                base.heapCommittedBytes(),
                base.heapMaxBytes(),
                base.rssBytes(),
                base.aotTrainingPid(),
                base.cores(),
                base.totalMemoryBytes(),
                base.freeMemoryBytes(),
                base.systemCpuLoad(),
                base.peakActiveRequests(),
                base.peakActivePipelines());
        StatusSnapshot hotter = snap(5L * 1024 * 1024 * 1024, 0.50);
        assertThat(LiveVitals.PresentStatus.of(base)).isNotEqualTo(LiveVitals.PresentStatus.of(moreJobs));
        assertThat(LiveVitals.PresentStatus.of(base)).isNotEqualTo(LiveVitals.PresentStatus.of(hotter));
    }

    @Test
    void presentCache_tracks_action_and_artifact_surfaces_separately() {
        CacheSnapshot a = new CacheSnapshot(1, 1_000_000, 2, 100_000, 0, 0, 0, 0, 0, 0, 0, 0, 20L << 30, 1L << 30, 0);
        CacheSnapshot actionGrew =
                new CacheSnapshot(1, 1_000_000, 2, 2_000_000, 0, 0, 0, 0, 0, 0, 0, 0, 20L << 30, 1L << 30, 0);
        CacheSnapshot casGrew =
                new CacheSnapshot(1, 3_000_000, 2, 100_000, 0, 0, 0, 0, 0, 0, 0, 0, 20L << 30, 1L << 30, 0);
        assertThat(LiveVitals.PresentCache.of(a)).isNotEqualTo(LiveVitals.PresentCache.of(actionGrew));
        assertThat(LiveVitals.PresentCache.of(a)).isNotEqualTo(LiveVitals.PresentCache.of(casGrew));
    }

    @Test
    void publishStatus_change_gates_without_subscribers_and_with_unchanged_present() throws Exception {
        HttpEvents hub = new HttpEvents();
        AtomicReference<StatusSnapshot> status = new AtomicReference<>(snap(1024L * 1024 * 1024, 0.2));
        AtomicReference<CacheSnapshot> cache = new AtomicReference<>(
                new CacheSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 20L << 30, 1L << 30, 0));
        try (LiveVitals live = new LiveVitals(hub, status::get, cache::get);
                HttpEvents.Subscription sub = hub.subscribe()) {
            live.publishStatus(true);
            String first = sub.next(200);
            assertThat(first).contains("event: status");

            // Unchanged presented values → no frame
            live.publishStatus(false);
            assertThat(sub.next(50)).isNull();

            // Pipeline count change → frame
            StatusSnapshot s = status.get();
            status.set(new StatusSnapshot(
                    s.version(),
                    s.pid(),
                    s.startedAtMillis(),
                    s.activeRequests(),
                    3,
                    s.heapUsedBytes(),
                    s.heapCommittedBytes(),
                    s.heapMaxBytes(),
                    s.rssBytes(),
                    s.aotTrainingPid(),
                    s.cores(),
                    s.totalMemoryBytes(),
                    s.freeMemoryBytes(),
                    s.systemCpuLoad(),
                    s.peakActiveRequests(),
                    s.peakActivePipelines()));
            live.publishStatus(false);
            String second = sub.next(200);
            assertThat(second).contains("event: status").contains("\"activePipelines\":3");
        }
    }

    @Test
    void publishCache_sends_thin_dual_surface_payload() throws Exception {
        HttpEvents hub = new HttpEvents();
        AtomicReference<StatusSnapshot> status = new AtomicReference<>(snap(1024L * 1024 * 1024, 0.2));
        AtomicReference<CacheSnapshot> cache = new AtomicReference<>(
                new CacheSnapshot(10, 5_000_000, 2, 100_000, 0, 0, 1, 2_000_000, 0, 0, 0, 0, 20L << 30, 1L << 30, 0));
        try (LiveVitals live = new LiveVitals(hub, status::get, cache::get);
                HttpEvents.Subscription sub = hub.subscribe()) {
            live.publishCache(true);
            // One SSE frame is a single multi-line string (id/event/data/blank).
            String frame = sub.next(500);
            assertThat(frame)
                    .isNotNull()
                    .contains("event: cache")
                    .contains("\"thin\":true")
                    .contains("actionCacheBytes")
                    .doesNotContain("casCount");
        }
    }

    @Test
    void cache_json_exposes_dual_surface_fields() {
        CacheSnapshot c =
                new CacheSnapshot(10, 1000, 5, 50, 0, 0, 2, 200, 1, 30, 0, 0, 20L << 30, 1L << 30, 99);
        String json = c.toJson().toString();
        assertThat(json)
                .contains("\"actionCacheBytes\":50")
                .contains("\"artifactStorageBytes\":1230")
                .contains("\"actionMaxBytes\":")
                .contains("\"maxBytes\":");
        assertThat(c.artifactStorageBytes()).isEqualTo(1000 + 200 + 30);
        String thin = c.toThinJson().toString();
        assertThat(thin)
                .contains("\"thin\":true")
                .contains("\"actionCacheBytes\":50")
                .contains("\"artifactStorageBytes\":1230")
                .doesNotContain("casCount");
    }

    private static StatusSnapshot snap(long freeBytes, double load) {
        return new StatusSnapshot(
                "0.11.0-test",
                1L,
                System.currentTimeMillis() - 60_000,
                0,
                0,
                100L * 1024 * 1024,
                200L * 1024 * 1024,
                512L * 1024 * 1024,
                250L * 1024 * 1024,
                -1L,
                8,
                16L * 1024 * 1024 * 1024,
                freeBytes,
                load,
                0,
                0);
    }
}
