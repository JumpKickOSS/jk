// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** The one Guava call the PRD's example uses, so the example compiles unchanged without Guava. */
final class Sets {

    private Sets() {}

    static List<Set<String>> powerSet(Set<String> items) {
        List<String> list = new ArrayList<>(items);
        List<Set<String>> out = new ArrayList<>();
        for (int mask = 0; mask < (1 << list.size()); mask++) {
            Set<String> s = new TreeSet<>();
            for (int i = 0; i < list.size(); i++) if ((mask >> i & 1) == 1) s.add(list.get(i));
            out.add(s);
        }
        return out;
    }
}
