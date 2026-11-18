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
 * literal values. {@code inherit = true} hands every worker, the compiler included, the caller's
 * whole environment. The declared names' values key the module's suite.
 *
 * <p>A test JVM sees the caller's whole environment besides: each variable it looks up is fetched
 * from the shell running {@code jk} when it is first read, and the suite is keyed on what it read
 * ({@code SuiteEnvReads}). {@code inherit = false}, written, is {@code strict}: the test JVM gets the
 * allow-list and the declared names alone, as before 0.53. {@code scope} narrows what a test JVM
 * can see of the rest to the names its patterns match ({@code SPRING_*}).
 *
 * @param lines the manifest line each declared name is written on, for messages; a name parsed
 *     from text the parser could not place is absent
 */
public record EnvConfig(
        boolean inherit,
        List<String> require,
        List<String> optional,
        List<EnvDecl.Set> vars,
        Map<String, Integer> lines,
        boolean strict,
        List<String> scope) {

    public static final EnvConfig EMPTY = new EnvConfig(false, List.of(), List.of(), List.of(), Map.of());

    public EnvConfig {
        scope = scope == null ? List.of() : List.copyOf(scope);
        require = require == null ? List.of() : List.copyOf(require);
        optional = optional == null ? List.of() : List.copyOf(optional);
        vars = vars == null ? List.of() : List.copyOf(vars);
        lines = lines == null ? Map.of() : Map.copyOf(lines);
    }

    /** The table without {@code strict} or {@code scope}. */
    public EnvConfig(
            boolean inherit,
            List<String> require,
            List<String> optional,
            List<EnvDecl.Set> vars,
            Map<String, Integer> lines) {
        this(inherit, require, optional, vars, lines, false, List.of());
    }

    /** This table with {@code strict} and {@code scope}. */
    public EnvConfig withTestPolicy(boolean strictTests, List<String> testScope) {
        return new EnvConfig(inherit, require, optional, vars, lines, strictTests, testScope);
    }

    /** Literal values and the inheritance switch, no declared names. */
    public EnvConfig(boolean inherit, List<EnvDecl.Set> vars) {
        this(inherit, List.of(), List.of(), vars, Map.of());
    }

    public boolean isEmpty() {
        return !inherit && !strict && require.isEmpty() && optional.isEmpty() && vars.isEmpty() && scope.isEmpty();
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
