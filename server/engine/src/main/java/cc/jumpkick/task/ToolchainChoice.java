// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.ActionRecordText;
import cc.jumpkick.config.RequestScope;
import cc.jumpkick.host.Log;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Which of a key's records a step takes: the latest record ({@code keys/<key>}) and the variants
 * other JDKs made ({@code keys/<key>~<toolchain>}), filtered by the step's {@link
 * ToolchainPolicy.Expectation} and the closest kept. None acceptable is a miss, said once as a note
 * naming the JDKs the records were made on and the two ways out ({@link #mismatch}).
 */
final class ToolchainChoice {

    private ToolchainChoice() {}

    /**
     * The text of the record of {@code key} that {@code expected} takes, preferring the closest;
     * {@code latest} is {@code keys/<key>}. Empty when no record is acceptable. A latest record that
     * does not parse is handed back for the caller's own read to refuse or discard.
     */
    static Optional<String> choose(
            LocalCacheBackend local, String key, String latest, ToolchainPolicy.@Nullable Expectation expected)
            throws IOException {
        if (expected == null) return Optional.of(latest); // no step said: as it was keyed
        String latestToolchain;
        try {
            latestToolchain = ActionRecordText.parse(latest, key).meta().toolchain();
        } catch (RuntimeException unreadable) {
            return Optional.of(latest);
        }
        String best = expected.accepts(latestToolchain) ? latest : null;
        int bestCloseness = best == null ? Integer.MAX_VALUE : expected.closeness(latestToolchain);
        String offered = latestToolchain;
        @Nullable String bestToolchain = null;
        for (String toolchain : local.variants(key)) {
            if (bestCloseness == 0) break;
            if (toolchain.equals(latestToolchain) || !expected.accepts(toolchain)) continue;
            int closeness = expected.closeness(toolchain);
            if (closeness >= bestCloseness) continue;
            Optional<String> text = local.variant(key, toolchain);
            if (text.isEmpty() || !whole(local, key, toolchain, text.get())) continue;
            best = text.get();
            bestCloseness = closeness;
            bestToolchain = toolchain;
        }
        if (best == null) {
            mismatch(key, offered, expected);
            return Optional.empty();
        }
        if (bestToolchain != null) ActionRecordText.markRead(local.variantPath(key, bestToolchain));
        return Optional.of(best);
    }

    /**
     * Whether {@code text}, {@code key}'s variant made by {@code toolchain}, is a record this engine
     * reads; one that is not (cut short, or refused) is a miss and goes, the latest record staying.
     */
    private static boolean whole(LocalCacheBackend local, String key, String toolchain, String text)
            throws IOException {
        try {
            ActionRecordText.parse(text, key);
            return true;
        } catch (RuntimeException unreadable) {
            local.discardVariant(key, toolchain);
            return false;
        }
    }

    /**
     * Why no record of {@code key} serves a reader expecting {@code expected} although one is kept
     * here: the note naming the JDK they were made on and the two ways out. Null when one serves,
     * none is kept, or the records predate a JDK's name.
     */
    static @Nullable String declined(LocalCacheBackend local, String key, ToolchainPolicy.Expectation expected) {
        try {
            Optional<String> latest = local.record(key);
            if (latest.isEmpty()) return null;
            if (choose(local, key, latest.get(), expected).isPresent()) return null;
            return note(ActionRecordText.parse(latest.get(), key).meta().toolchain(), expected);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * Whether {@code expected} refuses a remote record of {@code key} made on {@code offered}: a miss,
     * noted as {@link #mismatch} notes it. False when no step said what it accepts.
     */
    static boolean refuses(String key, @Nullable String offered, ToolchainPolicy.@Nullable Expectation expected) {
        if (expected == null || expected.accepts(offered)) return false;
        mismatch(key, offered, expected);
        return true;
    }

    /**
     * Say where a record taken for {@code key} came from when another JDK made it: {@code made on
     * temurin-25.0.5, linux-x86_64; this build uses openjdk-27.0.1}. Nothing for this JDK's own.
     */
    static void madeOn(String key, ActionRecordText.Meta meta, ToolchainPolicy.Expectation expected) {
        String made = meta.toolchain();
        if (made == null || made.equals(expected.toolchain())) return;
        StepCacheNotes.madeOn(
                key,
                "made on " + ToolchainPolicy.describe(made)
                        + (meta.host() == null ? "" : ", " + meta.host()) + "; this build uses "
                        + ToolchainPolicy.describe(expected.toolchain()));
    }

    /**
     * A record of {@code key} made on {@code offered} that {@code expected} does not take: a miss,
     * and once per JDK pair a note saying what the records were made on, what this build uses and the
     * two ways out (a JDK of that major, or the looser match), never an error.
     */
    static void mismatch(String key, @Nullable String offered, ToolchainPolicy.Expectation expected) {
        String note = note(offered, expected);
        if (note == null) return;
        StepCacheNotes.toolchainMismatch(key, note);
        Set<String> noted = RequestScope.current().get(NOTED, k -> ConcurrentHashMap.<String>newKeySet());
        if (noted.add(note)) Log.info("jk: " + note);
    }

    /** The request-scope key of the notes this request has said. */
    private record Noted() {}

    private static final Noted NOTED = new Noted();

    /**
     * The note for a record made on {@code offered} this build does not take, or null when there is
     * nothing to say (no TOOLCHAIN on the record).
     */
    static @Nullable String note(@Nullable String offered, ToolchainPolicy.Expectation expected) {
        if (offered == null) return null;
        int theirs = ToolchainPolicy.major(offered);
        int mine = ToolchainPolicy.major(expected.toolchain());
        if (expected.requirement() != null
                && expected.requirement().bindsCache()
                && !expected.requirement().admits(offered)) {
            return "cached results here were made on " + ToolchainPolicy.describe(offered)
                    + ", which this project's jdk requirement does not admit";
        }
        if (theirs > 0 && mine > 0 && theirs != mine && theirs >= expected.level()) {
            return "cached results here were made on JDK " + theirs + "; this build uses " + mine
                    + ": jk jdk install temurin-" + theirs + ", or set [cache] jdk-match = \"level\"";
        }
        if (expected.match() == ToolchainPolicy.Match.BUILD) {
            return "cached results here were made on " + ToolchainPolicy.describe(offered) + "; this build uses "
                    + ToolchainPolicy.describe(expected.toolchain()) + " and [cache] jdk-match is \"build\"";
        }
        return null;
    }
}
