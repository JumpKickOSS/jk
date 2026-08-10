// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
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
                base.activeBuildPlans() + 1,
                base.heapUsedBytes(),
                base.heapCommittedBytes(),
                base.heapMaxBytes(),
                base.rssBytes(),
                base.aotTrainingPid(),
                base.cores(),
                base.totalMemoryBytes(),
                base.availableMemoryBytes(),
                base.systemCpuLoad(),
                base.systemLoadAverage(),
                base.engineEpoch(),
                base.peakActiveRequests(),
                base.peakActiveBuildPlans());
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
        AtomicReference<CacheSnapshot> cache =
                new AtomicReference<>(new CacheSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 20L << 30, 1L << 30, 0));
        try (LiveVitals live = new LiveVitals(hub, status::get, cache::get);
                HttpEvents.Subscription sub = hub.subscribe()) {
            live.publishStatus(true);
            String first = sub.next(200);
            assertThat(first).contains("event: status");

            // Unchanged presented values → no frame
            live.publishStatus(false);
            assertThat(sub.next(50)).isNull();

            // BuildPlan count change → frame
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
                    s.availableMemoryBytes(),
                    s.systemCpuLoad(),
                    s.systemLoadAverage(),
                    s.engineEpoch(),
                    s.peakActiveRequests(),
                    s.peakActiveBuildPlans()));
            live.publishStatus(false);
            String second = sub.next(200);
            assertThat(second).contains("event: status").contains("\"activeBuildPlans\":3");
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
        CacheSnapshot c = new CacheSnapshot(10, 1000, 5, 50, 0, 0, 2, 200, 1, 30, 0, 0, 20L << 30, 1L << 30, 99);
        String json = c.toJson().toString();
        assertThat(json)
                .contains("\"actionCacheBytes\":50")
                .contains("\"artifactStorageBytes\":1200")
                .contains("\"actionMaxBytes\":")
                .contains("\"maxBytes\":");
        // Store CAS + worker jars. Run logs are state, not storage, so they are not in the total.
        assertThat(c.artifactStorageBytes()).isEqualTo(1000 + 200);
        String thin = c.toThinJson().toString();
        assertThat(thin)
                .contains("\"thin\":true")
                .contains("\"actionCacheBytes\":50")
                .contains("\"artifactStorageBytes\":1200")
                .doesNotContain("casCount");
    }

    @Test
    void mcp_only_subscription_neither_sustains_samplers_nor_receives_chrome() throws Exception {
        // JK-1512: an MCP progress stream alone must not keep the vitals samplers alive, and
        // status/cache chrome frames never land on MCP subscriptions.
        HttpEvents hub = new HttpEvents();
        AtomicReference<StatusSnapshot> status = new AtomicReference<>(snap(1024L * 1024 * 1024, 0.2));
        AtomicReference<CacheSnapshot> cache =
                new AtomicReference<>(new CacheSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 20L << 30, 1L << 30, 0));
        try (LiveVitals live = new LiveVitals(hub, status::get, cache::get);
                HttpEvents.Subscription mcp = hub.subscribe(HttpEvents.FrameStyle.MCP, null)) {
            assertThat(hub.hasSubscribers()).isTrue();
            assertThat(hub.hasDashboardSubscribers()).isFalse();

            // Non-forced publishes are gated on dashboard subscribers, not any subscriber.
            live.publishStatus(false);
            live.publishCache(false);
            assertThat(mcp.next(50)).isNull();

            // Even a forced chrome publish (connect hydrate) skips MCP subscriptions...
            live.publishStatus(true);
            live.publishCache(true);
            assertThat(mcp.next(50)).isNull();

            // ...while build events still reach them.
            hub.publish("request-start", JsonOut.object().put("requestId", 7));
            assertThat(mcp.next(500)).contains("notifications/jk/event").contains("request-start");
        }
    }

    @Test
    void chrome_frames_reach_dashboard_but_not_mcp_side_by_side() throws Exception {
        HttpEvents hub = new HttpEvents();
        AtomicReference<StatusSnapshot> status = new AtomicReference<>(snap(1024L * 1024 * 1024, 0.2));
        AtomicReference<CacheSnapshot> cache =
                new AtomicReference<>(new CacheSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 20L << 30, 1L << 30, 0));
        try (LiveVitals live = new LiveVitals(hub, status::get, cache::get);
                HttpEvents.Subscription dash = hub.subscribe();
                HttpEvents.Subscription mcp = hub.subscribe(HttpEvents.FrameStyle.MCP, null)) {
            live.publishStatus(true);
            assertThat(dash.next(500)).contains("event: status");
            assertThat(mcp.next(50)).isNull();
        }
    }

    @Test
    void hydrate_serves_last_snapshot_without_a_fresh_walk() throws Exception {
        // JK-1513: connect hydrate must not run the store walk on the connect path. With a
        // captured snapshot present, the frame arrives immediately even when a fresh capture
        // would take much longer than the read timeout.
        HttpEvents hub = new HttpEvents();
        AtomicReference<StatusSnapshot> status = new AtomicReference<>(snap(1024L * 1024 * 1024, 0.2));
        CacheSnapshot snapshot =
                new CacheSnapshot(10, 5_000_000, 2, 100_000, 0, 0, 1, 2_000_000, 0, 0, 0, 0, 20L << 30, 1L << 30, 0);
        AtomicInteger captures = new AtomicInteger();
        Supplier<CacheSnapshot> slowCapture = () -> {
            captures.incrementAndGet();
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return snapshot;
        };
        try (LiveVitals live = new LiveVitals(hub, status::get, slowCapture);
                HttpEvents.Subscription sub = hub.subscribe()) {
            // Seed the snapshot the way the sampler would (one slow capture, off-path here).
            live.publishCache(true);
            assertThat(sub.next(2_000)).contains("event: cache");

            long before = System.nanoTime();
            live.hydrateFor(sub);
            // First hydrate frame is the status; the cache frame follows from the stored snapshot.
            String status1 = sub.next(500);
            String frame = sub.next(500);
            long elapsedMillis = (System.nanoTime() - before) / 1_000_000;
            assertThat(status1).isNotNull().contains("event: status");
            assertThat(frame).isNotNull().contains("event: cache").contains("\"thin\":true");
            assertThat(elapsedMillis).isLessThan(900); // served from the stored snapshot, not a walk
        }
    }

    @Test
    void hydrate_delivers_to_the_new_subscription_only() throws Exception {
        // JK-1523: connect hydrate must not re-broadcast chrome to every open tab.
        HttpEvents hub = new HttpEvents();
        AtomicReference<StatusSnapshot> status = new AtomicReference<>(snap(1024L * 1024 * 1024, 0.2));
        AtomicReference<CacheSnapshot> cache =
                new AtomicReference<>(new CacheSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 20L << 30, 1L << 30, 0));
        try (LiveVitals live = new LiveVitals(hub, status::get, cache::get);
                HttpEvents.Subscription existing = hub.subscribe();
                HttpEvents.Subscription fresh = hub.subscribe()) {
            live.publishCache(true); // seed the snapshot (broadcast — drain both)
            assertThat(existing.next(500)).contains("event: cache");
            assertThat(fresh.next(500)).contains("event: cache");

            live.hydrateFor(fresh);
            assertThat(fresh.next(500)).contains("event: status");
            assertThat(fresh.next(500)).contains("event: cache");
            assertThat(existing.next(100)).isNull(); // no duplicate chrome on the old tab
        }
    }

    @Test
    void nudgeCache_runs_the_walk_off_the_caller_thread() throws Exception {
        HttpEvents hub = new HttpEvents();
        AtomicReference<StatusSnapshot> status = new AtomicReference<>(snap(1024L * 1024 * 1024, 0.2));
        CacheSnapshot snapshot =
                new CacheSnapshot(10, 5_000_000, 2, 100_000, 0, 0, 1, 2_000_000, 0, 0, 0, 0, 20L << 30, 1L << 30, 0);
        AtomicReference<Thread> captureThread = new AtomicReference<>();
        Supplier<CacheSnapshot> capture = () -> {
            captureThread.set(Thread.currentThread());
            return snapshot;
        };
        try (LiveVitals live = new LiveVitals(hub, status::get, capture);
                HttpEvents.Subscription sub = hub.subscribe()) {
            live.nudgeCache();
            assertThat(sub.next(2_000)).contains("event: cache");
            assertThat(captureThread.get()).isNotNull().isNotEqualTo(Thread.currentThread());
        }
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
                0.5,
                "0.11.0-test@1",
                0,
                0);
    }
}
