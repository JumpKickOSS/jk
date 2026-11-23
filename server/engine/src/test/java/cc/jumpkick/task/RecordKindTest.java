// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.ActionRecordText;
import cc.jumpkick.cache.ActionRecordText.Writes;
import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.StepKinds;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every record names the step kind that made it (an open, namespaced string) and what it holds
 * ({@code WRITES}), and the two decide what may leave the machine: outputs, aliases, verdicts,
 * green markers and all-green ledgers of a kind this jk knows; never a red marker, a ledger holding
 * a failure, or a record of a kind this jk does not know, which is carried but not shared. A tier
 * that writes is handed exactly those, blobs before the record.
 */
class RecordKindTest {

    @Test
    void every_store_path_writes_its_kind(@TempDir Path tmp) throws Exception {
        ActionCache cache = cache(tmp);
        Path out = Files.createDirectories(tmp.resolve("out"));
        Files.writeString(out.resolve("A.class"), "class A");

        cache.store("compile-main@x", "outputs", Map.of(), out);
        cache.storeArtifacts("package-jar@x", "artifact", Map.of(), out, List.of(out.resolve("A.class")));
        cache.storeVerdict("guard@x", "verdict", Map.of());
        cache.storeWithOutputs("node-build@x", "empty", Map.of(), Map.of());
        cache.storeAlias("outputs", "alias", Map.of("src", "b"));
        cache.storeMarkers(
                Writes.TEST_MARKER, "run-tests@x", "marker", Map.of(), TestStamp.outcome(2, 2, 0, 0), List.of());
        cache.storeMarkers(
                Writes.CLASS_LEDGER,
                "run-tests@x#classes",
                "ledger",
                Map.of(),
                Map.of("|a.A", "2,0,0,1700000000000,12"),
                List.of());

        assertThat(kind(cache, "outputs")).isEqualTo(Writes.OUTPUTS);
        assertThat(kind(cache, "artifact")).isEqualTo(Writes.OUTPUTS);
        assertThat(kind(cache, "verdict")).isEqualTo(Writes.VERDICT);
        assertThat(kind(cache, "empty")).isEqualTo(Writes.VERDICT);
        assertThat(kind(cache, "alias")).isEqualTo(Writes.ALIAS);
        assertThat(kind(cache, "marker")).isEqualTo(Writes.TEST_MARKER);
        assertThat(kind(cache, "ledger")).isEqualTo(Writes.CLASS_LEDGER);
        assertThat(Files.readString(tmp.resolve("actions/keys/marker")))
                .contains("\nKIND java:run-tests\nWRITES test-marker\n");
        assertThat(step(cache, "outputs")).isEqualTo("java:compile");
        assertThat(step(cache, "artifact")).isEqualTo("package:jar");
        assertThat(step(cache, "verdict")).isEqualTo("guard:check");
        assertThat(step(cache, "empty")).isEqualTo("node:build");
        assertThat(step(cache, "ledger")).isEqualTo("java:run-tests");
    }

    @Test
    void red_markers_and_failing_or_cleared_ledgers_never_take_the_shared_form() {
        assertThat(shared(Writes.TEST_MARKER, TestStamp.outcome(3, 3, 0, 0))).contains("WRITES test-marker");
        assertThat(shared(Writes.TEST_MARKER, TestStamp.noTestsOutcome())).contains("tests.none");
        assertThat(shared(
                        Writes.CLASS_LEDGER, Map.of("|a.A", "2,0,0,1700000000000,12", "|a.B", "1,0,1,1700000000000,3")))
                .contains("WRITES class-ledger");

        assertThatThrownBy(() -> shared(Writes.TEST_MARKER, TestStamp.outcome(3, 2, 0, 1)))
                .isInstanceOf(ActionRecordText.Refused.class);
        assertThatThrownBy(() -> shared(
                        Writes.CLASS_LEDGER, Map.of("|a.A", "2,0,0,1700000000000,12", "|a.B", "1,1,0,1700000000000,4")))
                .isInstanceOf(ActionRecordText.Refused.class);
        assertThatThrownBy(() -> shared(Writes.CLASS_LEDGER, Map.of())).isInstanceOf(ActionRecordText.Refused.class);
        assertThatThrownBy(() -> shared(null, Map.of("tests.total", "1")))
                .as("a record that does not say what it holds")
                .isInstanceOf(ActionRecordText.Refused.class);
    }

    @Test
    void a_kind_this_jk_does_not_know_is_carried_and_never_shared() {
        String key = "k".repeat(64);
        String sha = "a".repeat(64);
        ActionRecordText.Meta meta = new ActionRecordText.Meta(
                2, "rust:compile", Writes.OUTPUTS, null, null, null, null, null, -1, Map.of());
        String text =
                ActionRecordText.render("cargo@x", key, Map.of(), Map.of("lib.rlib", sha), Set.of(), List.of(), meta);

        ActionRecordText.Parsed read = ActionRecordText.parse(text, key);
        assertThat(read.meta().kind()).as("carried, not refused").isEqualTo("rust:compile");
        assertThat(read.meta().writes()).isEqualTo(Writes.OUTPUTS);
        assertThat(ActionRecordText.render("cargo@x", key, Map.of(), read.outputs(), Set.of(), List.of(), read.meta()))
                .isEqualTo(text);
        assertThat(ActionRecordText.shareable(read)).isFalse();
        assertThatThrownBy(() -> ActionRecordText.renderShared(read)).isInstanceOf(ActionRecordText.Refused.class);

        assertThat(StepKinds.known("java:compile")).isTrue();
        assertThat(StepKinds.known("rust:compile")).isFalse();
        assertThat(StepKinds.known("compile")).as("no namespace").isFalse();
        assertThat(StepKinds.known(null)).isFalse();
        assertThat(StepKinds.of("compile-kotlin@0123456789ab")).isEqualTo("kotlin:compile");
        assertThat(StepKinds.of("write-stamp")).isEqualTo("jk:write-stamp");
    }

    @Test
    void a_writing_tier_is_handed_what_may_be_shared_blobs_first(@TempDir Path tmp) throws Exception {
        MemoryCacheBackend remote = new MemoryCacheBackend();
        RemoteTier tier = new RemoteTier(remote, "linux-x86_64", true);
        Path out = Files.createDirectories(tmp.resolve("out"));
        Files.writeString(out.resolve("A.class"), "class A");
        Path abs = tmp.resolve("store/lib.jar");

        RemoteCaches.where(tier, () -> {
            ActionCache cache = cache(tmp);
            cache.store(
                    "compile-main@x",
                    "outputs",
                    Map.of("src/A.java", "a", abs.toString(), "abi:1"),
                    out,
                    List.of("said"));
            cache.storeMarkers(
                    Writes.TEST_MARKER, "run-tests@x", "green", Map.of(), TestStamp.outcome(2, 2, 0, 0), List.of());
            cache.storeMarkers(
                    Writes.TEST_MARKER, "run-tests@x", "red", Map.of(), TestStamp.outcome(2, 1, 0, 1), List.of());
            cache.storeMarkers(Writes.CLASS_LEDGER, "run-tests@x#classes", "cleared", Map.of(), Map.of(), List.of());
            return null;
        });
        assertThat(tier.drain(Duration.ofSeconds(30))).isTrue();

        assertThat(remote.records).containsOnlyKeys("outputs", "green");
        assertThat(remote.blobs).hasSize(1);
        assertThat(remote.records.get("outputs"))
                .doesNotContain("SAID")
                .doesNotContain(abs.toString())
                .contains("src/A.java");
        assertThat(tier.uploaded()).isEqualTo(2);
    }

    @Test
    void a_tier_that_only_reads_is_handed_nothing(@TempDir Path tmp) throws Exception {
        MemoryCacheBackend remote = new MemoryCacheBackend();
        RemoteTier tier = new RemoteTier(remote, "linux-x86_64");

        RemoteCaches.where(tier, () -> cache(tmp).storeVerdict("guard@x", "verdict", Map.of()));
        assertThat(tier.drain(Duration.ofSeconds(30))).isTrue();

        assertThat(remote.records).isEmpty();
    }

    private static String shared(@Nullable Writes writes, Map<String, String> markers) {
        String key = "k".repeat(64);
        ActionRecordText.Meta meta =
                new ActionRecordText.Meta(2, "java:run-tests", writes, null, null, null, null, null, -1, Map.of());
        String text = ActionRecordText.render("run-tests@x", key, Map.of(), markers, Set.of(), List.of(), meta);
        return ActionRecordText.renderShared(ActionRecordText.parse(text, key));
    }

    private static @Nullable Writes kind(ActionCache cache, String key) throws Exception {
        return cache.lookup(key).orElseThrow().meta().writes();
    }

    private static @Nullable String step(ActionCache cache, String key) throws Exception {
        return cache.lookup(key).orElseThrow().meta().kind();
    }

    private static ActionCache cache(Path tmp) {
        return new ActionCache(new Cas(tmp.resolve("cas")), tmp.resolve("actions"));
    }
}
