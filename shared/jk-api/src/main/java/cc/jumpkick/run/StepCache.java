// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * What the action cache did for one step: where its result came from, why it ran when it did, the
 * action key, and the bytes it moved. A step that consulted no action cache has none.
 *
 * @param outcome where the step's result came from
 * @param reason why a {@link Outcome#MISS} ran; null for a hit
 * @param changed the inputs a {@link Reason#INPUTS_CHANGED} or {@link Reason#TOOLCHAIN_CHANGED}
 *     names, comma-separated, at most a few; null otherwise
 * @param key the action key the result was restored from or stored under
 * @param bytesIn blob bytes restored into the step's outputs
 * @param bytesOut blob bytes the step stored into the cache
 * @param producerMillis for a hit, the time the run that made the record took, as the record
 *     states it; {@code -1} when it does not say, and for a miss
 */
public record StepCache(
        Outcome outcome,
        @Nullable Reason reason,
        @Nullable String changed,
        @Nullable String key,
        long bytesIn,
        long bytesOut,
        long producerMillis) {

    /** Where a step's result came from. */
    public enum Outcome {
        /** Restored or replayed from this machine's cache. */
        LOCAL_HIT,
        /** Restored or replayed from a record the build's remote cache held. */
        REMOTE_HIT,
        /** Ran, and stored what it made. */
        MISS;

        /** The word the build record and the HTTP API spell. */
        public String word() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    /** Why a step ran: a closed set, so history and insights can count them. */
    public enum Reason {
        /** No record under any earlier key of this step: a first build, or one after a clean cache. */
        NO_RECORD,
        /** A source, dependency or setting the key hashes moved; {@link #changed} names them. */
        INPUTS_CHANGED,
        /** Only the compiler, JDK or jk changed. */
        TOOLCHAIN_CHANGED,
        /** The inputs the record names are the same: something the key hashes beyond them moved. */
        KEY_CHANGED,
        /** A record under this very key was there, but not its whole payload. */
        OUTPUTS_GONE,
        /** A record under this key was refused as untrusted. */
        RECORD_REFUSED,
        /** The build's remote cache stopped answering before this step asked it. */
        REMOTE_UNREACHABLE,
        /** The run bypassed the cache ({@code --force}, {@code --redo}, {@code --rebuild}). */
        FORCED;

        /** The word the build record and the HTTP API spell. */
        public String word() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    /** The outcome {@code word} spells, or null. */
    public static @Nullable Outcome outcome(@Nullable String word) {
        for (Outcome o : Outcome.values()) {
            if (o.word().equals(word)) return o;
        }
        return null;
    }

    /** The reason {@code word} spells, or null. */
    public static @Nullable Reason reason(@Nullable String word) {
        for (Reason r : Reason.values()) {
            if (r.word().equals(word)) return r;
        }
        return null;
    }
}
