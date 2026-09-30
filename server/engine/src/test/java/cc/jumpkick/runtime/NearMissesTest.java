// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.library.LibraryCatalog;
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
}
