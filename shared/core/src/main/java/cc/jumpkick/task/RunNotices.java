// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Log;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * A note a run makes at most once, however many times the code that noticed it runs.
 *
 * <p>Some facts are discovered by code that executes many times per build and are worth saying
 * once: a repository URL carrying a credential is noticed every time the repository group is
 * rebuilt, which is once per module per planner. Without an owner each such site grows its own
 * {@code static Set<String> WARNED} plus a package-private {@code reset} for its tests, and the
 * bookkeeping is easy to forget — two neighbouring warnings in the same class had one copy between
 * them, so one said its piece once and the other said it once per call, from twenty call sites,
 * several of them per module.
 *
 * <h2>"Once" means once per run, not once per process</h2>
 *
 * A {@code static boolean} means once per process, and the engine is a resident daemon that serves
 * build after build. A warning armed with one fires on the first build after the engine starts and
 * is silent for every build after it — the user who reads the note, fixes nothing, and rebuilds
 * gets silence and reads it as agreement. So the scope has to be the run.
 *
 * <p>A run is identified by its {@link IoLedger}. That is not a convenient coincidence: the ledger
 * is the one object the engine already opens and closes around a request
 * ({@link IoLedger#open}/{@link IoLedger#close}), and every {@code Session} copy derived inside the
 * request — including work handed to the shared pools — carries the same instance. {@code Session}
 * itself cannot serve: it is a record, copied freely by every {@code with*} call, so its identity
 * changes several times inside one build while the ledger's does not.
 *
 * <p>Bounded on both axes and dropped whole when full, like the other run-scoped tables: the cost
 * of over-flowing is that one note may be said twice, which is the failure mode a diagnostic should
 * pick.
 *
 * <h2>Where a note goes</h2>
 *
 * To the run's registered {@link Sink} when the engine opened one, and to the process
 * {@link Log} at warn otherwise. The engine opens a sink around every enveloped request — registered beside {@link
 * IoLedger#open} and removed in the same {@code finally} — and its sink turns a note into a WARN
 * wire line the CLI already prints, so the warning reaches the user who can act on it rather than
 * only the engine log. The log remains for everything outside a request: engine housekeeping
 * and tests.
 */
public final class RunNotices {

    /** Where a run's notices go instead of standard error; {@code code} is the notice key. */
    @FunctionalInterface
    public interface Sink {
        void notice(String code, String message);
    }

    /** Keyed by the run's ledger — see the class note on why the session cannot serve. */
    private static final ConcurrentHashMap<IoLedger, Set<String>> BY_RUN = new ConcurrentHashMap<>();

    /** Same key as {@link #BY_RUN}: the ledger is the run's identity. */
    private static final ConcurrentHashMap<IoLedger, Sink> SINKS = new ConcurrentHashMap<>();

    private static final int MAX_RUNS = 32;
    private static final int MAX_PER_RUN = 64;

    private RunNotices() {}

    /** Route this run's notices to {@code sink} until {@link #closeSink}. */
    public static void openSink(IoLedger run, Sink sink) {
        if (run == null || sink == null) return;
        if (SINKS.size() > MAX_RUNS) SINKS.clear(); // a dropped sink degrades to stderr, no worse
        SINKS.put(run, sink);
    }

    /** Stop routing this run's notices; later notes fall back to the log. */
    public static void closeSink(IoLedger run) {
        if (run == null) return;
        SINKS.remove(run);
    }

    /**
     * Say {@code message} if this run has not already said {@code key}; otherwise do nothing. The
     * note goes to the run's {@link Sink} when one is open, else to the log.
     *
     * <p>{@code message} is a supplier so a repeat costs nothing to build — the caller that has
     * already decided it has something to say is usually the one doing the formatting work.
     *
     * <p>Never throws. A diagnostic that fails a build is worse than a diagnostic nobody sees.
     */
    public static void warnOnce(String key, Supplier<String> message) {
        if (key == null || message == null) return;
        try {
            IoLedger run = SessionContext.current().io(); // never null: Session's constructor sees to it
            if (!claim(run, key)) return;
            String text = message.get();
            if (text == null || text.isEmpty()) return;
            Sink sink = SINKS.get(run);
            if (sink != null) sink.notice(key, text);
            else Log.warn(text);
        } catch (RuntimeException e) {
            // Unreadable session, unwritable log, throwing sink: say nothing rather than fail
            // the build.
            Log.debug("warnOnce: Unreadable session, unwritable log, throwing sink", e);
        }
    }

    /** True the first time this run claims {@code key}, false every time after. */
    private static boolean claim(IoLedger run, String key) {
        if (BY_RUN.size() > MAX_RUNS) BY_RUN.clear();
        Set<String> said = BY_RUN.computeIfAbsent(run, r -> ConcurrentHashMap.newKeySet());
        if (said.size() >= MAX_PER_RUN) said.clear(); // over-full: at worst one note is said twice
        return said.add(key);
    }

    /** Forget every claim and every sink. Nothing on the build path needs this; tests do. */
    public static void clear() {
        BY_RUN.clear();
        SINKS.clear();
    }
}
