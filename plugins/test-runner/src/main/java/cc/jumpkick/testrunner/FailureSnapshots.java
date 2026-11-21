// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testrunner;

import cc.jumpkick.host.Errors;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * The arguments and locals at the frame that threw a failing test's exception, and at the project
 * frames above it, recorded while the throwing thread stood still ({@link FailureSnapshotProbe}).
 * A failed test's {@code throwable} payload carries the snapshot of its exception as {@code
 * snapshot}: one entry per frame, {@code at} as a stack trace names the frame and {@code vars} as
 * {@code name=value} strings, every one inside the budgets below.
 *
 * <p>Off unless the engine starts this JVM with {@link #PROPERTY} and the JDWP agent; any failure
 * to attach leaves the run as it would have been without it.
 */
final class FailureSnapshots {

    /** The system property the engine sets on a test JVM whose failures carry a snapshot. */
    static final String PROPERTY = "jk.test.failure-snapshot";

    /** Frames one snapshot keeps: the throwing frame and the project frames above it. */
    static final int MAX_FRAMES = 4;

    /** Variables one frame keeps, arguments first. */
    static final int MAX_VARS = 8;

    /** Characters of one rendered value. */
    static final int VALUE_CHARS = 96;

    /** Characters of one frame's variables, together. */
    static final int FRAME_CHARS = 320;

    /**
     * Ends a value cut at {@link #VALUE_CHARS}, before its remainder count; the engine masks a
     * secret the cut split where it dangles before this marker.
     */
    static final String VALUE_TRUNCATION_MARKER = "... (+";

    /** Snapshots held at once; a test's own are the latest, so older ones fall out. */
    static final int KEPT = 64;

    private static volatile @Nullable FailureSnapshots current;

    /** One frame: where, and its variables rendered. */
    record Frame(String at, List<String> vars) {}

    /** One thrown exception as the probe saw it, in the order the probe saw them. */
    record Snapshot(
            long seq,
            String thread,
            String exception,
            @Nullable String message,
            List<Frame> frames) {}

    private final AtomicLong seq = new AtomicLong();
    private final ArrayDeque<Snapshot> kept = new ArrayDeque<>();

    /** The store this JVM records into, or {@code null} when snapshots are off. */
    static @Nullable FailureSnapshots current() {
        return current;
    }

    /**
     * Attach the probe when the engine asked for snapshots. Never throws: a JVM without the JDWP
     * agent or the {@code jdk.jdi} module runs its tests without snapshots, and says so once.
     */
    static void startIfAsked() {
        if (!Boolean.getBoolean(PROPERTY) || current != null) return;
        FailureSnapshots store = new FailureSnapshots();
        try {
            FailureSnapshotProbe.attach(store);
            current = store;
        } catch (Exception | LinkageError e) {
            System.err.println("jk-test-runner: failures carry no snapshot: " + e);
        }
    }

    /** The sequence a test's window starts after: what it throws from now on is newer. */
    long mark() {
        return seq.get();
    }

    /** Record what the probe read; called with the throwing thread suspended. */
    void record(String thread, String exception, @Nullable String message, List<Frame> frames) {
        Snapshot s = new Snapshot(seq.incrementAndGet(), thread, exception, message, List.copyOf(frames));
        synchronized (kept) {
            if (kept.size() == KEPT) kept.removeFirst();
            kept.addLast(s);
        }
    }

    /**
     * The snapshot of {@code failure} as a payload, or {@code null} when none was recorded after
     * {@code since}. The innermost cause that has one wins, since that is where the fault was; of
     * several matching snapshots, the latest on {@code thread} wins, else the latest on any thread.
     */
    @Nullable
    List<Map<String, Object>> payload(Throwable failure, long since, String thread) {
        List<Snapshot> window = new ArrayList<>();
        synchronized (kept) {
            for (Snapshot s : kept) if (s.seq() > since) window.add(s);
        }
        if (window.isEmpty()) return null;
        List<Throwable> chain = Errors.chain(failure);
        // A class that overrides getMessage() differs from the field the probe read; match it by
        // class alone only when no exception of the chain matches on both.
        for (boolean byMessage : new boolean[] {true, false}) {
            for (int i = chain.size() - 1; i >= 0; i--) {
                Snapshot hit = latest(window, chain.get(i), thread, byMessage);
                if (hit != null) return payload(hit);
            }
        }
        return null;
    }

    private static @Nullable Snapshot latest(List<Snapshot> window, Throwable t, String thread, boolean byMessage) {
        Snapshot anyThread = null;
        for (int i = window.size() - 1; i >= 0; i--) {
            Snapshot s = window.get(i);
            if (!s.exception().equals(t.getClass().getName())) continue;
            if (byMessage && !Objects.equals(s.message(), t.getMessage())) continue;
            if (s.thread().equals(thread)) return s;
            if (anyThread == null) anyThread = s;
        }
        return anyThread;
    }

    private static List<Map<String, Object>> payload(Snapshot s) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Frame f : s.frames()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", f.at());
            m.put("vars", f.vars());
            out.add(m);
        }
        return out;
    }

    /** {@code text} cut at {@link #VALUE_CHARS}, the cut marked with its remainder count. */
    static String clip(String text) {
        if (text.length() <= VALUE_CHARS) return text;
        int at = VALUE_CHARS;
        if (Character.isHighSurrogate(text.charAt(at - 1))) at--;
        return text.substring(0, at) + VALUE_TRUNCATION_MARKER + (text.length() - at) + " chars)";
    }
}
