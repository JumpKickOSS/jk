// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** The inputs of a record that differ from the inputs a step keys on now: what a miss names. */
public final class ChangedInputs {

    /**
     * The input whose value is a step's key tokens joined by {@code ;}: a packaging step records its
     * key that way, and a miss names the tokens that moved inside it.
     */
    public static final String JOINED_TOKENS = "inputs";

    private ChangedInputs() {}

    /** The input names whose values differ between {@code prior} and {@code now}, sorted. */
    static List<String> of(Map<String, String> prior, Map<String, String> now) {
        Set<String> names = new TreeSet<>(prior.keySet());
        names.addAll(now.keySet());
        List<String> moved = new ArrayList<>();
        for (String name : names) {
            String was = prior.get(name);
            String is = now.get(name);
            if (Objects.equals(was, is)) continue;
            if (name.equals(JOINED_TOKENS) && was != null && is != null) {
                List<String> tokens = of(tokens(was), tokens(is));
                if (!tokens.isEmpty()) {
                    moved.addAll(tokens);
                    continue;
                }
            }
            moved.add(name);
        }
        return moved;
    }

    private static Map<String, String> tokens(String joined) {
        return KeyAudit.lines("", List.of(joined.split(";")));
    }
}
