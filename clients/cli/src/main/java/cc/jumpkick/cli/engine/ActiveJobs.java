// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongConsumer;
import org.jspecify.annotations.Nullable;

/**
 * The jids this CLI process has started, noted from {@code job-start} and forgotten on the stream's
 * terminal or a positive cancel ack. {@code LIVE} is process-global on purpose — one set per CLI
 * process — so the Ctrl-C handler can cancel every job this process owns as a best-effort supplement
 * to the dir-scoped cancel, from whatever thread the signal lands on.
 */
public final class ActiveJobs {

    private static final ConcurrentHashMap.KeySetView<Long, Boolean> LIVE = ConcurrentHashMap.newKeySet();

    /** Set by {@link #interrupt}; every jid noted afterwards is handed to it on the noting thread. */
    private static volatile @Nullable LongConsumer lateCancel;

    private ActiveJobs() {}

    public static void note(long jid) {
        if (jid <= 0) return;
        LIVE.add(jid);
        LongConsumer cancel = lateCancel;
        if (cancel != null) cancel.accept(jid);
    }

    /**
     * The Ctrl-C hand-off: returns every live jid and passes each jid noted from now on to {@code
     * cancel}. A signal that lands while a {@code job-start} is still in flight finds an empty set;
     * arming before the snapshot means that job is caught by one or the other, never neither.
     */
    public static Set<Long> interrupt(LongConsumer cancel) {
        lateCancel = cancel;
        return snapshot();
    }

    /** Forget every jid and disarm {@link #interrupt}, for tests that share this process. */
    static void reset() {
        lateCancel = null;
        LIVE.clear();
    }

    public static void forget(long jid) {
        LIVE.remove(jid);
    }

    public static void forgetAll() {
        LIVE.clear();
    }

    public static Set<Long> snapshot() {
        return Set.copyOf(LIVE);
    }
}
