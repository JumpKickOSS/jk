// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Work the engine has done since it started, counted where it happens: input files fingerprinted
 * (and how many of those the hash memo answered, and how many were read and hashed), Java sources
 * compiled, and action-cache entries restored. The {@code status-ack} carries them as {@code work};
 * a benchmark reads two acks around one build on an engine of its own and takes the difference.
 */
public final class WorkCounters {

    private static final AtomicLong SOURCES_COMPILED = new AtomicLong();
    private static final AtomicLong ACTIONS_RESTORED = new AtomicLong();

    private WorkCounters() {}

    static void compiled(int sources) {
        SOURCES_COMPILED.addAndGet(sources);
    }

    static boolean restored(boolean restored) {
        if (restored) ACTIONS_RESTORED.incrementAndGet();
        return restored;
    }

    /** The {@code work} object of a {@code status-ack}. */
    public static String statusJson() {
        return "{\"filesFingerprinted\":" + FileHashMemo.contentHashInvocations()
                + ",\"memoHits\":" + FileHashMemo.memoHits()
                + ",\"filesHashed\":" + FileHashMemo.contentReads()
                + ",\"javaSourcesCompiled\":" + SOURCES_COMPILED.get()
                + ",\"actionsRestored\":" + ACTIONS_RESTORED.get()
                + "}";
    }
}
