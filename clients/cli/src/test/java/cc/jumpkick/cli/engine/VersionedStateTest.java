// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The cache and state another jk version wrote are emptied before a cold start, and creds stay. */
class VersionedStateTest {

    @TempDir
    Path home;

    private Path cache;
    private Path state;
    private List<VersionedState.Root> roots;
    private final List<String> log = new ArrayList<>();

    @BeforeEach
    void layout() {
        cache = home.resolve("cache");
        state = home.resolve("state");
        roots = List.of(
                new VersionedState.Root(cache, Set.of()),
                new VersionedState.Root(state, Set.of("engine", "tmp", "self-update.toml")));
    }

    private List<String> claim(String version, boolean engineRunning) throws IOException {
        return VersionedState.claim(
                roots, version, state.resolve("engine").resolve("version.lock"), () -> engineRunning, log::add);
    }

    /** A home an older jk used: cache entries, build history, engine files, scratch and creds. */
    private void writtenBy(@Nullable String version) throws IOException {
        write(cache.resolve("actions/ab/record"), "old");
        write(state.resolve("builds/1.json"), "old");
        write(state.resolve("test-history/flaky.toml"), "old");
        write(state.resolve("engine/install.lock"), "");
        write(state.resolve("tmp/scratch"), "x");
        write(state.resolve("self-update.toml"), "held = \"0.54.0\"\n");
        write(home.resolve("creds/forge/github.com"), "token");
        write(home.resolve("store/repos/a.jar"), "jar");
        write(home.resolve("config.toml"), "[engine]\n");
        if (version != null) {
            write(cache.resolve(VersionedState.STAMP), version + "\n");
            write(state.resolve(VersionedState.STAMP), version + "\n");
        }
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    @Test
    void another_versions_cache_and_state_are_emptied_and_creds_store_and_config_stay() throws IOException {
        writtenBy("0.54.0");

        assertThat(claim("0.55.0", false)).containsExactly("jk 0.54.0");

        assertThat(cache).isDirectory();
        assertThat(cache.resolve("actions")).doesNotExist();
        assertThat(state.resolve("builds")).doesNotExist();
        assertThat(state.resolve("test-history")).doesNotExist();
        assertThat(state.resolve("engine/install.lock")).exists();
        assertThat(state.resolve("tmp/scratch")).exists();
        assertThat(state.resolve("self-update.toml")).exists();
        assertThat(home.resolve("creds/forge/github.com")).hasContent("token");
        assertThat(home.resolve("store/repos/a.jar")).exists();
        assertThat(home.resolve("config.toml")).exists();
        assertThat(VersionedState.stamp(cache)).isEqualTo("0.55.0");
        assertThat(VersionedState.stamp(state)).isEqualTo("0.55.0");
    }

    @Test
    void state_with_no_stamp_is_another_versions() throws IOException {
        writtenBy(null);

        assertThat(claim("0.55.0", false)).containsExactly("an older jk");

        assertThat(state.resolve("builds")).doesNotExist();
        assertThat(VersionedState.stamp(state)).isEqualTo("0.55.0");
    }

    @Test
    void nothing_is_emptied_while_an_engine_of_the_home_runs() throws IOException {
        writtenBy("0.54.0");

        assertThat(claim("0.55.0", true)).isEmpty();

        assertThat(cache.resolve("actions/ab/record")).exists();
        assertThat(state.resolve("builds/1.json")).exists();
        assertThat(VersionedState.stamp(state)).isEqualTo("0.54.0");
        assertThat(log).singleElement().asString().contains("still runs");
        // The next cold start, with the engine gone, empties them.
        assertThat(claim("0.55.0", false)).containsExactly("jk 0.54.0");
        assertThat(state.resolve("builds")).doesNotExist();
    }

    @Test
    void this_versions_state_is_kept_and_no_engine_is_looked_for() throws IOException {
        writtenBy("0.55.0");

        assertThat(VersionedState.claim(
                        roots,
                        "0.55.0",
                        state.resolve("engine/version.lock"),
                        () -> {
                            throw new AssertionError("a stamped home looks for no engine");
                        },
                        log::add))
                .isEmpty();

        assertThat(cache.resolve("actions/ab/record")).exists();
        assertThat(state.resolve("builds/1.json")).exists();
    }

    @Test
    void the_home_keeps_its_engine_files_scratch_and_self_update_record() {
        var state = VersionedState.current().get(1);

        assertThat(state.keep()).contains("engine", "tmp", "self-update.toml", "self-update.toml.lock");
        assertThat(VersionedState.current().get(0).keep()).isEmpty();
    }

    @Test
    void a_fresh_home_is_stamped_with_nothing_to_discard() throws IOException {
        write(state.resolve("engine/install.lock"), "");

        assertThat(claim("0.55.0", true)).isEmpty();

        assertThat(VersionedState.stamp(cache)).isEqualTo("0.55.0");
        assertThat(VersionedState.stamp(state)).isEqualTo("0.55.0");
        assertThat(log).isEmpty();
    }
}
