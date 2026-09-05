// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.args;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Unique-prefix resolution for commands, subcommands, and long options: exact match wins, else a
 * unique prefix, else ambiguous/none. Alias names that bind the same target collapse by identity.
 */
public final class Abbreviations {

    private Abbreviations() {}

    public enum Kind {
        EXACT,
        UNIQUE_PREFIX,
        AMBIGUOUS,
        NONE
    }

    /**
     * @param value the resolved target for {@link Kind#EXACT}/{@link Kind#UNIQUE_PREFIX}, else null
     * @param candidates the matching names (sorted) — one for an exact hit, all prefix matches for
     *     an ambiguous one, empty for none
     */
    public record Result<T>(Kind kind, @Nullable T value, List<String> candidates) {
        /** True when a single target was selected (exact or unique prefix). */
        public boolean resolved() {
            return kind == Kind.EXACT || kind == Kind.UNIQUE_PREFIX;
        }
    }

    /** Resolve {@code token} against {@code byName} (iteration order preserved for stable messages). */
    public static <T> Result<T> resolve(String token, Map<String, T> byName) {
        T exact = byName.get(token);
        if (exact != null) {
            return new Result<>(Kind.EXACT, exact, List.of(token));
        }
        List<String> names = new ArrayList<>();
        List<T> distinct = new ArrayList<>();
        for (Map.Entry<String, T> e : byName.entrySet()) {
            if (e.getKey().startsWith(token)) {
                names.add(e.getKey());
                if (!containsByIdentity(distinct, e.getValue())) distinct.add(e.getValue());
            }
        }
        if (distinct.isEmpty()) return new Result<>(Kind.NONE, null, List.of());
        names.sort(String::compareTo);
        if (distinct.size() == 1) return new Result<>(Kind.UNIQUE_PREFIX, distinct.get(0), List.copyOf(names));
        return new Result<>(Kind.AMBIGUOUS, null, List.copyOf(names));
    }

    private static <T> boolean containsByIdentity(List<T> list, T value) {
        for (T t : list) {
            if (t == value) return true;
        }
        return false;
    }
}
