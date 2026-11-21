// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.CacheBackend;
import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.Request;
import cc.jumpkick.config.RequestScope;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.StepScope;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.task.ToolchainPolicy.Expectation;
import cc.jumpkick.task.ToolchainPolicy.Match;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The action cache with a remote tier behind it: a record another machine stored is restorable on
 * this one before any blob is here, its restore fetches and verifies the blobs, and a tier that
 * fails or serves bad bytes leaves a local miss, warned once, never an error.
 */
class RemoteTierTest {

    @Test
    void a_record_only_the_remote_holds_is_restorable_and_restores_with_its_blobs(@TempDir Path tmp) throws Exception {
        MemoryCacheBackend remote = new MemoryCacheBackend();
        remote.seedFrom(storedElsewhere(tmp), "k1");
        ActionCache here = cache(tmp.resolve("here"));
        RemoteTier tier = new RemoteTier(remote, "linux-x86_64");

        RemoteCaches.where(tier, () -> {
            ActionCache cache = cache(tmp.resolve("here"));
            ActionCache.ActionRecord record = cache.restorable("k1").orElseThrow();
            assertThat(here.cas().contains(requireNonNull(record.outputs().get("A.class"))))
                    .as("the forecast's answer fetched nothing")
                    .isFalse();
            assertThat(cache.restore(record, tmp.resolve("out"))).isTrue();
            return null;
        });

        assertThat(tmp.resolve("out/A.class")).hasContent("class A");
        assertThat(tmp.resolve("out/b/B.class")).hasContent("class B");
        assertThat(tier.hits()).isEqualTo(1);
        assertThat(tier.bytesDown()).isEqualTo("class A".length() + "class B".length());
        assertThat(here.lookup("k1")).as("kept here for the next build").isPresent();
        assertThat(remote.lookups).hasSize(1);
        assertThat(remote.lookups.getFirst().platform()).isEqualTo("linux-x86_64");
    }

    @Test
    void without_a_tier_the_cache_is_the_local_disk_alone(@TempDir Path tmp) throws Exception {
        storedElsewhere(tmp);
        assertThat(cache(tmp.resolve("here")).restorable("k1")).isEmpty();
    }

    @Test
    void a_batch_answers_every_later_ask_from_memory(@TempDir Path tmp) throws Exception {
        MemoryCacheBackend remote = new MemoryCacheBackend();
        remote.seedFrom(storedElsewhere(tmp), "k1");
        RemoteTier tier = new RemoteTier(remote, "linux-x86_64");

        tier.prefetch(List.of("k1", "k2", "k3"));
        RemoteCaches.where(tier, () -> {
            ActionCache cache = cache(tmp.resolve("here"));
            assertThat(cache.restorable("k1")).isPresent();
            assertThat(cache.restorable("k2")).isEmpty();
            assertThat(cache.lookup("k3")).isEmpty();
            return null;
        });

        assertThat(remote.lookups).hasSize(1);
        assertThat(remote.lookups.getFirst().keys()).containsExactly("k1", "k2", "k3");
    }

    @Test
    void asks_that_arrive_together_share_one_lookup(@TempDir Path tmp) throws Exception {
        MemoryCacheBackend remote = new MemoryCacheBackend();
        remote.seedFrom(storedElsewhere(tmp), "k1");
        RemoteTier tier = new RemoteTier(remote, "linux-x86_64");
        int lanes = 8;
        CyclicBarrier together = new CyclicBarrier(lanes);
        List<Future<Boolean>> asks = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(lanes)) {
            for (int i = 0; i < lanes; i++) {
                String key = i == 0 ? "k1" : "absent-" + i;
                asks.add(pool.submit(() -> {
                    together.await();
                    return RemoteCaches.where(
                            tier,
                            () -> cache(tmp.resolve("here")).restorable(key).isPresent());
                }));
            }
            for (int i = 0; i < lanes; i++) assertThat(asks.get(i).get()).isEqualTo(i == 0);
        }

        assertThat(remote.lookups)
                .as("eight lanes asking at once, one round trip or very few")
                .hasSizeLessThanOrEqualTo(2);
        assertThat(remote.lookups.stream().mapToInt(l -> l.keys().size()).sum()).isEqualTo(lanes);
    }

    @Test
    void a_tier_that_fails_is_a_local_miss_warned_once_and_then_never_asked(@TempDir Path tmp) throws Exception {
        MemoryCacheBackend remote = new MemoryCacheBackend();
        remote.seedFrom(storedElsewhere(tmp), "k1");
        remote.down = true;
        RemoteTier tier = new RemoteTier(remote, "linux-x86_64");
        Recording step = new Recording();

        StepScope.run(
                step,
                () -> RemoteCaches.where(tier, () -> {
                    ActionCache cache = cache(tmp.resolve("here"));
                    assertThat(cache.restorable("k1")).isEmpty();
                    assertThat(cache.restorable("k2")).isEmpty();
                    assertThat(cache.lookup("k1")).isEmpty();
                    return null;
                }));

        assertThat(step.warnings).singleElement().asString().startsWith(RemoteTier.UNREACHABLE + "=");
        assertThat(tier.reachable()).isFalse();
        remote.down = false;
        RemoteCaches.where(tier, () -> cache(tmp.resolve("here")).restorable("k1"));
        assertThat(remote.lookups).as("off for the rest of the build").isEmpty();
    }

    @Test
    void a_blob_whose_bytes_do_not_match_its_digest_is_a_miss_that_leaves_nothing_behind(@TempDir Path tmp)
            throws Exception {
        MemoryCacheBackend remote = new MemoryCacheBackend();
        ActionCache elsewhere = storedElsewhere(tmp);
        remote.seedFrom(elsewhere, "k1");
        String a = requireNonNull(elsewhere.lookup("k1").orElseThrow().outputs().get("A.class"));
        remote.blobs.put(a, "class Evil".getBytes(StandardCharsets.UTF_8));
        RemoteTier tier = new RemoteTier(remote, "linux-x86_64");

        boolean restored = RemoteCaches.where(tier, () -> {
            ActionCache cache = cache(tmp.resolve("here"));
            ActionCache.ActionRecord record = cache.restorable("k1").orElseThrow();
            return cache.restore(record, tmp.resolve("out"));
        });

        assertThat(restored).isFalse();
        assertThat(cache(tmp.resolve("here")).cas().contains(a)).isFalse();
        assertThat(cache(tmp.resolve("here")).lookup("k1")).isEmpty();
        assertThat(tier.hits()).isZero();
    }

    @Test
    void a_remote_record_this_machine_refuses_is_a_miss_and_stays_in_the_store(@TempDir Path tmp) throws Exception {
        MemoryCacheBackend remote = new MemoryCacheBackend();
        remote.seedFrom(storedElsewhere(tmp), "k1");
        remote.records.put("k9", remote.records.get("k1")); // a record that names another key
        RemoteTier tier = new RemoteTier(remote, "linux-x86_64");

        Optional<ActionCache.ActionRecord> answer =
                RemoteCaches.where(tier, () -> cache(tmp.resolve("here")).restorable("k9"));

        assertThat(answer).isEmpty();
        assertThat(remote.records).containsKey("k9");
    }

    /**
     * A record here whose blobs were pruned is restored from the store only through a record of its
     * own this machine takes: one made for another platform is a miss and never replaces this one.
     */
    @Test
    void a_record_here_whose_blobs_went_is_not_replaced_by_one_made_for_another_platform(@TempDir Path tmp)
            throws Exception {
        Path out = Files.createDirectories(tmp.resolve("built"));
        Files.writeString(out.resolve("A.class"), "class A");
        ActionCache elsewhere = cache(tmp.resolve("elsewhere"));
        elsewhere.store("compile-main@x", "k1", Map.of("target", "target:windows-x86_64"), out);
        MemoryCacheBackend remote = new MemoryCacheBackend();
        remote.seedFrom(elsewhere, "k1");
        ActionCache here = cache(tmp.resolve("here"));
        ActionCache.ActionRecord mine = here.store("compile-main@x", "k1", Map.of("src/A.java", "aaa"), out);
        Path keyFile = tmp.resolve("here/actions/keys/k1");
        String before = Files.readString(keyFile);
        Files.delete(here.cas().pathFor(requireNonNull(mine.outputs().get("A.class"))));
        RemoteTier tier = new RemoteTier(remote, "linux-x86_64");

        boolean restored = RemoteCaches.where(tier, () -> {
            ActionCache cache = cache(tmp.resolve("here"));
            return cache.restore(cache.lookup("k1").orElseThrow(), tmp.resolve("out"));
        });

        assertThat(restored)
                .as("the store holds only another platform's record")
                .isFalse();
        assertThat(Files.readString(keyFile)).as("this machine's record stays").isEqualTo(before);
        assertThat(tier.hits()).isZero();
    }

    @Test
    void a_record_with_no_blob_is_kept_here_on_its_first_lookup(@TempDir Path tmp) throws Exception {
        ActionCache elsewhere = cache(tmp.resolve("elsewhere"));
        elsewhere.storeWithOutputs("run-tests@x", "stamp", Map.of(), TestStamp.outcome(3, 3, 0, 0));
        MemoryCacheBackend remote = new MemoryCacheBackend();
        remote.seedFrom(elsewhere, "stamp");
        RemoteTier tier = new RemoteTier(remote, "linux-x86_64");

        RemoteCaches.where(tier, () -> cache(tmp.resolve("here")).lookup("stamp"));

        assertThat(cache(tmp.resolve("here")).lookup("stamp").orElseThrow().outputs())
                .containsEntry(TestStamp.TOTAL, "3");
        assertThat(tier.hits()).isEqualTo(1);
    }

    @Test
    void a_lookup_tells_the_backend_which_toolchains_the_reader_takes(@TempDir Path tmp) throws Exception {
        MemoryCacheBackend remote = new MemoryCacheBackend();
        remote.seedFrom(storedElsewhere(tmp), "k1");
        RemoteTier tier = new RemoteTier(remote, "linux-x86_64");
        String corretto = "toolchain:jdk:amazon.com-inc./25.0.3+9-LTS/cccccccccccccccc";

        Request.of(1, new IoLedger()).run(() -> {
            try {
                ToolchainPolicy.expectForTests(
                        "k1",
                        new Expectation(corretto, 21, Match.MAJOR, new ToolchainPolicy.Requirement("temurin", "25")));
                RemoteCaches.where(tier, () -> cache(tmp.resolve("here")).restorable("k1"));
            } finally {
                RequestScope.release();
            }
        });

        assertThat(remote.lookups).singleElement().satisfies(l -> assertThat(l.accept())
                .containsExactly(Map.entry("k1", new CacheBackend.Accept(corretto, 21, "major", "eclipse", "25"))));
    }

    @Test
    void a_tier_built_to_write_sends_nothing_to_a_backend_that_says_this_caller_writes_nothing(@TempDir Path tmp)
            throws Exception {
        MemoryCacheBackend remote = new MemoryCacheBackend();
        remote.writable = false;
        RemoteTier tier = new RemoteTier(remote, "linux-x86_64", true);

        storeThrough(tier, tmp);

        assertThat(tier.writes()).isFalse();
        assertThat(remote.records).isEmpty();
        assertThat(remote.blobs).isEmpty();
        assertThat(remote.puts).isZero();
    }

    @Test
    void a_record_the_store_does_not_take_is_skipped_and_the_next_still_goes(@TempDir Path tmp) throws Exception {
        MemoryCacheBackend remote = new MemoryCacheBackend();
        remote.skip.add("k1");
        RemoteTier tier = new RemoteTier(remote, "linux-x86_64", true);

        storeThrough(tier, tmp);

        assertThat(tier.writes()).as("a skipped record stops nothing").isTrue();
        assertThat(remote.records).containsOnlyKeys("k2");
        assertThat(tier.uploaded()).isEqualTo(1);
    }

    @Test
    void a_record_the_store_could_not_take_now_is_tried_again_and_lands(@TempDir Path tmp) throws Exception {
        MemoryCacheBackend remote = new MemoryCacheBackend();
        remote.unavailable.put("k1", 2);
        RemoteTier tier = new RemoteTier(remote, "linux-x86_64", true, Duration.ZERO);

        storeThrough(tier, tmp);

        assertThat(remote.records).containsOnlyKeys("k1", "k2");
        assertThat(tier.uploaded()).isEqualTo(2);
        assertThat(tier.notStoredLine()).as("everything landed").isNull();
    }

    @Test
    void a_record_that_fails_every_try_is_not_stored_the_others_go_and_the_run_says_why(@TempDir Path tmp)
            throws Exception {
        MemoryCacheBackend remote = new MemoryCacheBackend();
        remote.unavailable.put("k1", 1_000);
        RemoteTier tier = new RemoteTier(remote, "linux-x86_64", true, Duration.ZERO);

        storeThrough(tier, tmp);

        assertThat(remote.records).as("one record's failure stops no other").containsOnlyKeys("k2");
        assertThat(tier.writes()).isTrue();
        assertThat(remote.unavailable.get("k1"))
                .as("tried " + RemoteTier.ATTEMPTS + " times, no more")
                .isEqualTo(1_000 - RemoteTier.ATTEMPTS);
        assertThat(tier.notStored()).containsExactly(Map.entry("503 unavailable after 4 tries", 1));
        assertThat(tier.notStoredLine())
                .isEqualTo("remote cache memory: 1 record not stored (1 503 unavailable after 4 tries)");
    }

    @Test
    void a_store_that_refuses_this_callers_writes_stops_the_builds_uploads(@TempDir Path tmp) throws Exception {
        MemoryCacheBackend remote = new MemoryCacheBackend();
        remote.readOnly = true;
        RemoteTier tier = new RemoteTier(remote, "linux-x86_64", true, Duration.ZERO);

        storeThrough(tier, tmp);

        assertThat(tier.writes()).as("no later put would be taken").isFalse();
        assertThat(tier.notStoredLine())
                .startsWith("remote cache memory: 2 records not stored (")
                .contains("403 read_only");
    }

    @Test
    void a_failure_is_named_by_the_stores_status_and_code() {
        assertThat(RemoteTier.reason(new CacheBackend.Unavailable("record abc: 503 unavailable (index down)")))
                .isEqualTo("503 unavailable");
        assertThat(RemoteTier.reason(new IOException("blob abc: 500 from the store")))
                .isEqualTo("500 from the store");
        assertThat(RemoteTier.reason(new HttpTimeoutException("request timed out")))
                .isEqualTo("HttpTimeoutException");
    }

    @Test
    void blobs_the_store_lost_before_the_put_are_sent_again_once(@TempDir Path tmp) throws Exception {
        MemoryCacheBackend remote = new MemoryCacheBackend();
        remote.loseBlobsBeforeFirstPut = true;
        RemoteTier tier = new RemoteTier(remote, "linux-x86_64", true);

        storeThrough(tier, tmp);

        assertThat(remote.records).containsOnlyKeys("k1", "k2");
        assertThat(tier.uploaded()).isEqualTo(2);
    }

    @Test
    void a_suites_read_list_rides_the_upload_of_its_result(@TempDir Path tmp) throws Exception {
        MemoryCacheBackend remote = new MemoryCacheBackend();
        RemoteTier tier = new RemoteTier(remote, "linux-x86_64", true);
        CacheBackend.Reads reads = new CacheBackend.Reads("base", List.of("ws:data/fixture.txt"));

        tier.noteReads("k1", reads);
        storeThrough(tier, tmp);

        assertThat(remote.reads).containsExactly(Map.entry("k1", reads));
    }

    @Test
    void results_found_by_a_base_are_offered_and_one_taken_needs_no_second_lookup(@TempDir Path tmp) throws Exception {
        ActionCache elsewhere = cache(tmp.resolve("elsewhere"));
        elsewhere.storeWithOutputs("run-tests@x", "settled", Map.of(), TestStamp.outcome(3, 3, 0, 0));
        MemoryCacheBackend remote = new MemoryCacheBackend();
        remote.seedFrom(elsewhere, "settled");
        CacheBackend.Reads reads = new CacheBackend.Reads("base", List.of("ws:data/fixture.txt"));
        remote.reads.put("settled", reads);
        RemoteTier tier = new RemoteTier(remote, "linux-x86_64");

        List<CacheBackend.Candidate> candidates = tier.candidates("base", null);
        assertThat(candidates).singleElement().satisfies(c -> {
            assertThat(c.key()).isEqualTo("settled");
            assertThat(c.reads()).isEqualTo(reads);
        });
        tier.take(candidates.getFirst());

        assertThat(tier.record("settled")).contains("tests.total");
        assertThat(remote.lookups).as("the base's lookup brought the result").hasSize(1);
    }

    /** Store two records, k1 and k2, with the tier bound, and wait for their uploads. */
    private static void storeThrough(RemoteTier tier, Path tmp) throws Exception {
        Path out = tmp.resolve("built");
        Files.createDirectories(out);
        Files.writeString(out.resolve("A.class"), "class A");
        RemoteCaches.where(tier, () -> {
            ActionCache cache = cache(tmp.resolve("here"));
            cache.store("compile-main@x", "k1", Map.of("src/A.java", "aaa"), out);
            cache.store("compile-main@y", "k2", Map.of("src/A.java", "bbb"), out);
            return null;
        });
        assertThat(tier.drain(Duration.ofSeconds(30))).isTrue();
    }

    private static ActionCache storedElsewhere(Path tmp) throws IOException {
        Path out = tmp.resolve("built");
        Files.createDirectories(out.resolve("b"));
        Files.writeString(out.resolve("A.class"), "class A");
        Files.writeString(out.resolve("b/B.class"), "class B");
        ActionCache elsewhere = cache(tmp.resolve("elsewhere"));
        elsewhere.store("compile-main@x", "k1", Map.of("src/A.java", "aaa"), out);
        return elsewhere;
    }

    private static ActionCache cache(Path home) {
        return new ActionCache(new Cas(home.resolve("cas")), home.resolve("actions"));
    }

    private static final class Recording implements TaskContext {
        final List<String> warnings = new ArrayList<>();

        @Override
        public void progress(int delta) {}

        @Override
        public void updateTicks(int additional) {}

        @Override
        public void label(@Nullable String description) {}

        @Override
        public void output(@Nullable String line) {}

        @Override
        public void warn(String code, String message) {
            warnings.add(code + "=" + message);
        }

        @Override
        public void error(String code, String message) {
            warnings.add("error " + code + "=" + message);
        }

        @Override
        public boolean cancelled() {
            return false;
        }

        @Override
        public <T> void put(BuildPlanKey<T> key, T value) {}

        @Override
        public <T> Optional<T> get(BuildPlanKey<T> key) {
            return Optional.empty();
        }

        @Override
        public <T> T require(BuildPlanKey<T> key) {
            throw new IllegalStateException("no " + key);
        }
    }
}
