// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.TestEnvValues;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.Log;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.EnvConfig;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.util.TestHomes;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * sandbox ({@link TestHomes}) and the temp root at that sandbox's slot, unless the module says
 * otherwise. The three roots with their own override — {@code JK_STATE_DIR}, {@code JK_STORE_DIR},
 * {@code JK_CACHE_DIR} — are pinned under that home too: {@link WorkerEnv} lets the engine's own
 * spellings of them through, and an engine started with {@code JK_STATE_DIR} in its shell would
 * otherwise hand every test JVM its real build history and host calibration. Anything a suite
 * genuinely needs from the real environment beyond {@link BuildEnv#MACHINE} it can name explicitly —
 * the sandbox is a default, not a wall.
 *
 * <p>The sandbox store's public-origin repository trees and build-tool distributions are links
 * into one pool every sandbox on the host shares ({@link TestStorePool}), so a new worktree's suites start with what any other worktree already fetched. The store is also warmed
 * with the JUnit Platform from the engine's own ({@link TestStoreSeed}): the injected test roots a
 * fixture lock needs, version lists included, are on disk before the first fixture asks, and a
 * Central that throttles this host cannot turn a suite red.
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

    /** The roots with an override of their own, each pinned under the sandbox home. */
    static final String JK_STATE_DIR = "JK_STATE_DIR";

    static final String JK_STORE_DIR = "JK_STORE_DIR";

    static final String JK_CACHE_DIR = "JK_CACHE_DIR";

    static final String JK_M2_LOCAL = "JK_M2_LOCAL";

    /**
     * The temp root: {@link TestHomes#tmpFor}, jk-owned and emptied at every launch, rather than the
     * host's.
     *
     * <p>A forked test JVM would otherwise inherit the engine's temp dir, one jk neither owns nor
     * cleans: leftovers pile up on a shared tmpfs until a later suite cannot allocate an inode, and
     * on macOS it sits under the {@code /var} → {@code /private/var} link, so code comparing a temp
     * path with a configured one compares two spellings of one directory. The root is handed out in
     * its real spelling for that reason. It is outside the project tree, as Surefire's is: a test
     * may assume {@code @TempDir} is not under its working directory, and a fixture there would find
     * the project's {@code jk.toml} walking up.
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
     * The sandbox store's pooled trees become links into the host's shared pool ({@link
     * TestStorePool}), then the sandbox store and its local m2 learn the JUnit Platform from this
     * machine's local repositories ({@link TestStoreSeed}), so the suite's fixtures lock their
     * injected test roots without Central. Warmth only: a link or seed that fails leaves the
     * fixtures to fetch what they need.
     */
    private static void warmStore(Path sandboxHome, Path sandboxM2) {
        Path store = sandboxHome.resolve("store");
        try {
            TestStorePool.link(TestStorePool.root(), store);
        } catch (IOException | RuntimeException e) {
            Log.debug("TestEnv: the sandbox keeps its own repository trees; fixtures fetch into them", e);
        }
        try {
            int seeded = TestStoreSeed.seed(store, sandboxM2);
            if (seeded > 0) Log.debug("TestEnv: seeded " + seeded + " JUnit Platform files into " + sandboxHome);
        } catch (IOException | RuntimeException e) {
            Log.debug("TestEnv: the sandbox store keeps whatever it had; fixtures fetch the rest", e);
        }
    }

    /**
     * The sandbox's Maven local repository: its pooled {@code repos/central} tree. Central's jars
     * live in the local repository, and the pool ({@link TestStorePool}) is what keeps a fetched
     * dependency from being fetched again by the next sandbox, so the two are one tree — the memos
     * that vouch for a file sit beside it, as they did when the store held the jars itself. A test
     * sandbox is the one place the local repository holds {@code .jk} files.
     */
    private static Path sandboxM2(Path sandboxHome) {
        return sandboxHome.resolve("store").resolve("repos").resolve(RepositorySpec.CENTRAL);
    }

    /**
     * Mark the slot {@code moduleDir}'s test JVMs read as held by a live launch until the hold is
     * closed, so no reaper takes their jars while the suite runs.
     */
    public static TestHomes.Hold holdSandboxes(Path moduleDir) {
        return TestHomes.hold(TestHomes.slotFor(moduleDir));
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
        return forModule(project, moduleDir, layout, true);
    }

    /**
     * {@link #forModule} without seeding the sandbox store: the same environment for a caller that
     * launches no suite into it and only asks what it is.
     */
    public static WorkerEnv forModuleUnseeded(JkBuild project, Path moduleDir, BuildLayout layout) throws IOException {
        return forModule(project, moduleDir, layout, false);
    }

    private static WorkerEnv forModule(JkBuild project, Path moduleDir, BuildLayout layout, boolean seed)
            throws IOException {
        Path target = layout.moduleTargetDir();
        Map<String, String> out = new LinkedHashMap<>();
        // Caller's PATH/HOME/… first so a declared [test] env entry can still replace them.
        out.putAll(BuildEnv.machine());
        // Sandbox next so a declared value replaces it. The home and the temp root are outside the
        // project (TestHomes). Prepared here — created and stamped as in use — so anything the
        // build stages into it before the suite launches survives the reaper another module's
        // preparation may run meanwhile.
        Path sandboxHome = TestHomes.prepare(moduleDir);
        Path m2 = sandboxM2(sandboxHome);
        if (seed) warmStore(sandboxHome, m2);
        out.put(JK_HOME, sandboxHome.toString());
        out.put(JK_STATE_DIR, sandboxHome.resolve("state").toString());
        out.put(JK_STORE_DIR, sandboxHome.resolve("store").toString());
        out.put(JK_CACHE_DIR, sandboxHome.resolve("cache").toString());
        out.put(JK_JDKS_DIR, sandboxHome.resolve("jdks").toString());
        out.put(JK_M2_LOCAL, m2.toString());
        // Created and emptied at launch, not here: JUnitLauncher makes it. The slot already exists,
        // so its real spelling can be had now.
        String testTmp = TestHomes.tmpFor(moduleDir).toString();
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
