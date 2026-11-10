// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.notices;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.SelfUpdateState;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The bell says "JumpKick was updated" once per install, from the first engine of that version. */
class UpdatedNoticeTest {

    @Test
    void the_first_engine_of_the_installed_version_raises_it_once(@TempDir Path dir) throws Exception {
        Path state = dir.resolve("self-update.toml");
        SelfUpdateState.update(state, s -> s.withUpdated("0.50.3", "0.51.0", Instant.EPOCH));
        Notices notices = new Notices();

        assertThat(UpdatedNotice.announce(notices, "0.51.0", state)).isTrue();
        assertThat(notices.list()).singleElement().satisfies(n -> {
            assertThat(n.kind()).isEqualTo(UpdatedNotice.KIND);
            assertThat(n.text()).isEqualTo("JumpKick was updated to 0.51.0 (from 0.50.3)");
            assertThat(n.href()).isEqualTo("#whats-new");
        });

        // A restart of the same engine does not say it again.
        assertThat(UpdatedNotice.announce(notices, "0.51.0", state)).isFalse();
        assertThat(notices.list()).hasSize(1);
    }

    @Test
    void an_engine_of_another_version_raises_nothing(@TempDir Path dir) throws Exception {
        Path state = dir.resolve("self-update.toml");
        Notices notices = new Notices();
        assertThat(UpdatedNotice.announce(notices, "0.51.0", state)).isFalse();
        SelfUpdateState.update(state, s -> s.withUpdated("0.50.3", "0.51.0", Instant.EPOCH));
        assertThat(UpdatedNotice.announce(notices, "0.52.0-SNAPSHOT", state)).isFalse();
        assertThat(notices.list()).isEmpty();
    }
}
