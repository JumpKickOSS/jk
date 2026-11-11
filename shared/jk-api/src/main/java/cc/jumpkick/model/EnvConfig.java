// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code [env]}: what a module's workers — the compiler, each test JVM, a plugin step — get from
 * the environment beyond the allow-list jk applies by default.
 *
 * <p>{@code require} and {@code optional} name variables taken from the shell running {@code jk};
 * a required one that is unset fails the module before anything compiles. {@code vars} sets
 * literal values. {@code inherit = true} hands the workers the caller's whole environment. The
 * declared names' values key the module's suite; nothing else in the environment does, short of
 * {@code inherit}.
 *
 * @param lines the manifest line each declared name is written on, for messages; a name parsed
 *     from text the parser could not place is absent
 */
public record EnvConfig(
        boolean inherit,
        List<String> require,
        List<String> optional,
        List<EnvDecl.Set> vars,
        Map<String, Integer> lines) {

    public static final EnvConfig EMPTY = new EnvConfig(false, List.of(), List.of(), List.of(), Map.of());

    public EnvConfig {
        require = require == null ? List.of() : List.copyOf(require);
        optional = optional == null ? List.of() : List.copyOf(optional);
        vars = vars == null ? List.of() : List.copyOf(vars);
        lines = lines == null ? Map.of() : Map.copyOf(lines);
    }

    /** Literal values and the inheritance switch, no declared names. */
    public EnvConfig(boolean inherit, List<EnvDecl.Set> vars) {
        this(inherit, List.of(), List.of(), vars, Map.of());
    }

    public boolean isEmpty() {
        return !inherit && require.isEmpty() && optional.isEmpty() && vars.isEmpty();
    }

    /** Every name taken from the caller's environment: {@code require}, then {@code optional}. */
    public Set<String> declared() {
        Set<String> out = new LinkedHashSet<>(require);
        out.addAll(optional);
        return out;
    }

    /**
     * The table as one environment array in precedence order: each declared name forwarded, then
     * the literal values — the shape {@code [test] env} has, so one expansion serves both.
     */
    public List<EnvDecl> decls() {
        if (require.isEmpty() && optional.isEmpty()) return List.copyOf(vars);
        List<EnvDecl> out = new ArrayList<>();
        for (String name : declared()) out.add(new EnvDecl.Forward(name));
        out.addAll(vars);
        return List.copyOf(out);
    }

    /** The manifest line {@code name} is declared on, or 0 when unknown. */
    public int line(String name) {
        return lines.getOrDefault(name, 0);
    }
}
