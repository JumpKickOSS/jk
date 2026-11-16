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
            LocalCacheBackend local, String key, String latest, ToolchainPolicy.Expectation expected)
            throws IOException {
        String latestToolchain;
        try {
            latestToolchain = ActionRecordText.parse(latest, key).meta().toolchain();
        } catch (RuntimeException unreadable) {
            return Optional.of(latest);
        }
        String best = expected.accepts(latestToolchain) ? latest : null;
        int bestCloseness = best == null ? Integer.MAX_VALUE : expected.closeness(latestToolchain);
        String offered = latestToolchain;
        for (String toolchain : local.variants(key)) {
            if (bestCloseness == 0) break;
            if (toolchain.equals(latestToolchain) || !expected.accepts(toolchain)) continue;
            int closeness = expected.closeness(toolchain);
            if (closeness >= bestCloseness) continue;
            Optional<String> text = local.variant(key, toolchain);
            if (text.isEmpty()) continue;
            best = text.get();
            bestCloseness = closeness;
        }
        if (best == null) {
            mismatch(key, offered, expected);
            return Optional.empty();
        }
        if (best != latest) ActionRecordText.markRead(local.variantPath(key, chosenToolchain(best, key)));
        return Optional.of(best);
    }

    private static String chosenToolchain(String text, String key) {
        String t = ActionRecordText.parse(text, key).meta().toolchain();
        return t == null ? "" : t;
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
