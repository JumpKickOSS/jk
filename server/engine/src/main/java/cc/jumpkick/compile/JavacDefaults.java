// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.model.DebugInfo;
import java.util.ArrayList;
import java.util.List;

/**
 * The javac flags jk adds to every compile before the user's own: the debug-info level and the
 * lint policy.
 *
 * <p>Debug info defaults to {@link DebugInfo#FULL} ({@code -g}), matching Gradle and Maven,
 * so a debugger attached to a jk-built JVM sees local variable names; {@code [build] debug} picks
 * another level. Lint defaults to {@code -Xlint:deprecation,unchecked} so deprecation/unchecked
 * warnings surface; they are reported through the warn channel and never fail the build.
 * {@code [build] lint = false} turns them off.
 *
 * <p>Every flag here precedes the user's, so a flag the user passes through a profile or {@code
 * [javac] args} wins where javac takes the last one.
 */
public final class JavacDefaults {

    private JavacDefaults() {}

    /** The lint flags injected when {@code [build] lint} is on. */
    public static final List<String> LINT_ARGS = List.of("-Xlint:deprecation,unchecked");

    /**
     * The effective javac args: the debug-info flag, jk's lint flags (when {@code lintEnabled}),
     * the installed plugins' contributed args (e.g. the spring-boot manifest's {@code -parameters}
     * — the framework reflects on constructor/handler parameter names), then the user's own {@code
     * javac} args. A contributed arg the user already passes is not duplicated (user wins on
     * position).
     */
    public static List<String> effectiveArgs(
            boolean lintEnabled, DebugInfo debug, List<String> contributedArgs, List<String> userArgs) {
        List<String> user = userArgs == null ? List.of() : userArgs;
        List<String> contributed = contributedArgs == null ? List.of() : contributedArgs;
        List<String> out = new ArrayList<>(1 + LINT_ARGS.size() + contributed.size() + user.size());
        out.add(debug.javacFlag());
        if (lintEnabled) out.addAll(LINT_ARGS);
        for (String arg : contributed) {
            if (!user.contains(arg) && !out.contains(arg)) out.add(arg);
        }
        out.addAll(user);
        return List.copyOf(out);
    }
}
