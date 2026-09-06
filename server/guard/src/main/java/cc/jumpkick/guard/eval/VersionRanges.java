// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.resolver.Versions;

/**
 * The floor grammar of {@code depend.require}: comma-separated clauses, each {@code >=v}, {@code
 * >v}, {@code <=v}, {@code <v}, {@code =v}, {@code !=v}, or a bare {@code v} (exact). Compared with
 * jk's one Maven-order comparator — never a second one.
 */
final class VersionRanges {

    private VersionRanges() {}

    static boolean satisfies(String version, String spec) {
        for (String clause : spec.split(",")) {
            String c = clause.strip();
            if (c.isEmpty()) continue;
            if (!clause(version, c)) return false;
        }
        return true;
    }

    private static boolean clause(String version, String c) {
        if (c.startsWith(">=")) return Versions.compare(version, c.substring(2).strip()) >= 0;
        if (c.startsWith("<=")) return Versions.compare(version, c.substring(2).strip()) <= 0;
        if (c.startsWith("!=")) return Versions.compare(version, c.substring(2).strip()) != 0;
        if (c.startsWith(">")) return Versions.compare(version, c.substring(1).strip()) > 0;
        if (c.startsWith("<")) return Versions.compare(version, c.substring(1).strip()) < 0;
        if (c.startsWith("=")) return Versions.compare(version, c.substring(1).strip()) == 0;
        return Versions.compare(version, c) == 0;
    }

    /** Whether {@code spec} parses: every clause has a recognised operator and a non-empty version. */
    static boolean valid(String spec) {
        boolean any = false;
        for (String clause : spec.split(",")) {
            String c = clause.strip();
            if (c.isEmpty()) continue;
            any = true;
            String v = c.replaceFirst("^(>=|<=|!=|>|<|=)", "").strip();
            if (v.isEmpty()) return false;
        }
        return any;
    }
}
