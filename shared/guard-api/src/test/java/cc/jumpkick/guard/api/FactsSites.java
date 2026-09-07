// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.api;

import cc.jumpkick.guard.facts.CallSite;
import java.util.List;

/** Builds the facts index's raw call site, whose simple name the API's {@code CallSite} takes in the test. */
final class FactsSites {

    private FactsSites() {}

    static CallSite call(
            String owner, String name, String desc, int line, String literalBefore, List<String> literals) {
        return new CallSite(owner, name, desc, line, literalBefore, 1, literals);
    }
}
