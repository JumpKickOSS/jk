// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.activity;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.changes.Change;
import cc.jumpkick.changes.ChangeJournal;
import cc.jumpkick.engine.notices.Notices;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * New change-journal entries ring the bell once per batch: what was there before the engine started
 * rings nothing, installs ring nothing, and a machine-setup result rings as setup.
 */
class ChangeNoticesTest {

    @TempDir
    Path home;

    private final Notices notices = new Notices();

    private ChangeJournal journal() {
        return new ChangeJournal(home.resolve("changes"));
    }

    @Test
    void entries_before_the_first_poll_ring_nothing_and_a_batch_after_it_rings_once() {
        journal().record("config.toml", Change.EDIT, "set engine.max-heap-mb", null);
        ChangeNotices watcher = new ChangeNotices(this::journal, notices);

        assertThat(watcher.poll()).isZero();
        journal().record("config.toml", Change.EDIT, "added repository corp", null);
        journal().record("credentials", Change.STORE, "stored a login for corp", null);

        assertThat(watcher.poll()).isEqualTo(1);
        assertThat(notices.list()).singleElement().satisfies(n -> {
            assertThat(n.kind()).isEqualTo("change");
            assertThat(n.text()).isEqualTo("JumpKick made 2 changes outside your projects, the last to credentials");
            assertThat(n.href()).isEqualTo("#activity?kind=change");
        });
        assertThat(watcher.poll()).isZero();
    }

    @Test
    void one_change_links_to_itself_installs_ring_nothing_and_setup_rings_as_setup() {
        ChangeNotices watcher = new ChangeNotices(this::journal, notices);
        watcher.poll();

        journal().record("JDK 25", Change.INSTALL, "installed JDK 25", null);
        assertThat(watcher.poll()).isZero();

        Change c =
                journal().record("config.toml", Change.EDIT, "set ui.ask", null).orElseThrow();
        journal().record("Windows host", Change.APPLY, "applied machine setup: 1 change", null);
        assertThat(watcher.poll()).isEqualTo(2);
        assertThat(notices.list())
                .extracting(Notices.Notice::text)
                .containsExactlyInAnyOrder(
                        "JumpKick changed config.toml: set ui.ask", "JumpKick applied machine setup: 1 change");
        assertThat(notices.list())
                .filteredOn(n -> n.kind().equals("change"))
                .singleElement()
                .satisfies(n -> assertThat(n.href()).isEqualTo("#activity/change:" + c.id()));
    }
}
