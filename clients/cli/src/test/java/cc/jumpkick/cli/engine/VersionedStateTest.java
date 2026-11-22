// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.StoreWipe;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The cache, state and store another jk version wrote are emptied before a cold start; creds,
 * config, trust and what the store's own shelf holds stay.
 */
class VersionedStateTest {

    @TempDir
    Path home;

    private final List<String> log = new ArrayList<>();

    private Path cache() {
        return home.resolve("cache");
    }

    private Path state() {
        return home.resolve("state");
    }

    private Path store() {
        return home.resolve("store");
    }

    private List<VersionedState.Root> roots(String version, int layout) {
        return List.of(
                new VersionedState.Root(
                        "cache", cache(), VersionedState.STAMP, version, VersionedState.Kept.names(Set.of())),
                new VersionedState.Root(
                        "state",
                        state(),
                        VersionedState.STAMP,
                        version,
                        VersionedState.Kept.names(VersionedState.STATE_KEPT)),
                new VersionedState.Root(
                        "store",
                        store(),
                        VersionedState.LAYOUT_STAMP,
                        Integer.toString(layout),
                        dir -> StoreWipe.kept(dir, version)));
    }

    private List<String> claim(String version, boolean engineRunning) throws IOException {
        return claim(version, 1, engineRunning);
    }

    private List<String> claim(String version, int layout, boolean engineRunning) throws IOException {
        return VersionedState.claim(
                roots(version, layout), state().resolve("engine/version.lock"), () -> engineRunning, log::add);
    }

    /** A home an older jk used: cache entries, history, trust, engine files, scratch, store and creds. */
    private void writtenBy(@Nullable String version) throws IOException {
        write(cache().resolve("actions/ab/record"), "old");
        write(state().resolve("builds/1.json"), "old");
        write(state().resolve("test-history/flaky.toml"), "old");
        write(state().resolve("jk-jdks.toml"), "old");
        write(state().resolve("trusted-plugins.toml"), "plugins = [\"com.example:\"]\n");
        write(state().resolve("trusted-sources.toml"), "sources = [\"https://example.com\"]\n");
        write(state().resolve("whats-new.md"), "## 0.55.0\n");
        write(state().resolve("engine/install.lock"), "");
        write(state().resolve("tmp/scratch"), "x");
        write(state().resolve("self-update.toml"), "held = \"0.54.0\"\n");
        write(home.resolve("creds/forge/github.com"), "token");
        write(store().resolve("repos/a.jar"), "jar");
        write(home.resolve("config.toml"), "[engine]\n");
        if (version != null) {
            write(cache().resolve(VersionedState.STAMP), version + "\n");
            write(state().resolve(VersionedState.STAMP), version + "\n");
            write(store().resolve(VersionedState.LAYOUT_STAMP), "1\n");
        }
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    @Test
    void another_versions_cache_and_state_are_emptied_and_creds_trust_store_and_config_stay() throws IOException {
        writtenBy("0.54.0");

        assertThat(claim("0.55.0", false)).containsExactly("cache", "state");

        assertThat(cache()).isDirectory();
        assertThat(cache().resolve("actions")).doesNotExist();
        assertThat(state().resolve("builds")).doesNotExist();
        assertThat(state().resolve("test-history")).doesNotExist();
        assertThat(state().resolve("jk-jdks.toml")).doesNotExist();
        assertThat(state().resolve("trusted-plugins.toml")).hasContent("plugins = [\"com.example:\"]\n");
        assertThat(state().resolve("trusted-sources.toml")).exists();
        assertThat(state().resolve("whats-new.md")).exists();
        assertThat(state().resolve("engine/install.lock")).exists();
        assertThat(state().resolve("tmp/scratch")).exists();
        assertThat(state().resolve("self-update.toml")).exists();
        assertThat(home.resolve("creds/forge/github.com")).hasContent("token");
        assertThat(store().resolve("repos/a.jar")).exists();
        assertThat(home.resolve("config.toml")).exists();
        assertThat(VersionedState.stamp(roots("0.55.0", 1).get(0))).isEqualTo("0.55.0");
        assertThat(VersionedState.stamp(roots("0.55.0", 1).get(1))).isEqualTo("0.55.0");
        assertThat(log).anySatisfy(line -> assertThat(line).contains("names 0.54.0"));
    }

    @Test
    void a_root_with_no_stamp_is_this_versions_and_keeps_what_it_holds() throws IOException {
        writtenBy(null);

        assertThat(claim("0.55.0", false)).isEmpty();

        assertThat(state().resolve("jk-jdks.toml")).exists();
        assertThat(cache().resolve("actions/ab/record")).exists();
        assertThat(store().resolve("repos/a.jar")).exists();
        roots("0.55.0", 1)
                .forEach(root -> assertThat(VersionedState.stamp(root)).isEqualTo(root.stamp()));
    }

    @Test
    void a_store_of_another_layout_is_emptied_but_this_versions_shelf() throws IOException {
        writtenBy("0.55.0");
        Path shelf = store().resolve("repos/jk-local/cc/jumpkick/jk-engine");
        write(shelf.resolve("0.55.0/jk-engine-0.55.0.jar"), "engine");
        write(shelf.resolve("0.54.0/jk-engine-0.54.0.jar"), "older");

        assertThat(claim("0.55.0", 2, false)).containsExactly("store");

        assertThat(store().resolve("repos/a.jar")).doesNotExist();
        assertThat(shelf.resolve("0.54.0")).doesNotExist();
        assertThat(shelf.resolve("0.55.0/jk-engine-0.55.0.jar")).hasContent("engine");
        assertThat(VersionedState.stamp(roots("0.55.0", 2).get(2))).isEqualTo("2");
        assertThat(state().resolve("builds/1.json"))
                .as("state of this version stays")
                .exists();
    }

    @Test
    void nothing_is_emptied_while_an_engine_of_the_home_runs() throws IOException {
        writtenBy("0.54.0");

        assertThat(claim("0.55.0", true)).isEmpty();

        assertThat(cache().resolve("actions/ab/record")).exists();
        assertThat(state().resolve("builds/1.json")).exists();
        assertThat(VersionedState.stamp(roots("0.55.0", 1).get(1))).isEqualTo("0.54.0");
        assertThat(log).singleElement().asString().contains("still runs");
        // The next cold start, with the engine gone, empties them.
        assertThat(claim("0.55.0", false)).containsExactly("cache", "state");
        assertThat(state().resolve("builds")).doesNotExist();
    }

    @Test
    void this_versions_state_is_kept_and_no_engine_is_looked_for() throws IOException {
        writtenBy("0.55.0");

        assertThat(VersionedState.claim(
                        roots("0.55.0", 1),
                        state().resolve("engine/version.lock"),
                        () -> {
                            throw new AssertionError("a stamped home looks for no engine");
                        },
                        log::add))
                .isEmpty();

        assertThat(cache().resolve("actions/ab/record")).exists();
        assertThat(state().resolve("builds/1.json")).exists();
    }

    @Test
    void the_home_keeps_its_engine_files_scratch_trust_and_update_record() {
        var current = VersionedState.current("0.55.0");

        assertThat(current).extracting(VersionedState.Root::name).containsExactly("cache", "state", "store");
        assertThat(VersionedState.STATE_KEPT)
                .contains(
                        "engine",
                        "tmp",
                        "trusted-plugins.toml",
                        "trusted-sources.toml",
                        "self-update.toml",
                        "self-update.toml.lock",
                        "whats-new.md")
                .doesNotContain("jk-jdks.toml");
    }

    @Test
    void a_fresh_home_is_stamped_with_nothing_to_discard() throws IOException {
        write(state().resolve("engine/install.lock"), "");

        assertThat(claim("0.55.0", true)).isEmpty();

        roots("0.55.0", 1)
                .forEach(root -> assertThat(VersionedState.stamp(root)).isEqualTo(root.stamp()));
        assertThat(log).isEmpty();
    }

    @Test
    void the_emptied_roots_read_as_a_list() {
        assertThat(VersionedState.spoken(List.of("cache"))).isEqualTo("cache");
        assertThat(VersionedState.spoken(List.of("cache", "state"))).isEqualTo("cache and state");
        assertThat(VersionedState.spoken(List.of("cache", "state", "store"))).isEqualTo("cache, state and store");
    }
}
