// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.run.StepCache;
import cc.jumpkick.run.StepScope;
import cc.jumpkick.run.TaskContext;
import org.jspecify.annotations.Nullable;

/**
 * What the action cache did for the step running on this thread, gathered as it happens and told
 * to the step once its scope closes ({@link TaskContext#cache}). A store makes the step a miss
 * whatever it restored before; otherwise a restore, or a marker or verdict the step read and
 * replayed, makes it a hit. Outside a step, nothing is kept.
 */
final class StepCacheNotes {

    private static final StepScope.Local<Note> NOTE = new StepScope.Local<>(Note::new);

    static {
        StepScope.onClose(StepCacheNotes::flush);
    }

    private StepCacheNotes() {}

    /** True when a step is running on this thread, so a note would be kept. */
    static boolean inStep() {
        return StepScope.current() != null;
    }

    /** The step restored {@code key}'s outputs, {@code bytes} of them, from here or the remote tier. */
    static void restored(String key, boolean remote, long bytes, long producerMillis) {
        Note n = StepScope.local(NOTE);
        if (n != null) n.hit(key, remote, bytes, producerMillis);
    }

    /** The step read {@code key}'s marker or verdict, a result in itself. */
    static void replayed(String key, boolean remote, long producerMillis) {
        Note n = StepScope.local(NOTE);
        if (n != null) n.hit(key, remote, 0, producerMillis);
    }

    /** A record under {@code key} was refused as untrusted while the step looked for it. */
    static void refused() {
        Note n = StepScope.local(NOTE);
        if (n != null) n.refused();
    }

    /** Whether the running step has met a refused record. */
    static boolean sawRefusal() {
        Note n = StepScope.local(NOTE);
        return n != null && n.sawRefusal();
    }

    /** The step ran and stored {@code bytes} under {@code key}, for {@code reason}. */
    static void stored(String key, long bytes, StepCache.Reason reason, @Nullable String changed) {
        Note n = StepScope.local(NOTE);
        if (n != null) n.stored(key, bytes, reason, changed);
    }

    private static void flush(TaskContext ctx) {
        Note n = StepScope.local(NOTE);
        StepCache c = n == null ? null : n.toStepCache();
        if (c != null) ctx.cache(c);
    }

    /** One step's notes; steps hand work to pool threads, so every change is under the note's lock. */
    private static final class Note {
        private StepCache.@Nullable Outcome outcome;
        private StepCache.@Nullable Reason reason;
        private @Nullable String changed;
        private @Nullable String key;
        private long bytesIn;
        private long bytesOut;
        private boolean refused;
        private long producerMillis = -1;

        synchronized void hit(String key, boolean remote, long bytes, long producerMillis) {
            bytesIn += bytes;
            if (outcome == StepCache.Outcome.MISS) return;
            // Several records restored by one step each cost their producer this much.
            if (producerMillis >= 0) this.producerMillis = Math.max(0, this.producerMillis) + producerMillis;
            if (outcome != StepCache.Outcome.REMOTE_HIT) {
                outcome = remote ? StepCache.Outcome.REMOTE_HIT : StepCache.Outcome.LOCAL_HIT;
            }
            if (this.key == null) this.key = key;
        }

        synchronized void stored(String key, long bytes, StepCache.Reason reason, @Nullable String changed) {
            bytesOut += bytes;
            if (outcome != StepCache.Outcome.MISS) {
                outcome = StepCache.Outcome.MISS;
                this.key = key;
                this.reason = reason;
                this.changed = changed;
            }
        }

        synchronized void refused() {
            refused = true;
        }

        synchronized boolean sawRefusal() {
            return refused;
        }

        synchronized @Nullable StepCache toStepCache() {
            if (outcome == null) return null;
            long producer = outcome == StepCache.Outcome.MISS ? -1 : producerMillis;
            return new StepCache(outcome, reason, changed, key, bytesIn, bytesOut, producer);
        }
    }
}
