// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The recording seam keeps events only while a test holds it open; a production path noting an
 * event into an empty slot keeps nothing, so nothing accumulates for the engine's lifetime.
 */
class RecordingTest {

    @Test
    void events_are_kept_only_while_a_recording_is_open() {
        AtomicReference<@Nullable Recording<String>> slot = new AtomicReference<>();

        Recording.note(slot, "before");
        assertThat(slot.get()).as("no recording: the slot stays empty").isNull();

        Recording<String> first;
        try (Recording<String> recording = Recording.open(slot)) {
            first = recording;
            Recording.note(slot, "one");
            Recording.note(slot, "two");
            assertThat(recording.events()).containsExactly("one", "two");
        }
        assertThat(slot.get()).as("closing uninstalls the recording").isNull();

        Recording.note(slot, "after");
        assertThat(first.events()).as("a closed recording sees nothing further").containsExactly("one", "two");
    }

    @Test
    void closing_a_superseded_recording_leaves_the_open_one_in_place() {
        AtomicReference<@Nullable Recording<String>> slot = new AtomicReference<>();
        Recording<String> older = Recording.open(slot);
        Recording<String> newer = Recording.open(slot);
        older.close();
        Recording.note(slot, "x");
        assertThat(newer.events()).containsExactly("x");
        assertThat(older.events()).isEmpty();
        newer.close();
        assertThat(slot.get()).isNull();
    }
}
