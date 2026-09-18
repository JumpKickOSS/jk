// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * One instance per distinct short string the engine repeats: Maven coordinates, versions, scopes,
 * property names. A thousand-package lock reads thousands of POMs that spell the same group ids,
 * versions and {@code test} scopes, and every memoized POM would otherwise carry its own copy of
 * each — more than half the string bytes of a large lock's live heap.
 *
 * <p>The table is process-wide and bounded: past {@value #CAPACITY} entries it starts over, so a
 * long-lived engine never retains an unbounded set. A string longer than {@value #MAX_LENGTH} is
 * returned as it is; nothing that long repeats enough to be worth a table slot.
 */
public final class Interned {

    static final int CAPACITY = 1 << 17;
    static final int MAX_LENGTH = 200;

    private static final ConcurrentHashMap<String, String> TABLE = new ConcurrentHashMap<>();

    private Interned() {}

    /** The shared instance equal to {@code s}. */
    public static String of(String s) {
        if (s.length() > MAX_LENGTH) return s;
        String shared = TABLE.get(s);
        if (shared != null) return shared;
        if (TABLE.size() >= CAPACITY) TABLE.clear();
        shared = TABLE.putIfAbsent(s, s);
        return shared != null ? shared : s;
    }

    /** {@link #of}, passing {@code null} through. */
    public static @Nullable String ofNullable(@Nullable String s) {
        return s == null ? null : of(s);
    }

    /**
     * Drop every entry and return how many went. A string still referenced from a live object stays
     * alive on its own; the table only stops keeping the rest. For the idle engine.
     */
    public static int dropAll() {
        int dropped = TABLE.size();
        TABLE.clear();
        return dropped;
    }

    /** How many distinct strings the table holds right now. */
    static int size() {
        return TABLE.size();
    }
}
