// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import java.util.List;

/**
 * The lines an impact adds to a uses answer: {@code before} its Java uses (what was looked at and the
 * places no compiler checks), {@code after} them (accessors' callers, tests).
 */
public record ImpactLines(List<String> before, List<String> after) {

    public ImpactLines {
        before = List.copyOf(before);
        after = List.copyOf(after);
    }
}
