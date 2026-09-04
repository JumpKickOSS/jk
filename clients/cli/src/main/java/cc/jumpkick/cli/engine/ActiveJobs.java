// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The jids this CLI process has started, noted from {@code job-start} and forgotten on the stream's
 * terminal or a positive cancel ack. {@code LIVE} is process-global on purpose — one set per CLI
 * process — so the Ctrl-C handler can cancel every job this process owns as a best-effort supplement
 * to the dir-scoped cancel, from whatever thread the signal lands on.
 */
public final class ActiveJobs {

    private static final ConcurrentHashMap.KeySetView<Long, Boolean> LIVE = ConcurrentHashMap.newKeySet();

    private ActiveJobs() {}

    public static void note(long jid) {
        if (jid > 0) LIVE.add(jid);
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
