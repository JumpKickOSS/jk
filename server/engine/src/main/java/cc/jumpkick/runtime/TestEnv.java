// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.TestEnvValues;
import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The environment handed to a forked test JVM: the caller's machine env ({@link BuildEnv#MACHINE}),
 * a sandbox jk supplies by default, plus whatever the module declares in {@code [test] env}.
 *
 * <p>The default matters more than the knob. A forked test JVM inherits the engine's environment, so
 * without sandboxing it would read the developer's real product layout and write the real local m2.
 * Without the machine-env seed it would also search the daemon's {@code PATH} — whichever shell
 * started the engine, possibly days ago — instead of the shell that ran {@code jk}. jk's Gradle
 * build redirects the product layout per module for exactly that reason; the machine seed is the
 * matching answer for tools on {@code PATH}.
 *
 * <p>So {@code JK_HOME}, {@code JK_JDKS_DIR}, {@code JK_M2_LOCAL} and the temp root point at
 * throwaway directories under the module's build output unless the module says otherwise. Anything a
 * suite genuinely needs from the real environment beyond {@link BuildEnv#MACHINE} it can name
 * explicitly — the sandbox is a default, not a wall.
 *
 * <p>The declared values themselves are resolved by {@link TestEnvValues}, which the run-tests cache
 * key also uses: the two must agree about an unset {@code ${VAR}} or a build's outcome depends on
 * what is already cached. Machine env is deliberately not part of that key.
 */
public final class TestEnv {

    /** Product single-tree umbrella (config, cache, store, state, …). */
    static final String JK_HOME = "JK_HOME";

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
     * defect either way, but jk found it in {@code jk-java-compiler} while Gradle — which has
     * redirected this per module all along — could not, and a difference that decides whether a
     * gate can see a bug is not one to leave in place.
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
     * Measured on this repo before centralizing — 467 MB across nine copies, the two largest being
     * near-identical. jk's Gradle build has the same shape for the same reason (464 MB across five
     * {@code <module>/build/test-m2} directories) and needs a size cap to hold it down.
     *
     * <p>{@code JK_HOME} deliberately stays per module: it holds state, locks, learned rates and a
     * calibration, and concurrent suites writing one copy would race on all four.
     *
     * <p>Not part of the run-tests action key — the key hashes the module's declared
     * {@code [test] env}, not the sandbox underneath it — so moving it re-runs nothing.
     */
    private static Path sandboxM2(Path moduleDir, Path moduleTarget) {
        try {
            Optional<Path> root = WorkspaceScan.findRoot(moduleDir);
            if (root.isPresent()) {
                Path rootDir = root.get();
                BuildLayout rootLayout =
                        BuildLayout.of(rootDir, JkBuildParser.parse(rootDir.resolve(ManifestPaths.MANIFEST)));
                return rootLayout.moduleTargetDir().resolve("test-m2").toAbsolutePath();
            }
        } catch (IOException | RuntimeException e) {
            // A workspace root that will not read or parse is the build's error to report, not this
            // one's: fall back to the module-local cache so a test JVM still gets a sandbox.
        }
        return moduleTarget.resolve("test-m2").toAbsolutePath();
    }

    /**
     * The child environment for {@code project}'s test JVMs: the caller's machine env ({@link
     * BuildEnv#MACHINE}), then the sandbox defaults, then the module's {@code [test] env}, with
     * {@code ${target}} / {@code ${module}} / {@code ${VAR}} expanded.
     *
     * <p>Machine env overlays the daemon's inherited {@code PATH} with the shell that ran {@code
     * jk} — without it a suite that execs {@code node} searches whichever shell started the
     * engine, possibly days ago and without nvm. It is not hashed into the action key; a module
     * that needs the value keyed declares the name in {@code [test] env}.
     *
     * <p>A module that sets {@code JK_HOME} itself wins — the sandbox is a default, not an override.
     */
    public static Map<String, String> forModule(JkBuild project, Path moduleDir, BuildLayout layout) {
        Path target = layout.moduleTargetDir();
        Map<String, String> out = new LinkedHashMap<>();
        // Caller's PATH/HOME/… first so a declared [test] env entry can still replace them.
        out.putAll(BuildEnv.machine());
        // Sandbox next so a declared value replaces it.
        Path sandboxHome = target.resolve("test-jk-home").toAbsolutePath();
        out.put(JK_HOME, sandboxHome.toString());
        out.put(JK_JDKS_DIR, sandboxHome.resolve("jdks").toString());
        out.put(JK_M2_LOCAL, sandboxM2(moduleDir, target).toString());
        // Created at launch, not here: this method answers what the environment is, and the
        // directory has to exist before a worker starts. JUnitLauncher makes it.
        String testTmp = target.resolve("tmp").toAbsolutePath().toString();
        out.put(TMPDIR, testTmp);
        out.put(TMP, testTmp);
        out.put(TEMP, testTmp);
        // Unique listeners: a nested engine must not steal the host's HTTP port or share its
        // UDS (UDS follows JK_HOME/state). Port 0 is OS-assigned; disable HTTP unless a test
        // opts in — Gradle does the same.
        out.put("JK_HTTP_ENABLED", "false");
        out.put("JK_HTTP_PORT", "0");
        out.putAll(TestEnvValues.resolve(
                project.build().testEnv(),
                moduleDir,
                target,
                new TestEnvValues.Mode.Launch(BuildEnv.forModule(moduleDir))));
        return Map.copyOf(out);
    }
}
