// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.host.Os;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The environment variables jk ships from the caller's shell without being asked.
 *
 * <p>Everything else a build depends on is declared: {@code env:NAME} in a plugin config, a name or
 * a value in {@code [test] env}. Declaration is what lets a variable enter an action key, and a
 * variable that cannot enter a key cannot safely decide what gets built. So this list is short by
 * construction, and every entry has to justify why no manifest could have declared it.
 *
 * <p>They all justify it the same way: they describe the machine, not the build. A module cannot
 * name the {@code PATH} it wants — that is the user's toolchain, and it differs per developer and
 * per CI runner. What a module can do is name a variable whose <em>value</em> it cares about, which
 * puts it in the key. Both routes exist; this one is for the shape of the world.
 *
 * <p><b>Deliberately not in any action key.</b> Keying on {@code PATH} would mean a laptop and a CI
 * runner never share a cached result, and two terminals on one machine often would not either. The
 * cost of leaving it out is bounded and known: a suite whose outcome depends on a tool being on
 * {@code PATH} can replay a cached pass from a run where the tool was present. A suite that cares
 * should fail loudly when the tool is missing rather than skip — which is what
 * {@code WebClientJsTest} does — and one that genuinely needs the value keyed should name the
 * variable in {@code [test] env}, where it is hashed like any other declared reference.
 */
final class ClientEnvForward {

    /**
     * Where the machine is and how it talks.
     *
     * <ul>
     *   <li>{@code PATH} — every tool a test execs. The engine is a daemon, so without this a
     *       worker searches the {@code PATH} of whichever shell happened to start the daemon,
     *       possibly days ago. Installing {@code node} and re-running would not find it.
     *   <li>{@code HOME} — where tools keep their per-user state; a wrong one writes into another
     *       account's dotfiles or fails outright.
     *   <li>{@code LANG}, {@code LC_ALL} — collation and encoding. A test asserting sorted output
     *       or non-ASCII round-tripping reads differently under {@code C} than under a UTF-8 locale,
     *       and the daemon's answer is not the caller's.
     * </ul>
     *
     * <p>{@code TZ} is not here: it changes what code computes, so a module that cares states it in
     * {@code [test] env} and gets it keyed. The line is the same one {@code Interpolation} draws —
     * the environment may say where jk talks to and what a spawned process sees, never what a
     * result depends on without being written down.
     */
    private static final List<String> UNIX = List.of("PATH", "HOME", "LANG", "LC_ALL");

    /**
     * Windows spells the same facts differently, and a JVM that cannot find {@code SystemRoot}
     * fails to initialise its socket stack — so these are not conveniences.
     */
    private static final List<String> WINDOWS = List.of(
            "PATH", "USERPROFILE", "SystemRoot", "SystemDrive", "windir", "PATHEXT", "COMSPEC", "NUMBER_OF_PROCESSORS");

    private ClientEnvForward() {}

    /** The names forwarded on this platform. */
    static List<String> names() {
        return Os.isWindows() ? WINDOWS : UNIX;
    }

    /** Those of {@link #names()} the caller actually has, in listed order. */
    static Map<String, String> resolve() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String name : names()) {
            String value = System.getenv(name);
            if (value != null) out.put(name, value);
        }
        return out;
    }
}
