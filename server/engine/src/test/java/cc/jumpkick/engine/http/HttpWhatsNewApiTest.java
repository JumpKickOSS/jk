// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.SelfUpdateState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What the "What's new" dialog is served: the releases the last update moved across. */
class HttpWhatsNewApiTest {

    private static Path notes(Path dir) throws Exception {
        Path file = dir.resolve("whats-new.md");
        Files.writeString(file, """
                ## 0.52.0

                - **Two.**

                ## 0.51.0

                - **One.**

                ## 0.50.3

                - **Zero.**
                """);
        return file;
    }

    @Test
    void after_an_update_it_lists_every_release_moved_across(@TempDir Path dir) throws Exception {
        SelfUpdateState state = SelfUpdateState.EMPTY.withUpdated("0.50.3", "0.52.0", Instant.EPOCH);
        Map<String, Object> body = new HttpWhatsNewApi("0.52.0", () -> state, notes(dir)).body();

        assertThat(body).containsEntry("version", "0.52.0").containsEntry("from", "0.50.3");
        assertThat(body.get("headline")).isEqualTo("JumpKick was updated to 0.52.0 (from 0.50.3)");
        assertThat(body.get("releaseUrl")).isEqualTo("https://github.com/JumpKickOSS/jk/releases/tag/v0.52.0");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) body.get("entries");
        assertThat(entries).extracting(e -> e.get("version")).containsExactly("0.52.0", "0.51.0");
        assertThat(entries).first().satisfies(e -> assertThat(e.get("bullets")).isEqualTo(List.of("**Two.**")));
    }

    @Test
    void with_no_update_to_this_version_it_shows_this_version_alone(@TempDir Path dir) throws Exception {
        Map<String, Object> body = new HttpWhatsNewApi("0.51.0", () -> SelfUpdateState.EMPTY, notes(dir)).body();
        assertThat(body).doesNotContainKey("from").doesNotContainKey("headline");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) body.get("entries");
        assertThat(entries).extracting(e -> e.get("version")).containsExactly("0.51.0");
    }

    @Test
    void no_highlights_file_is_an_empty_list(@TempDir Path dir) {
        Map<String, Object> body =
                new HttpWhatsNewApi("0.51.0", () -> SelfUpdateState.EMPTY, dir.resolve("none.md")).body();
        assertThat((List<?>) body.get("entries")).isEmpty();
    }
}
