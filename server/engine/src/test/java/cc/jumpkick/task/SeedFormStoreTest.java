// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.ActionRecordText;
import cc.jumpkick.cache.ActionRecordText.Writes;
import cc.jumpkick.cache.Cas;
import cc.jumpkick.host.ActionTree;
import cc.jumpkick.host.PathUtil;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A team's key seed with no remote cache: the request's own action cache keeps a result that read
 * the environment under its key's shared form beside its own key, so a copy of the cache directory
 * on another machine replays it under the same seed and under no other, and a home without a seed
 * finds nothing it could match. Keys and values are made up; a shared key here is what {@code
 * SharedStampKeys} would take, the value keyed under the tier's seed.
 */
class SeedFormStoreTest {

    private static final String TEAM = "made-up-team-seed-0123";

    private static final Map<String, String> HOST_KEYED_INPUTS = Map.of(
            "x:env-read:MY_APP_URL", "made-up-digest", ActionRecordText.HOST_KEYED_INPUT, ActionRecordText.HOST_KEYED);

    private static CacheSeeds.Team team(String seed) {
        return new CacheSeeds.Team(seed.getBytes(StandardCharsets.UTF_8));
    }

    private static RemoteTier tier(String seed) {
        return Objects.requireNonNull(RemoteCacheBinding.seedForms(team(seed)), "a team seed binds a tier");
    }

    private static ActionCache cache(Path home) {
        return new ActionCache(new Cas(home.resolve("cas")), home.resolve("actions"));
    }

    private static Path keys(Path home) {
        return ActionTree.KEYS.under(home.resolve("actions"));
    }

    /** The shared key a host takes for a value under {@code tier}'s seed. */
    private static String shared(RemoteTier tier, String value) {
        KeySeed seed = Objects.requireNonNull(tier.keySeed(), "a team seed keys");
        return "shared-" + seed.hmac("MY_APP_URL\u0000" + value).substring(0, 16);
    }

    private static void storeGreen(RemoteTier tier, Path home, String key, Map<String, String> inputs)
            throws Exception {
        RemoteCaches.where(tier, () -> cache(home)
                .storeMarkers(
                        Writes.TEST_MARKER,
                        "run-tests@lib",
                        key,
                        inputs,
                        Map.of("tests.run", "1", "tests.failed", "0"),
                        List.of("a warning naming /made/up/abs/Path.java")));
        assertThat(tier.drain(Duration.ofSeconds(30))).isTrue();
    }

    private static List<String> names(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(f -> f.getFileName().toString()).sorted().toList();
        }
    }

    private static ActionCache.@Nullable ActionRecord lookup(@Nullable RemoteTier tier, Path home, String key)
            throws Exception {
        if (tier == null) return cache(home).lookup(key).orElse(null);
        return RemoteCaches.where(tier, () -> cache(home).lookup(key).orElse(null));
    }

    @Test
    void only_the_teams_own_seed_binds_the_action_cache_as_the_tier_of_shared_forms(@TempDir Path tmp) {
        RemoteTier tier = tier(TEAM);
        assertThat(tier.sharedFormsOnly()).isTrue();
        assertThat(tier.writes()).isTrue();
        assertThat(RemoteCacheBinding.seedForms(CacheSeeds.Source.ISSUED))
                .as("an issued seed never keys the local cache, and with no cache none is issued")
                .isNull();
        assertThat(RemoteCacheBinding.seedForms(CacheSeeds.Source.OFF)).isNull();
        Path none = tmp.resolve("none.toml");
        assertThat(RemoteCacheBinding.seedForms(CacheSeeds.resolve(Map.of(CacheSeeds.ENV, "off")::get, none)))
                .as("JK_CACHE_KEY_SEED=off")
                .isNull();
        assertThat(RemoteCacheBinding.seedForms(CacheSeeds.resolve(Map.of(CacheSeeds.ENV, "too-short")::get, none)))
                .as("a seed under 16 characters is no seed")
                .isNull();
        assertThat(RemoteCacheBinding.seedForms(CacheSeeds.resolve(Map.<String, String>of()::get, none)))
                .as("nothing set")
                .isNull();
    }

    @Test
    void one_seed_keys_alike_on_every_machine_and_apart_from_any_remote_cache() throws Exception {
        KeySeed one = tier(TEAM).keySeed();
        assertThat(tier(TEAM).keySeed())
                .as("no path, URL, home or user in the scope")
                .isEqualTo(one)
                .isEqualTo(KeySeed.derive(TEAM.getBytes(StandardCharsets.UTF_8), "team\u0000local cache"));
        assertThat(tier("made-up-other-seed-4567").keySeed()).isNotEqualTo(one);
        assertThat(CacheSeeds.keySeed(team(TEAM), new MemoryCacheBackend()))
                .as("a remote cache's team key is derived under its own scope, as before")
                .isNotEqualTo(one);
    }

    @Test
    void a_restored_copy_replays_under_the_same_seed_and_under_no_other_or_none(@TempDir Path tmp) throws Exception {
        Path a = tmp.resolve("a");
        RemoteTier writer = tier(TEAM);
        String shared = shared(writer, "one");
        writer.shareAs("local-a", shared);
        storeGreen(writer, a, "local-a", HOST_KEYED_INPUTS);

        assertThat(names(keys(a))).containsExactlyInAnyOrder("local-a", shared);
        assertThat(Files.readString(keys(a).resolve(shared)))
                .contains("KEY " + shared + "\n")
                .doesNotContain(ActionRecordText.HOST_KEYED_INPUT)
                .doesNotContain("made-up-digest")
                .doesNotContain("/made/up/abs");
        assertThat(Files.readString(keys(a).resolve("local-a")))
                .as("the record under this host's key is as it was")
                .contains(ActionRecordText.HOST_KEYED_INPUT)
                .contains("/made/up/abs");

        Path b = tmp.resolve("b");
        PathUtil.copyTree(a, b);
        RemoteTier same = tier(TEAM);
        same.shareAs("local-b", shared(same, "one"));
        ActionCache.ActionRecord hit =
                Objects.requireNonNull(lookup(same, b, "local-b"), "the same seed and value replay");
        assertThat(hit.actionKey()).isEqualTo("local-b");
        assertThat(same.hits()).isEqualTo(1);
        assertThat(lookup(null, b, "local-b")).as("kept under B's own key").isNotNull();

        Path c = tmp.resolve("c");
        PathUtil.copyTree(a, c);
        RemoteTier other = tier("made-up-other-seed-4567");
        other.shareAs("local-c", shared(other, "one"));
        assertThat(lookup(other, c, "local-c")).as("another seed").isNull();
        RemoteTier otherValue = tier(TEAM);
        otherValue.shareAs("local-c", shared(otherValue, "two"));
        assertThat(lookup(otherValue, c, "local-c")).as("another value").isNull();
        assertThat(lookup(null, c, "local-c"))
                .as("no seed: no tier, and the own key is not there")
                .isNull();
    }

    @Test
    void a_record_with_no_shared_form_is_neither_asked_again_nor_stored_twice(@TempDir Path tmp) throws Exception {
        Path a = tmp.resolve("a");
        RemoteTier tier = tier(TEAM);
        storeGreen(tier, a, "plain", Map.of("file:/made/up/abs/in.txt", "made-up-digest"));
        tier.shareAs("own", "own");
        storeGreen(tier, a, "own", HOST_KEYED_INPUTS);

        assertThat(names(keys(a))).containsExactlyInAnyOrder("plain", "own");
        assertThat(Files.readString(keys(a).resolve("plain")))
                .as("not rewritten in the form that may leave the machine")
                .contains("/made/up/abs");
        assertThat(lookup(tier, a, "missing")).isNull();
        assertThat(tier.reachable()).isTrue();
        assertThat(tier.hits()).isZero();
    }
}
