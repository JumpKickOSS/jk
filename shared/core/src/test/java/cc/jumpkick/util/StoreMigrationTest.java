// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Moving the fetched set out of {@code cache/} has to be safe when it half-fails, because the cost of
 * losing track of an entry is a re-download from a rate-limited host, not a rebuild.
 */
class StoreMigrationTest {

    private static Path seed(Path dir, String rel, String body) throws Exception {
        Path p = dir.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, body);
        return p;
    }

    @Test
    void the_fetched_set_moves_and_the_build_set_stays(@TempDir Path tmp) throws Exception {
        Path legacy = tmp.resolve("cache");
        Path store = tmp.resolve("store");
        seed(legacy, "sha256/ab/cd/blob", "jar bytes");
        seed(legacy, "repos/central/org/foo/foo-1.0.jar", "jar bytes");
        seed(legacy, "metadata/deadbeef", "<metadata/>");
        seed(legacy, "git/somerepo/HEAD", "ref");
        seed(legacy, "jdks.json", "{}");
        // Build-derived state, which is what JK_CACHE_DIR is for isolating.
        seed(legacy, "actions/key-1", "outputs");
        seed(legacy, "runs/run-1.jsonl", "events");
        seed(legacy, "hash-memo/x", "hash");

        int moved = StoreMigration.migrate(store, legacy);

        assertThat(moved).isEqualTo(5);
        assertThat(store.resolve("sha256/ab/cd/blob")).exists();
        assertThat(store.resolve("repos/central/org/foo/foo-1.0.jar")).exists();
        assertThat(store.resolve("metadata/deadbeef")).exists();
        assertThat(store.resolve("jdks.json")).exists();
        assertThat(legacy.resolve("sha256")).doesNotExist();
        // The action cache and run logs are not fetched from anywhere and must not move.
        assertThat(legacy.resolve("actions/key-1")).exists();
        assertThat(legacy.resolve("runs/run-1.jsonl")).exists();
        assertThat(legacy.resolve("hash-memo/x")).exists();
    }

    @Test
    void running_twice_moves_nothing_the_second_time(@TempDir Path tmp) throws Exception {
        Path legacy = tmp.resolve("cache");
        Path store = tmp.resolve("store");
        seed(legacy, "sha256/ab/cd/blob", "bytes");

        assertThat(StoreMigration.migrate(store, legacy)).isEqualTo(1);
        assertThat(StoreMigration.migrate(store, legacy)).isZero();
        assertThat(store.resolve("sha256/ab/cd/blob")).exists();
    }

    @Test
    void an_entry_already_in_the_store_is_never_overwritten(@TempDir Path tmp) throws Exception {
        Path legacy = tmp.resolve("cache");
        Path store = tmp.resolve("store");
        seed(legacy, "sha256/ab/cd/blob", "old");
        seed(store, "sha256/ab/cd/blob", "current");

        StoreMigration.migrate(store, legacy);

        assertThat(Files.readString(store.resolve("sha256/ab/cd/blob"))).isEqualTo("current");
    }

    @Test
    void a_straggler_left_behind_is_still_found(@TempDir Path tmp) throws Exception {
        // The half-failed case: sha256 made it across, metadata did not. Re-fetching metadata is what
        // trips Central's per-IP quota, so it has to resolve to where it actually is.
        Path legacy = tmp.resolve("cache");
        Path store = tmp.resolve("store");
        seed(store, "sha256/ab/cd/blob", "bytes");
        seed(legacy, "metadata/deadbeef", "<metadata/>");

        assertThat(StoreMigration.resolveForRead("sha256", store, legacy)).isEqualTo(store.resolve("sha256"));
        assertThat(StoreMigration.resolveForRead("metadata", store, legacy)).isEqualTo(legacy.resolve("metadata"));
    }

    @Test
    void an_entry_in_neither_place_resolves_to_the_new_layout(@TempDir Path tmp) {
        // A caller about to create it should write to store/, not re-establish the old location.
        Path legacy = tmp.resolve("cache");
        Path store = tmp.resolve("store");

        assertThat(StoreMigration.resolveForRead("repos", store, legacy)).isEqualTo(store.resolve("repos"));
    }

    @Test
    void nothing_happens_when_store_and_legacy_are_the_same_directory(@TempDir Path tmp) throws Exception {
        // JK_STORE_DIR pointed at the old cache: moving entries onto themselves would delete them.
        seed(tmp, "sha256/ab/cd/blob", "bytes");

        assertThat(StoreMigration.migrate(tmp, tmp)).isZero();
        assertThat(tmp.resolve("sha256/ab/cd/blob")).exists();
        assertThat(StoreMigration.resolveForRead("sha256", tmp, tmp)).isEqualTo(tmp.resolve("sha256"));
    }

    @Test
    void a_missing_legacy_directory_is_not_an_error(@TempDir Path tmp) {
        assertThat(StoreMigration.migrate(tmp.resolve("store"), tmp.resolve("no-such-cache")))
                .isZero();
    }

    @Test
    void the_store_covers_exactly_the_fetched_set() {
        // A guard on the list itself: adding build-derived state here would make JK_CACHE_DIR stop
        // isolating it, which is the whole point of the split.
        assertThat(StoreMigration.STORE_ENTRIES)
                .containsExactly("sha256", "repos", "metadata", "git", "git-artifacts", "jdks.json")
                .doesNotContain("actions", "runs", "hash-memo", "kotlin-cp-snapshots", "timings.toml");
    }
}
