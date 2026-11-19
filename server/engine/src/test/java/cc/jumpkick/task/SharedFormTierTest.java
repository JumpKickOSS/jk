// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.ActionRecordText;
import cc.jumpkick.cache.ActionRecordText.Writes;
import cc.jumpkick.cache.Cas;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A suite result whose key holds a value only this home keys alike, between the action cache and
 * a remote tier that knows its key's shared form: it is uploaded under the shared form
 * without the host mark, a host that asks by its own key is served by the shared form and keeps the
 * record under its own key, and with no shared form nothing leaves the host.
 */
class SharedFormTierTest {

    private static final Map<String, String> HOST_KEYED_INPUTS = Map.of(
            "x:env-read:MY_APP_URL", "made-up-digest", ActionRecordText.HOST_KEYED_INPUT, ActionRecordText.HOST_KEYED);

    private static ActionCache cache(Path home) {
        return new ActionCache(new Cas(home.resolve("cas")), home.resolve("actions"));
    }

    private static void storeGreen(RemoteTier tier, Path home, String key) throws Exception {
        RemoteCaches.where(tier, () -> cache(home)
                .storeMarkers(
                        Writes.TEST_MARKER,
                        "run-tests@lib",
                        key,
                        HOST_KEYED_INPUTS,
                        Map.of("tests.run", "1", "tests.failed", "0"),
                        List.of()));
        assertThat(tier.drain(Duration.ofSeconds(30))).isTrue();
    }

    @Test
    void a_host_keyed_result_goes_up_under_its_shared_form_without_the_host_mark(@TempDir Path tmp) throws Exception {
        MemoryCacheBackend remote = new MemoryCacheBackend();
        RemoteTier tier = new RemoteTier(remote, "linux-x86_64", true);
        tier.shareAs("local-a", "shared");

        storeGreen(tier, tmp.resolve("a"), "local-a");

        assertThat(remote.records).containsOnlyKeys("shared");
        assertThat(remote.records.get("shared"))
                .contains("KEY shared\n")
                .doesNotContain("local-a")
                .doesNotContain(ActionRecordText.HOST_KEYED_INPUT)
                .doesNotContain("made-up-digest");
        assertThat(cache(tmp.resolve("a")).lookup("local-a"))
                .as("kept here under the key this host took, host mark and all")
                .hasValueSatisfying(r -> assertThat(r.inputs()).containsKey(ActionRecordText.HOST_KEYED_INPUT));
    }

    @Test
    void with_no_shared_form_a_host_keyed_result_stays_here(@TempDir Path tmp) throws Exception {
        MemoryCacheBackend remote = new MemoryCacheBackend();
        RemoteTier tier = new RemoteTier(remote, "linux-x86_64", true);

        storeGreen(tier, tmp.resolve("a"), "local-a");

        assertThat(remote.records).isEmpty();
        assertThat(remote.puts).isZero();
    }

    @Test
    void another_host_asks_by_its_own_key_and_keeps_the_shared_result_under_it(@TempDir Path tmp) throws Exception {
        MemoryCacheBackend remote = new MemoryCacheBackend();
        RemoteTier writer = new RemoteTier(remote, "linux-x86_64", true);
        writer.shareAs("local-a", "shared");
        storeGreen(writer, tmp.resolve("a"), "local-a");

        RemoteTier reader = new RemoteTier(remote, "linux-x86_64");
        reader.shareAs("local-b", "shared");
        ActionCache.ActionRecord hit = RemoteCaches.where(
                reader, () -> cache(tmp.resolve("b")).lookup("local-b").orElseThrow());

        assertThat(hit.actionKey()).isEqualTo("local-b");
        assertThat(remote.lookups.getFirst().keys()).containsExactly("shared");
        assertThat(cache(tmp.resolve("b")).lookup("local-b"))
                .as("kept for the next build, no tier needed")
                .isPresent();
        assertThat(reader.hits()).isEqualTo(1);

        RemoteTier unseeded = new RemoteTier(remote, "linux-x86_64");
        assertThat(RemoteCaches.where(unseeded, () -> cache(tmp.resolve("c")).lookup("local-b")))
                .as("a host whose key has no shared form asks its own key, which no one stored")
                .isEmpty();
    }

    /**
     * A key noted as its own shared form (keyed on this home's secret, no seed to share it under)
     * is uploaded nowhere, host mark or not: a class ledger's record carries none when only a
     * declared variable's value keyed the suite.
     */
    @Test
    void a_key_noted_as_its_own_shared_form_is_uploaded_nowhere(@TempDir Path tmp) throws Exception {
        MemoryCacheBackend remote = new MemoryCacheBackend();
        RemoteTier tier = new RemoteTier(remote, "linux-x86_64", true);
        tier.shareAs("local-a", "local-a");

        RemoteCaches.where(tier, () -> cache(tmp.resolve("a"))
                .storeMarkers(
                        Writes.TEST_MARKER,
                        "run-tests@lib",
                        "local-a",
                        Map.of(),
                        Map.of("tests.run", "1", "tests.failed", "0"),
                        List.of()));
        assertThat(tier.drain(Duration.ofSeconds(30))).isTrue();

        assertThat(remote.records).isEmpty();
        assertThat(cache(tmp.resolve("a")).lookup("local-a")).as("kept here").isPresent();
    }
}
