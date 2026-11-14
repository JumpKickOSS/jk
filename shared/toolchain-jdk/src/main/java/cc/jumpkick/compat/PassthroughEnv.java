// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import cc.jumpkick.host.ChildEnv;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.SearchPath;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The one child-process environment jk hands to a tool it did not write: {@code jk mvn}, {@code jk
 * gradle}, {@code jk shell}, and the engine's Gradle/Maven source-dependency builds.
 *
 * <p>Every caller strips the same {@link #STRIPPED} union rather than curating its own subset. A var
 * that is only dangerous for one caller is merely noise for the rest, and three near-identical lists
 * is how the engine's copy came to prepend the JDK's {@code bin} to a key Windows does not use.
 */
public final class PassthroughEnv {

    /**
     * The vars through which the surrounding shell could out-vote the toolchain jk just picked, each
     * with the caller that needs it gone:
     *
     * <ul>
     *   <li>{@code JAVA_TOOL_OPTIONS}, {@code _JAVA_OPTIONS}, {@code JDK_JAVA_OPTIONS} — all callers:
     *       every JVM launched below us reads them (the last through the {@code java} launcher), and
     *       they carry arbitrary flags including {@code -javaagent}.
     *   <li>{@code JDK_HOME} — {@code jk shell}: an interactive shell's rc files and IDE-spawned
     *       launchers prefer it over {@code JAVA_HOME}, so leaving it would silently undo the very
     *       pin {@code jk shell} exists to apply.
     *   <li>{@code KOTLIN_HOME} — {@code jk mvn} / {@code jk gradle}: a kotlinc launcher resolves its
     *       whole distribution from it.
     *   <li>{@code MAVEN_OPTS}, {@code GRADLE_OPTS} — {@code jk mvn} / {@code jk gradle} and the
     *       engine's source-dependency builds: the heap and {@code -D} injection points for exactly
     *       the tool those call sites are about to run.
     * </ul>
     */
    private static final List<String> STRIPPED = List.of(
            "JAVA_TOOL_OPTIONS",
            "_JAVA_OPTIONS",
            "JDK_JAVA_OPTIONS",
            "JDK_HOME",
            "KOTLIN_HOME",
            "MAVEN_OPTS",
            "GRADLE_OPTS");

    private PassthroughEnv() {}

    /**
     * Mutate {@code env} in place: drop {@link #STRIPPED}, and when {@code javaHome} is non-null set
     * {@code JAVA_HOME} and prepend its {@code bin} to {@code PATH}. Pass {@code null} when the
     * caller has already put the JDK on {@code PATH} itself — {@code jk shell} already swapped the
     * toolchain bin onto PATH, so a second prepend here would double it.
     */
    public static void apply(Map<String, String> env, @Nullable Path javaHome) {
        apply(env, javaHome, Os.isWindows());
    }

    /**
     * {@link #apply(Map, Path)} for a platform whose names are case-insensitive or not: on Windows
     * {@code PATH} habitually arrives as {@code Path}, and {@link ChildEnv} writes the existing
     * spelling rather than a second key that would leave the old java first on the real one.
     */
    static void apply(Map<String, String> env, @Nullable Path javaHome, boolean caseInsensitive) {
        for (String name : STRIPPED) {
            ChildEnv.remove(env, name, caseInsensitive);
        }
        if (javaHome == null) return;
        ChildEnv.put(env, "JAVA_HOME", javaHome.toAbsolutePath().toString(), caseInsensitive);
        String binDir = javaHome.resolve("bin").toAbsolutePath().toString();
        String existing = ChildEnv.get(env, "PATH", caseInsensitive);
        ChildEnv.put(env, "PATH", SearchPath.prepend(binDir, existing == null ? "" : existing), caseInsensitive);
    }
}
