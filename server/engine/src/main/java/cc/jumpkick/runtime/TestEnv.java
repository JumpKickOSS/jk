// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.TestEnvValues;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The environment handed to a forked test JVMa sandbox jk supplies by default, plus
 * whatever the module declares in {@code [test] env}.
 *
 * <p>The default matters more than the knob. A forked test JVM inherits the engine's environment, so
 * without sandboxing it would read the developer's real product layout and write the real local m2.
 * jk's Gradle build redirects those per module for exactly that reason.
 *
 * <p>So {@code JK_HOME}, {@code JK_JDKS_DIR}, {@code JK_M2_LOCAL} and the temp root point at
 * throwaway directories under the module's build output unless the module says otherwise. Anything a
 * suite genuinely needs from the real environment it can name explicitly — the sandbox is a default,
 * not a wall.
 *
 * <p>The declared values themselves are resolved by {@link TestEnvValues}, which the run-tests cache
 * key also uses: the two must agree about an unset {@code ${VAR}} or a build's outcome depends on
 * what is already cached.
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
     * The child environment for {@code project}'s test JVMs: the sandbox defaults with the module's
     * {@code [test] env} applied over them, and {@code ${target}} / {@code ${module}} / {@code ${VAR}}
     * expanded.
     *
     * <p>A module that sets {@code JK_HOME} itself wins — this is a default, not an override.
     */
    public static Map<String, String> forModule(JkBuild project, Path moduleDir, BuildLayout layout) {
        Path target = layout.moduleTargetDir();
        Map<String, String> out = new LinkedHashMap<>();
        // Sandbox first so a declared value replaces it.
        Path sandboxHome = target.resolve("test-jk-home").toAbsolutePath();
        out.put(JK_HOME, sandboxHome.toString());
        out.put(JK_JDKS_DIR, sandboxHome.resolve("jdks").toString());
        out.put(JK_M2_LOCAL, target.resolve("test-m2").toAbsolutePath().toString());
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
