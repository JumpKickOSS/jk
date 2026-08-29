// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.config.BuildEnv;
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
 * <p>The names live on {@link BuildEnv#MACHINE} so the client forward list and the test-JVM seed
 * cannot disagree. Values ride the request's {@code clientEnv}; the engine merges them into every
 * forked test JVM (still not into any action key — see {@link BuildEnv#MACHINE}).
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

    private ClientEnvForward() {}

    /** The names forwarded on this platform — {@link BuildEnv#MACHINE}. */
    static List<String> names() {
        return BuildEnv.MACHINE;
    }

    /**
     * Those of {@link #names()} the caller actually has, in listed order. {@code System::getenv} is
     * passed explicitly — the client resolves from its own shell, never from a session's
     * {@code clientEnv}.
     */
    static Map<String, String> resolve() {
        return BuildEnv.machine(System::getenv);
    }
}
