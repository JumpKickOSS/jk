// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.library.LibraryCatalog;
import cc.jumpkick.repo.RepoArtifactStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A mistyped coordinate names the module the store or catalog knows under a near group. */
class NearMissesTest {

    @Test
    void a_group_that_differs_in_punctuation_or_a_character_is_a_near_miss() {
        List<String> known = List.of(
                "com.ninja-squad:springmockk",
                "io.mockk:mockk",
                "org.other:springmockk",
                "com.ninjasquad:springmockk-core",
                "com.ninjasquad:spring-mockk");
        assertThat(NearMisses.among("com.ninjasquad", "springmockk", known))
                .containsExactly("com.ninja-squad:springmockk", "com.ninjasquad:spring-mockk");
        assertThat(NearMisses.among("io.mock", "mockk", known)).containsExactly("io.mockk:mockk");
        assertThat(NearMisses.among("io.mockk", "mockk", known))
                .as("the coordinate itself is not its own near miss")
                .isEmpty();
    }

    @Test
    void the_local_store_is_searched_by_artifact(@TempDir Path store) throws Exception {
        Files.createDirectories(store.resolve("repos/central/com/ninja-squad/springmockk/4.0.2"));
        Files.writeString(
                store.resolve("repos/central/com/ninja-squad/springmockk/4.0.2/springmockk-4.0.2.pom"), "<project/>");
        assertThat(NearMisses.of("com.ninjasquad", "springmockk", store, LibraryCatalog.of(Map.of())))
                .containsExactly("com.ninja-squad:springmockk");
    }

    @Test
    void the_store_answers_what_a_listing_of_every_module_answers(@TempDir Path store) throws Exception {
        Path central = store.resolve("repos/central");
        Path google = store.resolve("repos/google");
        module(central, "com.ninja-squad", "springmockk", "springmockk-4.0.2.pom");
        module(central, "com.ninjasquad", "spring-mockk", "spring-mockk-1.0.jar");
        module(central, "com.ninjasquad", "springmockk-core", "springmockk-core-1.0.pom");
        module(google, "com.ninja.squad", "springmockk", "springmockk-1.0.pom");
        module(google, "com.ninja-squad", "springmockk", "springmockk-4.0.2.pom");
        module(central, "io.mockk", "mockk", "mockk-1.0.pom");
        module(central, "io.mockk.extra", "mockk", "mockk-1.0.pom");
        module(central, "io.mock-k", "Mockk", "mockk-1.0.pom");
        module(central, "org.other", "springmockk", "springmockk-1.0.pom");
        module(central, "com.ninjasquadd", "spring_mockk", "x.sha256");
        Files.createDirectories(central.resolve(".access/com/ninjasquad/springmockk/1.0"));
        Files.writeString(central.resolve(".access/com/ninjasquad/springmockk/1.0/springmockk-1.0.pom"), "");
        for (int i = 0; i < 40; i++) module(central, "org.filler" + i, "lib" + i, "lib-1.0.pom");

        List<String> everyModule = RepoArtifactStore.allModules(store).stream()
                .map(RepoArtifactStore.Module::moduleKey)
                .toList();
        LibraryCatalog none = LibraryCatalog.of(Map.of());
        for (String[] ask : List.of(
                new String[] {"com.ninjasquad", "springmockk"},
                new String[] {"com.ninja-squad", "spring-mockk"},
                new String[] {"io.mock", "mockk"},
                new String[] {"io.mockk", "mockk"},
                new String[] {"io", "mockk"},
                new String[] {"org.filler7", "lib7"},
                new String[] {"net.nothing", "nothing"})) {
            assertThat(NearMisses.of(ask[0], ask[1], store, none))
                    .as(ask[0] + ":" + ask[1])
                    .isEqualTo(NearMisses.among(ask[0], ask[1], everyModule));
        }
        assertThat(NearMisses.of("com.ninjasquad", "springmockk", store, none))
                .as("the fixture has near misses to agree on")
                .containsExactly("com.ninja-squad:springmockk", "com.ninjasquad:spring-mockk");
    }

    @Test
    void a_large_store_is_answered_from_a_few_directory_listings(@TempDir Path store) throws Exception {
        Path central = store.resolve("repos/central");
        for (int g = 0; g < 250; g++) {
            for (int a = 0; a < 10; a++) {
                for (int v = 0; v < 2; v++) {
                    Path dir = Files.createDirectories(central.resolve("org/g" + g + "/sub/a" + a + "/1." + v));
                    Files.writeString(dir.resolve("a" + a + "-1." + v + ".pom"), "<project/>");
                    Files.writeString(dir.resolve("a" + a + "-1." + v + ".pom.jk"), "");
                }
            }
        }
        module(central, "com.ninja-squad", "springmockk", "springmockk-4.0.2.pom");

        Listings pruned = new Listings(NearMisses.near("com.ninjasquad", "springmockk"), false);
        List<String> near = RepoArtifactStore.moduleKeys(store, pruned);
        Listings everything = new Listings(NearMisses.near("com.ninjasquad", "springmockk"), true);
        RepoArtifactStore.moduleKeys(store, everything);

        assertThat(near).containsExactly("com.ninja-squad:springmockk");
        assertThat(NearMisses.of("com.ninjasquad", "springmockk", store, LibraryCatalog.of(Map.of())))
                .isEqualTo(near);
        assertThat(pruned.opened).as("directories the lookup lists").isLessThanOrEqualTo(12);
        assertThat(everything.opened)
                .as("directories a walk of every module lists")
                .isGreaterThan(100 * pruned.opened);
    }

    /** Counts the directories one walk lists; {@code everything} admits every directory, as a full listing does. */
    private static final class Listings implements RepoArtifactStore.ModuleFilter {
        private final RepoArtifactStore.ModuleFilter filter;
        private final boolean everything;
        int opened;

        Listings(RepoArtifactStore.ModuleFilter filter, boolean everything) {
            this.filter = filter;
            this.everything = everything;
        }

        @Override
        public boolean mayHold(String groupPrefix) {
            return everything || filter.mayHold(groupPrefix);
        }

        @Override
        public boolean wants(String group, String artifact) {
            return everything || filter.wants(group, artifact);
        }

        @Override
        public void listing(Path dir) {
            opened++;
        }
    }

    private static void module(Path tree, String group, String artifact, String file) throws IOException {
        Path dir = Files.createDirectories(
                tree.resolve(group.replace('.', '/')).resolve(artifact).resolve("1.0"));
        Files.writeString(dir.resolve(file), "");
    }
}
