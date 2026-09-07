// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.rules;

import cc.jumpkick.guard.schema.Kind;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlTable;

/**
 * One loaded rule: the common keys typed, the kind-specific keys left on {@link #table()} for the
 * kind's evaluator to read against its {@link Kind#keys()} — already shape-checked at load.
 *
 * @param scope module globs; empty means every module
 * @param sourceSet {@code main} (default), {@code test}, {@code guard} or {@code all}
 * @param fixture {@code guard-fixtures/<id>} or {@code null}
 */
public record Rule(
        String id,
        Kind kind,
        String why,
        @Nullable String instead,
        List<String> scope,
        String sourceSet,
        List<Allow> allow,
        boolean baseline,
        @Nullable String fixture,
        TomlTable table,
        RuleSource source,
        boolean locked) {

    /** An unlocked rule: every rule a project writes; only a pack says {@code locked = true}. */
    public Rule(
            String id,
            Kind kind,
            String why,
            @Nullable String instead,
            List<String> scope,
            String sourceSet,
            List<Allow> allow,
            boolean baseline,
            @Nullable String fixture,
            TomlTable table,
            RuleSource source) {
        this(id, kind, why, instead, scope, sourceSet, allow, baseline, fixture, table, source, false);
    }

    /** The same rule with more {@code allow} entries and a baseline flag — a root amendment of a pack rule. */
    public Rule amended(List<Allow> more, boolean baselineToo) {
        List<Allow> all = new ArrayList<>(allow);
        all.addAll(more);
        return new Rule(
                id, kind, why, instead, scope, sourceSet, all, baseline || baselineToo, fixture, table, source, locked);
    }

    /** The same rule with its scope set — a module file's rule defaults to the module. */
    public Rule withScope(List<String> newScope) {
        return new Rule(id, kind, why, instead, newScope, sourceSet, allow, baseline, fixture, table, source, locked);
    }

    public Rule {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(why, "why");
        scope = scope == null ? List.of() : List.copyOf(scope);
        allow = allow == null ? List.of() : List.copyOf(allow);
        sourceSet = sourceSet == null ? "main" : sourceSet;
    }

    /** Whether {@code module} (a workspace-relative path) is in this rule's scope. */
    public boolean applies(String module) {
        if (scope.isEmpty()) return true;
        for (String glob : scope) {
            if (globMatches(glob, module)) return true;
        }
        return false;
    }

    public static boolean globMatches(String glob, String path) {
        if (glob.equals("*") || glob.equals("**")) return true;
        StringBuilder re = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    // `**/` may match nothing at all, so `**/src/**` also matches `src/x`.
                    if (i + 2 < glob.length() && glob.charAt(i + 2) == '/') {
                        re.append("(?:.*/)?");
                        i += 2;
                    } else {
                        re.append(".*");
                        i++;
                    }
                } else {
                    re.append("[^/]*");
                }
            } else if (c == '?') {
                re.append('.');
            } else if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
                re.append('\\').append(c);
            } else {
                re.append(c);
            }
        }
        return path.matches(re.toString());
    }
}
