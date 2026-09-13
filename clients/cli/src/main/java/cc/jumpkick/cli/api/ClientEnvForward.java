// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.api;

import cc.jumpkick.config.BuildEnv;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

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
 * <p><b>{@code JK_REPO_*} rides too, by prefix.</b> {@code JK_REPO_<ID>_TOKEN} and its
 * {@code _USERNAME}/{@code _PASSWORD}/{@code _HOST} siblings are the user's standing with a
 * repository, not the build's: a project cannot declare them (its {@code .env} is refused as a host
 * binding by design), they enter no action key, and no test JVM is seeded with them — only the
 * credential resolver reads them, and it files every value it resolves with the redactor. They have
 * to travel because the engine is a daemon: its own environment is whichever shell started it, so a
 * token exported in a later terminal was invisible to the resolve, and the refusal warning told the
 * user to set a variable that could not arrive.
 *
 * <p><b>Deliberately not in any action key.</b> Keying on {@code PATH} would mean a laptop and a CI
 * runner never share a cached result, and two terminals on one machine often would not either. The
 * cost of leaving it out is bounded and known: a suite whose outcome depends on a tool being on
 * {@code PATH} can replay a cached pass from a run where the tool was present. A suite that cares
 * should fail loudly when the tool is missing rather than skip — which is what
 * {@code WebClientJsTest} does — and one that genuinely needs the value keyed should name the
 * variable in {@code [test] env}, where it is hashed like any other declared reference.
 */
public final class ClientEnvForward {

    /** Every variable under this prefix is forwarded: repository credentials and host bindings. */
    public static final String REPO_PREFIX = "JK_REPO_";

    /** The {@code jk.env.<NAME>} property that overrides one variable — {@code JkDirs.env}'s seam. */
    private static final String SEAM = "jk.env.";

    private ClientEnvForward() {}

    /** The names forwarded by exact spelling on this platform — {@link BuildEnv#MACHINE}. */
    public static List<String> names() {
        return BuildEnv.MACHINE;
    }

    /**
     * Those of {@link #names()} the caller actually has, in listed order, then every
     * {@link #REPO_PREFIX} variable. {@code System::getenv} is passed explicitly — the client
     * resolves from its own shell, never from a session's {@code clientEnv}. The repository names
     * also honour the {@code jk.env.*} seam, so a test varies one credential per invocation.
     */
    public static Map<String, String> resolve() {
        Map<String, String> out = new LinkedHashMap<>(BuildEnv.machine(System::getenv));
        for (Map.Entry<String, String> e : System.getenv().entrySet()) {
            if (e.getKey().startsWith(REPO_PREFIX)) out.put(e.getKey(), e.getValue());
        }
        for (String property : System.getProperties().stringPropertyNames()) {
            if (!property.startsWith(SEAM + REPO_PREFIX)) continue;
            String value = System.getProperty(property);
            if (value != null) out.put(property.substring(SEAM.length()), value);
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * {@link #resolve()} beneath {@code declared}: what a command already resolved for its
     * declared references wins over the forward set for the same name.
     */
    public static Map<String, String> layerUnder(@Nullable Map<String, String> declared) {
        Map<String, String> out = new LinkedHashMap<>(resolve());
        if (declared != null) out.putAll(declared);
        return Collections.unmodifiableMap(out);
    }
}
