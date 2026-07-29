// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The environment handed to a forked test JVM (JK-1267): a sandbox jk supplies by default, plus
 * whatever the module declares in {@code [test] env}.
 *
 * <p>The default matters more than the knob. A forked test JVM inherits the engine's environment, so
 * before this existed jk's own suite ran against the developer's real {@code ~/.jk} — reading the
 * real library catalog and able to write the real local m2. jk's Gradle build redirects those per
 * module for exactly that reason, with the comment "must not leak between modules or into the user's
 * real home"; a build tool should not make every project remember to do that itself.
 *
 * <p>So {@code JK_HOME} and {@code JK_M2_LOCAL} point at throwaway directories under the module's
 * build output unless the module says otherwise. Anything a suite genuinely needs from the real
 * environment it can name explicitly — the sandbox is a default, not a wall.
 */
public final class TestEnv {

    /** {@code jk}'s own home, and the local m2 derived from it — the two that hold real user state. */
    static final String JK_HOME = "JK_HOME";

    static final String JK_M2_LOCAL = "JK_M2_LOCAL";

    private TestEnv() {}

    /**
     * The child environment for {@code project}'s test JVMs: the sandbox defaults with the module's
     * {@code [test] env} applied over them, and {@code ${target}} / {@code ${module}} expanded.
     *
     * <p>A module that sets {@code JK_HOME} itself wins — this is a default, not an override.
     */
    public static Map<String, String> forModule(JkBuild project, Path moduleDir, BuildLayout layout) {
        return forModule(project, moduleDir, layout, cc.jumpkick.config.BuildEnv.forModule(moduleDir));
    }

    /** As {@link #forModule(JkBuild, Path, BuildLayout)}, resolving {@code ${VAR}} through {@code env}. */
    public static Map<String, String> forModule(
            JkBuild project, Path moduleDir, BuildLayout layout, java.util.function.UnaryOperator<String> env) {
        Path target = layout.moduleTargetDir();
        Map<String, String> out = new LinkedHashMap<>();
        // Sandbox first so a declared value replaces it.
        out.put(JK_HOME, target.resolve("test-jk-home").toAbsolutePath().toString());
        out.put(JK_M2_LOCAL, target.resolve("test-m2").toAbsolutePath().toString());
        for (Map.Entry<String, String> e : project.build().testEnv().entrySet()) {
            String withPaths = expand(e.getValue(), moduleDir, target);
            // Then environment references — a whitelisted position (JK-1271), resolved through the
            // request's environment plus .env, and strict about an unset variable.
            out.put(
                    e.getKey(),
                    cc.jumpkick.config.Interpolation.expand(withPaths, "[test].env." + e.getKey(), env));
        }
        return Map.copyOf(out);
    }

    /**
     * Substitute the two path tokens. Explicit tokens rather than inferring which values look like
     * paths: a magic "does this smell like a path" rule would eventually rewrite something that was
     * meant literally.
     */
    static String expand(String value, Path moduleDir, Path target) {
        if (value == null || value.indexOf('$') < 0) return value;
        return value.replace("${target}", target.toAbsolutePath().toString())
                .replace("${module}", moduleDir.toAbsolutePath().toString());
    }
}
