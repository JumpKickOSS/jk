// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoreWipeTest {

    private static final String VERSION = "0.52.1-SNAPSHOT";

    private static Path write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, text);
    }

    @Test
    void a_wipe_keeps_this_versions_first_party_shelf_and_takes_everything_else(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        Path shelf = store.resolve("repos/jk-local/cc/jumpkick");
        Path intellij = write(shelf.resolve("jk-intellij/" + VERSION + "/jk-intellij-" + VERSION + ".jar"), "jar");
        Path memo = write(shelf.resolve("jk-intellij/" + VERSION + "/jk-intellij-" + VERSION + ".jar.jk"), "memo");
        Path host = write(shelf.resolve("jk-host/" + VERSION + "/jk-host-" + VERSION + ".pom"), "pom");
        Path older = write(shelf.resolve("jk-intellij/0.51.1/jk-intellij-0.51.1.jar"), "old");
        Path project = write(store.resolve("repos/jk-local/com/example/app/1.0/app-1.0.jar"), "app");
        Path central = write(store.resolve("repos/central/org/x/x/1/x-1.jar"), "x");
        Path blob = write(store.resolve("sha256/ab/abcd"), "blob");
        Path completions = write(store.resolve("completions/zsh"), "#compdef jk");

        assertThat(StoreWipe.kept(store, VERSION))
                .containsExactly(shelf.resolve("jk-host/" + VERSION), shelf.resolve("jk-intellij/" + VERSION));

        // What the confirm counts is what goes.
        DiskUsage.Stats measured = StoreWipe.measure(store, VERSION);
        StoreWipe.wipe(store, VERSION);

        assertThat(intellij).as("the jar the engine launches its plugin from").hasContent("jar");
        assertThat(memo).as("the memo the resolver reads beside it").exists();
        assertThat(host).exists();
        assertThat(older).as("another version can be fetched or reinstalled").doesNotExist();
        assertThat(shelf.resolve("jk-intellij/0.51.1")).doesNotExist();
        assertThat(project).doesNotExist();
        assertThat(store.resolve("repos/jk-local/com")).doesNotExist();
        assertThat(central).doesNotExist();
        assertThat(store.resolve("repos/central")).doesNotExist();
        assertThat(blob).doesNotExist();
        assertThat(store.resolve("sha256")).doesNotExist();
        assertThat(completions).doesNotExist();
        assertThat(measured.files())
                .as("old jar, app, central, blob, completions")
                .isEqualTo(5);
    }

    @Test
    void a_store_without_this_versions_shelf_goes_as_a_directory(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        write(store.resolve("repos/jk-local/cc/jumpkick/jk-intellij/0.51.1/jk-intellij-0.51.1.jar"), "old");
        write(store.resolve("sha256/ab/abcd"), "blob");

        assertThat(StoreWipe.doomed(store, StoreWipe.kept(store, VERSION))).containsExactly(store);
        StoreWipe.wipe(store, VERSION);
        assertThat(store).doesNotExist();
    }

    @Test
    void a_missing_store_has_nothing_to_wipe(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store");
        assertThat(StoreWipe.kept(store, VERSION)).isEmpty();
        assertThat(StoreWipe.doomed(store, List.of())).isEmpty();
    }
}
