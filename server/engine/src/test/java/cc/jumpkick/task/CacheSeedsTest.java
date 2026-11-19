// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.CacheBackend;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where a cache's key seed comes from: the team's own, from the shell or the user's
 * settings, wins over the one the cache issues; an issued seed is kept in the home, owner-only,
 * and asked again only once it is an hour old; a failure is a failure, never a seed. The seeds
 * here are made up.
 */
class CacheSeedsTest {

    private static final String TEAM = "made-up-team-seed-0123";

    private static CacheBackend.IssuedSeed issued(String id) {
        return new CacheBackend.IssuedSeed(id, "made-up-issued-seed-0123456789abc".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void the_teams_seed_in_the_shell_wins_over_the_users_file_and_the_issued_one(@TempDir Path tmp) throws Exception {
        Path seedFile = tmp.resolve("seed");
        Files.writeString(seedFile, "made-up-file-seed-0123\n");
        Path config = tmp.resolve("config.toml");
        Files.writeString(
                config, "[cache]\nkey-seed-file = \"" + seedFile.toString().replace('\\', '/') + "\"\n");
        Map<String, String> env = new HashMap<>();

        assertThat(CacheSeeds.resolve(env::get, tmp.resolve("none.toml"))).isEqualTo(CacheSeeds.Source.ISSUED);
        CacheSeeds.Source fromFile = CacheSeeds.resolve(env::get, config);
        assertThat(fromFile).isEqualTo(new CacheSeeds.Team("made-up-file-seed-0123".getBytes(StandardCharsets.UTF_8)));
        env.put(CacheSeeds.ENV, TEAM);
        CacheSeeds.Source fromShell = CacheSeeds.resolve(env::get, config);
        assertThat(fromShell).isEqualTo(new CacheSeeds.Team(TEAM.getBytes(StandardCharsets.UTF_8)));
        assertThat(fromShell.toString()).doesNotContain(TEAM);

        MemoryCacheBackend backend = new MemoryCacheBackend();
        backend.issued = issued("proj:1");
        KeySeed team = CacheSeeds.keySeed(fromShell, backend);
        assertThat(team).isEqualTo(KeySeed.derive(TEAM.getBytes(StandardCharsets.UTF_8), "team\u0000memory"));
        assertThat(backend.seedAsks).as("a team seed asks the cache nothing").isZero();
    }

    @Test
    void off_takes_no_seed_and_a_short_one_is_refused(@TempDir Path tmp) {
        Path none = tmp.resolve("none.toml");
        assertThat(CacheSeeds.resolve(Map.of(CacheSeeds.ENV, "off")::get, none)).isEqualTo(CacheSeeds.Source.OFF);
        assertThat(CacheSeeds.resolve(Map.of(CacheSeeds.ENV, "too-short")::get, none))
                .as("a short seed is no seed: the issued one is not taken in its place")
                .isEqualTo(CacheSeeds.Source.OFF);
    }

    @Test
    void an_issued_seed_is_kept_owner_only_and_asked_again_only_once_an_hour_old(@TempDir Path tmp) throws Exception {
        MemoryCacheBackend backend = new MemoryCacheBackend();
        backend.issued = issued("proj:1");
        Path dir = tmp.resolve("cache-seeds");

        KeySeed first = CacheSeeds.issued(backend, dir);
        KeySeed again = CacheSeeds.issued(backend, dir);

        assertThat(first).isNotNull().isEqualTo(again);
        assertThat(first).isEqualTo(KeySeed.derive(issued("proj:1").seed(), "issued\u0000memory\u0000proj:1"));
        assertThat(backend.seedAsks).as("the kept copy answered the second").isEqualTo(1);
        Path kept;
        try (var files = Files.list(dir)) {
            kept = files.findFirst().orElseThrow();
        }
        if (Files.getFileAttributeView(kept, PosixFileAttributeView.class) != null)
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(kept)))
                    .isEqualTo("rw-------");

        // An hour on, the cache is asked again and its rotated seed taken.
        Files.writeString(kept, Files.readString(kept).replaceFirst("fetched \\d+", "fetched 0"));
        backend.issued = issued("proj:2");
        KeySeed rotated = CacheSeeds.issued(backend, dir);
        assertThat(backend.seedAsks).isEqualTo(2);
        assertThat(rotated).isNotEqualTo(first);
    }

    @Test
    void a_cache_that_issues_none_is_kept_as_none_and_a_failure_is_thrown(@TempDir Path tmp) throws Exception {
        MemoryCacheBackend backend = new MemoryCacheBackend();
        Path dir = tmp.resolve("cache-seeds");

        assertThat(CacheSeeds.issued(backend, dir)).isNull();
        assertThat(CacheSeeds.issued(backend, dir)).isNull();
        assertThat(backend.seedAsks).isEqualTo(1);

        MemoryCacheBackend failing = new MemoryCacheBackend();
        failing.scope = "another";
        failing.seedFailure = new IOException("key seed: 503 unavailable");
        assertThatThrownBy(() -> CacheSeeds.issued(failing, dir)).isInstanceOf(IOException.class);
    }

    @Test
    void a_tier_whose_cache_fails_to_hand_its_seed_out_has_none_and_asks_once_a_build() {
        MemoryCacheBackend backend = new MemoryCacheBackend();
        // A scope no home has kept an answer for: the tier asks.
        backend.scope = "memory:" + UUID.randomUUID();
        backend.seedFailure = new IOException("key seed: 503 unavailable");
        RemoteTier tier = new RemoteTier(backend, "linux-x86_64")
                .seededBy(new CacheSeeds.Team(TEAM.getBytes(StandardCharsets.UTF_8)), false);
        assertThat(tier.keySeed()).as("a team seed needs no answer").isNotNull();

        RemoteTier issuedTier = new RemoteTier(backend, "linux-x86_64");
        assertThat(issuedTier.keySeed()).isNull();
        assertThat(issuedTier.keySeed()).isNull();
        assertThat(backend.seedAsks).isEqualTo(1);
    }

    @Test
    void credentials_share_under_the_seed_unless_the_user_keeps_them_here(@TempDir Path tmp) throws Exception {
        Path none = tmp.resolve("none.toml");
        Path config = tmp.resolve("config.toml");
        Files.writeString(config, "[cache]\ncredential-env = \"local\"\n");

        assertThat(CacheSeeds.credentialsLocal(Map.<String, String>of()::get, none))
                .as("by default a credential's value keys the shared form like any other")
                .isFalse();
        assertThat(CacheSeeds.credentialsLocal(Map.<String, String>of()::get, config))
                .isTrue();
        assertThat(CacheSeeds.credentialsLocal(Map.of(CacheSeeds.CREDENTIAL_ENV, "shared")::get, config))
                .as("the shell wins over the user's file")
                .isFalse();
        assertThat(CacheSeeds.credentialsLocal(Map.of(CacheSeeds.CREDENTIAL_ENV, "LOCAL")::get, none))
                .isTrue();
        assertThat(CacheSeeds.credentialsLocal(Map.of(CacheSeeds.CREDENTIAL_ENV, "nope")::get, none))
                .as("a word that is neither sends nothing")
                .isTrue();
    }
}
