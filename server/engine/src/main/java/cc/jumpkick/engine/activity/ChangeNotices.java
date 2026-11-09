// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.activity;

import cc.jumpkick.changes.Change;
import cc.jumpkick.changes.ChangeJournal;
import cc.jumpkick.engine.notices.Notices;
import cc.jumpkick.host.Log;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Puts the change journal's new entries on the bell: one notice per batch the watcher finds, not
 * one per line, so a {@code jk self nuke} or an import that edits several files rings once. The
 * client and the engine both append to the journal, so the engine learns of new lines by watching
 * the file, one stat every {@link #EVERY}.
 *
 * <p>Installs and uninstalls stay off the bell: a build that fetches a JDK or a tool does so as a
 * matter of course, and a notice for each would bury the ones that matter. They are in the
 * Activity page under {@code install}. A machine-setup result is a notice of its own kind, linking
 * to the setup filter.
 */
public final class ChangeNotices implements AutoCloseable {

    /** How often the journal file is checked. */
    public static final Duration EVERY = Duration.ofSeconds(5);

    private final Supplier<ChangeJournal> journal;
    private final Notices notices;
    private @Nullable ScheduledExecutorService scheduler;
    private long stamp = -1;
    private @Nullable String newest;

    public ChangeNotices(Supplier<ChangeJournal> journal, Notices notices) {
        this.journal = Objects.requireNonNull(journal);
        this.notices = Objects.requireNonNull(notices);
    }

    /** Notes where the journal stands, so only entries after now ring, and checks it every {@link #EVERY}. */
    public synchronized void start() {
        poll();
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("jk-change-notices").factory());
        s.scheduleWithFixedDelay(this::pollQuietly, EVERY.toMillis(), EVERY.toMillis(), TimeUnit.MILLISECONDS);
        scheduler = s;
    }

    @Override
    public synchronized void close() {
        ScheduledExecutorService s = scheduler;
        if (s != null) s.shutdownNow();
        scheduler = null;
    }

    private void pollQuietly() {
        try {
            poll();
        } catch (RuntimeException e) {
            Log.debug("change notices: poll failed", e);
        }
    }

    /**
     * Adds the notices for the entries appended since the last poll and answers how many it added.
     * The first poll only notes where the journal stands.
     */
    synchronized int poll() {
        ChangeJournal j = journal.get();
        long now = j.stamp();
        if (now == stamp) return 0;
        boolean first = stamp < 0;
        stamp = now;
        List<Change> fresh = new ArrayList<>();
        for (Change c : j.newestFirst()) {
            if (newest != null && c.id().compareTo(newest) <= 0) break;
            fresh.add(c);
        }
        if (!fresh.isEmpty()) newest = fresh.getFirst().id();
        if (first || newest == null) return 0;
        int added = 0;
        List<Change> setup =
                fresh.stream().filter(c -> Change.APPLY.equals(c.action())).toList();
        if (!setup.isEmpty()) {
            notices.add(Activity.SETUP, setupText(setup.getFirst()), "#activity?kind=setup", null);
            added++;
        }
        List<Change> changes = fresh.stream()
                .filter(c -> !Change.APPLY.equals(c.action())
                        && !Change.INSTALL.equals(c.action())
                        && !Change.UNINSTALL.equals(c.action()))
                .toList();
        if (!changes.isEmpty()) {
            String href = changes.size() == 1
                    ? "#activity/change:" + changes.getFirst().id()
                    : "#activity?kind=change";
            notices.add(Activity.CHANGE, changeText(changes), href, null);
            added++;
        }
        return added;
    }

    /** "JumpKick applied machine setup: 2 changes". */
    static String setupText(Change c) {
        return "JumpKick " + c.summary();
    }

    /** One change says what it was; several say how many, newest first. */
    static String changeText(List<Change> newestFirst) {
        Change top = newestFirst.getFirst();
        if (newestFirst.size() == 1) return "JumpKick changed " + top.what() + ": " + top.summary();
        return "JumpKick made " + newestFirst.size() + " changes outside your projects, the last to " + top.what();
    }
}
