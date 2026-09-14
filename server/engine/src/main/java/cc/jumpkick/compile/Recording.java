// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * A test's window onto events a production path otherwise keeps nowhere. {@link #open} installs
 * the window in a slot the path consults, every event noted while it is open is kept, and closing
 * it uninstalls it. With no window open {@link #note} keeps nothing, so a resident engine carries
 * no ledger of the forks and warm-ups it has made across days of builds: the only state is the
 * slot, and it is empty.
 */
public final class Recording<T> implements AutoCloseable {

    private final AtomicReference<@Nullable Recording<T>> slot;
    private final List<T> events = Collections.synchronizedList(new ArrayList<>());

    private Recording(AtomicReference<@Nullable Recording<T>> slot) {
        this.slot = slot;
    }

    /** Opens a recording in {@code slot}, taking over from any that is open there. */
    public static <T> Recording<T> open(AtomicReference<@Nullable Recording<T>> slot) {
        Recording<T> recording = new Recording<>(slot);
        slot.set(recording);
        return recording;
    }

    /** Hands {@code event} to the recording open in {@code slot}; a no-op when none is. */
    public static <T> void note(AtomicReference<@Nullable Recording<T>> slot, T event) {
        Recording<T> recording = slot.get();
        if (recording != null) recording.events.add(event);
    }

    /** Everything noted since this recording opened, oldest first. */
    public List<T> events() {
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    /** Uninstalls this recording; a later one already open in the slot stays. */
    @Override
    public void close() {
        slot.compareAndSet(this, null);
    }
}
