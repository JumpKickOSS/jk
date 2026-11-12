// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Every in-process memo that can give its entries back, joined when the memo is made, so the idle
 * trim finds each one without a hand-kept list. Held weakly: a memo a test made and dropped leaves
 * with its last reference; a memo in a static field stays for the process.
 */
public final class TrimRegistry {

    /** A memo that can drop what it holds. */
    public interface Trimmable {

        /**
         * Drop every entry not about {@code keep}, or every entry when {@code keep} is null, and
         * say how many went.
         */
        int trimOutside(@Nullable Path keep);
    }

    private static final Set<Trimmable> MEMOS =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private TrimRegistry() {}

    /** Join {@code memo}: the next {@link #trimAll} trims it. */
    public static void join(Trimmable memo) {
        MEMOS.add(memo);
    }

    /** Trim every joined memo to {@code keep} (all of it when null); how many entries went in all. */
    public static int trimAll(@Nullable Path keep) {
        List<Trimmable> all;
        synchronized (MEMOS) {
            all = new ArrayList<>(MEMOS);
        }
        int dropped = 0;
        for (Trimmable m : all) dropped += m.trimOutside(keep);
        return dropped;
    }

    /** Whether {@code memo} has joined. Test seam. */
    static boolean joined(Trimmable memo) {
        return MEMOS.contains(memo);
    }
}
