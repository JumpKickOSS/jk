// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.ActionRecordText;
import cc.jumpkick.cache.ActionRecordText.Kind;
import cc.jumpkick.cache.Cas;
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
 * Every record names its kind, and the kind decides what may leave the machine: outputs, aliases,
 * verdicts, green markers and all-green ledgers; never a red marker or a ledger holding a failure.
 * A tier that writes is handed exactly those, blobs before the record.
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
                Kind.TEST_MARKER, "run-tests@x", "marker", Map.of(), TestStamp.outcome(2, 2, 0, 0), List.of());
        cache.storeMarkers(
                Kind.CLASS_LEDGER, "run-tests@x#classes", "ledger", Map.of(), Map.of("|a.A", "2,0,0"), List.of());

        assertThat(kind(cache, "outputs")).isEqualTo(Kind.OUTPUTS);
        assertThat(kind(cache, "artifact")).isEqualTo(Kind.OUTPUTS);
        assertThat(kind(cache, "verdict")).isEqualTo(Kind.VERDICT);
        assertThat(kind(cache, "empty")).isEqualTo(Kind.VERDICT);
        assertThat(kind(cache, "alias")).isEqualTo(Kind.ALIAS);
        assertThat(kind(cache, "marker")).isEqualTo(Kind.TEST_MARKER);
        assertThat(kind(cache, "ledger")).isEqualTo(Kind.CLASS_LEDGER);
        assertThat(Files.readString(tmp.resolve("actions/keys/marker"))).contains("\nKIND test-marker\n");
    }

    @Test
    void red_markers_and_failing_or_cleared_ledgers_never_take_the_shared_form() {
        assertThat(shared(Kind.TEST_MARKER, TestStamp.outcome(3, 3, 0, 0))).contains("KIND test-marker");
        assertThat(shared(Kind.TEST_MARKER, TestStamp.noTestsOutcome())).contains("tests.none");
        assertThat(shared(Kind.CLASS_LEDGER, Map.of("|a.A", "2,0,0", "|a.B", "1,0,1")))
                .contains("KIND class-ledger");

        assertThatThrownBy(() -> shared(Kind.TEST_MARKER, TestStamp.outcome(3, 2, 0, 1)))
                .isInstanceOf(ActionRecordText.Refused.class);
        assertThatThrownBy(() -> shared(Kind.CLASS_LEDGER, Map.of("|a.A", "2,0,0", "|a.B", "1,1,0")))
                .isInstanceOf(ActionRecordText.Refused.class);
        assertThatThrownBy(() -> shared(Kind.CLASS_LEDGER, Map.of())).isInstanceOf(ActionRecordText.Refused.class);
        assertThatThrownBy(() -> shared(null, Map.of("tests.total", "1")))
                .as("a record of no kind")
                .isInstanceOf(ActionRecordText.Refused.class);
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
                    Kind.TEST_MARKER, "run-tests@x", "green", Map.of(), TestStamp.outcome(2, 2, 0, 0), List.of());
            cache.storeMarkers(
                    Kind.TEST_MARKER, "run-tests@x", "red", Map.of(), TestStamp.outcome(2, 1, 0, 1), List.of());
            cache.storeMarkers(Kind.CLASS_LEDGER, "run-tests@x#classes", "cleared", Map.of(), Map.of(), List.of());
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

    private static String shared(@Nullable Kind kind, Map<String, String> markers) {
        String key = "k".repeat(64);
        ActionRecordText.Meta meta = new ActionRecordText.Meta(1, kind, null, null, null, Map.of());
        String text = ActionRecordText.render("run-tests@x", key, Map.of(), markers, Set.of(), List.of(), meta);
        return ActionRecordText.renderShared(ActionRecordText.parse(text, key));
    }

    private static @Nullable Kind kind(ActionCache cache, String key) throws Exception {
        return cache.lookup(key).orElseThrow().meta().kind();
    }

    private static ActionCache cache(Path tmp) {
        return new ActionCache(new Cas(tmp.resolve("cas")), tmp.resolve("actions"));
    }
}
