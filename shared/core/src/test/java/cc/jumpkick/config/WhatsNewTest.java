// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Reading the highlights a release ships, and picking the ones an update moved across. */
class WhatsNewTest {

    private static final String FILE = """
            # What's new in JumpKick

            ## 0.52.0

            - **Faster builds.** Compiles start warm,
              even after a restart.
            - **Smaller downloads.**

            ## 0.51.0

            - **Notes in the app.** You read them here.

            ## 0.50.3

            - **Keychain logins.**
            """;

    @Test
    void every_entry_is_read_with_its_bullets_and_continuations_joined() {
        List<WhatsNew.Entry> entries = WhatsNew.parse(FILE);
        assertThat(entries).extracting(WhatsNew.Entry::version).containsExactly("0.52.0", "0.51.0", "0.50.3");
        assertThat(entries.getFirst().bullets())
                .containsExactly(
                        "**Faster builds.** Compiles start warm, even after a restart.", "**Smaller downloads.**");
    }

    @Test
    void an_update_shows_the_releases_it_moved_across_newest_first() {
        List<WhatsNew.Entry> entries = WhatsNew.parse(FILE);
        assertThat(WhatsNew.between(entries, "0.50.3", "0.52.0"))
                .extracting(WhatsNew.Entry::version)
                .containsExactly("0.52.0", "0.51.0");
        assertThat(WhatsNew.between(entries, "0.51.0", "0.51.0")).isEmpty();
        assertThat(WhatsNew.between(entries, "0.52.0", "0.51.0")).isEmpty();
    }

    @Test
    void the_headline_says_updated_or_went_back() {
        assertThat(WhatsNew.headline("0.50.3", "0.51.0")).isEqualTo("JumpKick was updated to 0.51.0 (from 0.50.3)");
        assertThat(WhatsNew.headline("0.51.0", "0.50.3")).isEqualTo("JumpKick went back to 0.50.3 (from 0.51.0)");
        assertThat(WhatsNew.releaseUrl("0.51.0")).isEqualTo("https://github.com/JumpKickOSS/jk/releases/tag/v0.51.0");
    }
}
