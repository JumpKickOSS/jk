// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.TestEnvValues;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.Log;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.EnvConfig;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.util.TestHomes;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The environment handed to a forked test JVM: the module's {@link WorkerEnv} policy, the caller's
 * machine env ({@link BuildEnv#MACHINE}), a sandbox jk supplies by default, the module's {@code [env]
 * vars}, then whatever it declares in {@code [test] env}.
 *
 * <p>The default matters more than the knob. Without sandboxing a test JVM would read the
 * developer's real product layout and write the real local m2. Without the machine-env seed it
 * would search the daemon's {@code PATH} — whichever shell started the engine, possibly days ago —
 * instead of the shell that ran {@code jk}. The sandbox redirects the product layout per module
 * for exactly that reason; the machine seed is the matching answer for tools on {@code PATH}.
 *
 * <p>So {@code JK_HOME}, {@code JK_JDKS_DIR} and {@code JK_M2_LOCAL} point at the module's throwaway
 * sandbox ({@link TestHomes}) and the temp root at the module's build output, unless the module says
 * otherwise. Anything a suite genuinely needs from the real environment beyond
 * {@link BuildEnv#MACHINE} it can name explicitly — the sandbox is a default, not a wall.
 *
 * <p>The declared values themselves are resolved by {@link TestEnvValues}, which the run-tests cache
 * key also uses: the two must agree about an unset {@code ${VAR}} or a build's outcome depends on
 * what is already cached. Machine env is deliberately not part of that key.
 */
public final class TestEnv {

    /** Product single-tree umbrella (config, cache, store, state, …). */
    public static final String JK_HOME = "JK_HOME";

    /** Managed JDK write root — not relocated by {@code JK_HOME} alone. */
    static final String JK_JDKS_DIR = "JK_JDKS_DIR";

    static final String JK_M2_LOCAL = "JK_M2_LOCAL";

    /**
     * The temp root, under the module's build output rather than the host's.
     *
     * <p>Same argument as the sandbox above, one step further: a forked test JVM inherits the
     * engine's temp dir, so {@code @TempDir} and every {@code createTempFile} land in a directory
     * jk neither owns nor cleans. Two costs followed. Leftovers accumulate on a shared tmpfs until
     * a later suite cannot allocate an inode — the reason {@code jk-cli} carries its own deletion
     * strategy. And the host temp root is not a neutral path: on macOS it sits under the
     * {@code /var} → {@code /private/var} link, so any code that compares a temp path against a
     * path it was configured with is comparing two spellings of one directory. That is a real
     * defect, found in {@code jk-java-compiler} by a run whose temp root was the host's; a temp
     * root the module owns is what lets a gate see it.
     *
     * <p>All three names, because a test that forks a process hands it the environment, not this
     * JVM's system properties. {@link cc.jumpkick.test.JUnitLauncher} mirrors the same directory
     * into {@code java.io.tmpdir} for the Java side.
     */
    static final String TMPDIR = "TMPDIR";

    static final String TMP = "TMP";

    static final String TEMP = "TEMP";

    private TestEnv() {}

    /**
     * The sandbox local-m2 root: one per <em>workspace</em>, not one per module.
     *
     * <p>Unlike the product home beside it, a local m2 is a content-addressed artifact cache with
     * nothing module-specific in it, so a copy per module buys nothing and costs twice: the same
     * dependency is fetched once per module that needs it, and stored once per module that has it.
     *
     * <p>{@code JK_HOME} deliberately stays per module: it holds state, locks, learned rates and a
     * calibration, and concurrent suites writing one copy would race on all four.
     *
     * <p>Not part of the run-tests action key — the key hashes the module's declared
     * {@code [test] env}, not the sandbox underneath it — so moving it re-runs nothing.
     */
    private static Path sandboxM2(Path moduleDir) {
        try {
            Optional<Path> root = WorkspaceScan.findRoot(moduleDir);
            // The workspace's slot, not its home: the m2 is a sibling of one member's home rather
            // than inside it, so it outlives a home being wiped and `jk clean` at the root takes it.
            if (root.isPresent()) return TestHomes.slotFor(root.get()).resolve("test-m2");
        } catch (RuntimeException e) {
            // A workspace root that will not read is the build's error to report, not this one's:
            // fall back to the module's own slot so a test JVM still gets a sandbox.
            Log.debug(
                    "sandboxM2: A workspace root that will not read is the build's error to report, not this one's", e);
        }
        return TestHomes.slotFor(moduleDir).resolve("test-m2");
    }

    /**
     * The child environment for {@code project}'s test JVMs: the caller's machine env ({@link
     * BuildEnv#MACHINE}), then the sandbox defaults, then the module's {@code [env] vars} and {@code
     * [test] env}, with {@code ${target}} / {@code ${module}} / {@code ${VAR}} expanded — all on top
     * of what the module's {@code [env]} policy lets through from the engine.
     *
     * <p>Machine env overlays the daemon's inherited {@code PATH} with the shell that ran {@code
     * jk} — without it a suite that execs {@code node} searches whichever shell started the
     * engine, possibly days ago and without nvm. It is not hashed into the action key; a module
     * that needs the value keyed declares the name in {@code [test] env}.
     *
     * <p>A module that sets {@code JK_HOME} itself wins — the sandbox is a default, not an override.
     */
    public static WorkerEnv forModule(JkBuild project, Path moduleDir, BuildLayout layout) throws IOException {
        Path target = layout.moduleTargetDir();
        Map<String, String> out = new LinkedHashMap<>();
        // Caller's PATH/HOME/… first so a declared [test] env entry can still replace them.
        out.putAll(BuildEnv.machine());
        // Sandbox next so a declared value replaces it. The home is outside the project (TestHomes);
        // the temp root below stays under the build output. Prepared here — created and stamped as
        // in use — so anything the build stages into it before the suite launches survives the
        // reaper another module's preparation may run meanwhile.
        Path sandboxHome = TestHomes.prepare(moduleDir);
        out.put(JK_HOME, sandboxHome.toString());
        out.put(JK_JDKS_DIR, sandboxHome.resolve("jdks").toString());
        out.put(JK_M2_LOCAL, sandboxM2(moduleDir).toString());
        // Created at launch, not here: this method answers what the environment is, and the
        // directory has to exist before a worker starts. JUnitLauncher makes it.
        String testTmp = target.resolve("tmp").toAbsolutePath().toString();
        out.put(TMPDIR, testTmp);
        out.put(TMP, testTmp);
        out.put(TEMP, testTmp);
        // Unique listeners: a nested engine must not steal the host's HTTP port or share its
        // UDS (UDS follows JK_HOME/state). Port 0 is OS-assigned; disable HTTP unless a test
        // opts in.
        out.put("JK_HTTP_ENABLED", "false");
        out.put("JK_HTTP_PORT", "0");
        EnvConfig policy = project.build().env();
        out.putAll(WorkerEnv.declared(policy, moduleDir, target));
        Function<String, @Nullable String> buildEnv = BuildEnv.forModule(moduleDir);
        out.putAll(TestEnvValues.resolve(
                "[test].env",
                project.build().testEnv(),
                moduleDir,
                target,
                new TestEnvValues.Mode.Launch(buildEnv::apply)));
        return WorkerEnv.policy(policy).with(out);
    }
}
