// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.ActionRecordText.Kind;
import cc.jumpkick.cache.Cas;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.StepCache;
import cc.jumpkick.run.StepScope;
import cc.jumpkick.run.TaskContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Each step is told what the action cache did for it: a first store is a miss with no record, a
 * store after one input changed names that input, a toolchain-only change says so, a restore is a
 * hit with its bytes, from a remote tier a remote hit, and a green marker read is a hit.
 */
class StepCacheNotesTest {

    @Test
    void a_miss_names_why_and_a_restore_is_a_hit(@TempDir Path tmp) throws Exception {
        ActionCache cache = new ActionCache(new Cas(tmp.resolve("cas")), tmp.resolve("actions"));
        Path out = Files.createDirectories(tmp.resolve("out"));
        Files.writeString(out.resolve("A.class"), "class A");

        StepCache first = step(() -> cache.store("compile-main@x", "k1", Map.of("src/A.java", "a", "jdk", "25"), out));
        Files.writeString(out.resolve("A.class"), "class A2");
        StepCache second = step(() -> cache.store("compile-main@x", "k2", Map.of("src/A.java", "b", "jdk", "25"), out));
        StepCache third = step(() -> cache.store("compile-main@x", "k3", Map.of("src/A.java", "b", "jdk", "26"), out));
        StepCache hit = step(() -> cache.restore(cache.lookup("k1").orElseThrow(), tmp.resolve("back")));

        assertThat(first.outcome()).isEqualTo(StepCache.Outcome.MISS);
        assertThat(first.reason()).isEqualTo(StepCache.Reason.NO_RECORD);
        assertThat(first.key()).isEqualTo("k1");
        assertThat(first.bytesOut()).isEqualTo("class A".length());
        assertThat(second.reason()).isEqualTo(StepCache.Reason.INPUTS_CHANGED);
        assertThat(second.changed()).isEqualTo("src/A.java");
        assertThat(third.reason()).isEqualTo(StepCache.Reason.TOOLCHAIN_CHANGED);
        assertThat(third.changed()).isEqualTo("jdk");
        assertThat(hit.outcome()).isEqualTo(StepCache.Outcome.LOCAL_HIT);
        assertThat(hit.key()).isEqualTo("k1");
        assertThat(hit.producerMillis())
                .as("what the storing step said it worked")
                .isEqualTo(1234);
        assertThat(cache.lookup("k1").orElseThrow().meta().wallMillis()).isEqualTo(1234);
        assertThat(first.producerMillis()).isEqualTo(-1);
        assertThat(hit.bytesIn()).isEqualTo("class A".length());
    }

    @Test
    void a_restore_from_the_remote_tier_is_a_remote_hit(@TempDir Path tmp) throws Exception {
        Path out = Files.createDirectories(tmp.resolve("out"));
        Files.writeString(out.resolve("A.class"), "class A");
        ActionCache elsewhere = new ActionCache(new Cas(tmp.resolve("e/cas")), tmp.resolve("e/actions"));
        elsewhere.store("compile-main@x", "k1", Map.of(), out);
        MemoryCacheBackend remote = new MemoryCacheBackend();
        remote.seedFrom(elsewhere, "k1");
        RemoteTier tier = new RemoteTier(remote, "linux-x86_64");

        StepCache hit = step(() -> RemoteCaches.where(tier, () -> {
            ActionCache here = new ActionCache(new Cas(tmp.resolve("h/cas")), tmp.resolve("h/actions"));
            return here.restore(here.restorable("k1").orElseThrow(), tmp.resolve("back"));
        }));

        assertThat(hit.outcome()).isEqualTo(StepCache.Outcome.REMOTE_HIT);
        assertThat(hit.bytesIn()).isEqualTo("class A".length());
        assertThat(hit.producerMillis())
                .as("unknown: the record was stored outside a step")
                .isEqualTo(-1);
    }

    @Test
    void a_green_marker_read_is_a_hit_and_a_red_one_that_runs_again_is_a_miss(@TempDir Path tmp) throws Exception {
        ActionCache cache = new ActionCache(new Cas(tmp.resolve("cas")), tmp.resolve("actions"));
        cache.storeMarkers(
                Kind.TEST_MARKER, "run-tests@x", "green", Map.of(), TestStamp.outcome(1, 1, 0, 0), List.of());
        cache.storeMarkers(Kind.TEST_MARKER, "run-tests@x", "red", Map.of(), TestStamp.outcome(1, 0, 0, 1), List.of());

        StepCache green = step(() -> cache.lookup("green"));
        StepCache red = step(() -> {
            cache.lookup("red");
            return cache.storeMarkers(
                    Kind.TEST_MARKER, "run-tests@x", "red", Map.of(), TestStamp.outcome(1, 1, 0, 0), List.of());
        });

        assertThat(green.outcome()).isEqualTo(StepCache.Outcome.LOCAL_HIT);
        assertThat(red.outcome()).isEqualTo(StepCache.Outcome.MISS);
        assertThat(red.reason())
                .as("a whole record under this very key, run anyway")
                .isEqualTo(StepCache.Reason.FORCED);
    }

    private interface Body {
        Object call() throws Exception;
    }

    private static StepCache step(Body body) throws Exception {
        Recording ctx = new Recording();
        StepScope.call(ctx, body::call);
        StepCache told = ctx.cache;
        assertThat(told).as("the step was told").isNotNull();
        return Objects.requireNonNull(told);
    }

    private static final class Recording implements TaskContext {
        @Nullable
        StepCache cache;

        @Override
        public void cache(StepCache cache) {
            this.cache = cache;
        }

        @Override
        public long workedMillis() {
            return 1234;
        }

        @Override
        public void progress(int delta) {}

        @Override
        public void updateTicks(int additional) {}

        @Override
        public void label(@Nullable String description) {}

        @Override
        public void output(@Nullable String line) {}

        @Override
        public void warn(String code, String message) {}

        @Override
        public void error(String code, String message) {}

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
