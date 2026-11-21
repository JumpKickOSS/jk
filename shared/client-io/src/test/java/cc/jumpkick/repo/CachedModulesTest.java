// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CachedModulesTest {

    private static void cache(Path store, String rel) throws IOException {
        Path file = store.resolve("repos").resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "x");
    }

    private static Path store(Path dir) throws IOException {
        Path store = dir.resolve("store");
        cache(store, "central/g/a/1/a-1.jar");
        cache(store, "jk-local/g/a/2/a-2.jar");
        cache(store, "jk-local/org/x/b/3/b-3.jar");
        // Only bookkeeping: no version of g:c is held.
        cache(store, "central/g/c/1/c-1.jar.sha256");
        // A legacy name-keyed tree is read by nothing.
        cache(store, "private/g/z/1/z-1.jar");
        return store;
    }

    @Test
    void every_known_store_is_merged_by_module_key(@TempDir Path dir) throws IOException {
        Path store = store(dir);

        assertThat(CachedModules.allModules(store))
                .extracting(RepoArtifactStore.Module::moduleKey)
                .containsExactlyInAnyOrder("g:a", "org.x:b");
        assertThat(CachedModules.allModules(store))
                .filteredOn(m -> m.moduleKey().equals("g:a"))
                .singleElement()
                .satisfies(m -> assertThat(m.versions()).containsExactlyInAnyOrder("1", "2"));
        assertThat(CachedModules.allVersions(store, "g", "a")).containsExactlyInAnyOrder("1", "2");
        assertThat(CachedModules.allVersions(store, "g", "z")).isEmpty();
    }

    @Test
    void module_keys_list_only_the_directories_the_filter_admits(@TempDir Path dir) throws IOException {
        Path store = store(dir);
        List<Path> listed = new ArrayList<>();
        CachedModules.ModuleFilter onlyG = new CachedModules.ModuleFilter() {
            @Override
            public boolean mayHold(String groupPrefix) {
                return groupPrefix.equals("g");
            }

            @Override
            public boolean wants(String group, String artifact) {
                return group.equals("g");
            }

            @Override
            public void listing(Path dir) {
                listed.add(dir);
            }
        };

        assertThat(CachedModules.moduleKeys(store, onlyG)).containsExactly("g:a");
        assertThat(listed).noneMatch(p -> p.getFileName().toString().equals("x"));
    }
}
