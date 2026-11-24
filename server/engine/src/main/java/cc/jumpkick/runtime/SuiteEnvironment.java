// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.SelfHostEngine.needsNestedEngineIsolation;
import static cc.jumpkick.runtime.SelfHostEngine.nestedEngineTestEnv;

import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.ManifestNames;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Profile;
import cc.jumpkick.node.NodeHome;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.runtime.base.CompileSupport;
import cc.jumpkick.runtime.base.TestEnv;
import cc.jumpkick.runtime.base.TestStoreSeed;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The environment a module's suite JVMs run in: the sandbox and the module's declared variables,
 * the nested-engine isolation, the locked Node.js and the active profile's {@code env}. A run-tests
 * stamp keys this class with the suite code ({@link cc.jumpkick.task.KeyContract#SUITE}), and what
 * it sets on every host by value ({@link SuiteEnvKeys#environment}).
 */
final class SuiteEnvironment {

    private SuiteEnvironment() {}

    /** The active profile's {@code env}: what every forked test JVM of this run is given on top of {@code [test] env}. */
    private static Map<String, String> profileEnv(JkBuild project, @Nullable String profileName) {
        Profile profile = CompileSupport.resolveProfile(project.profiles(), profileName);
        return profile == null ? Map.of() : profile.env();
    }

    /**
     * Sandboxed JK_HOME/JK_M2_LOCAL plus this module's [test] env — without it a forked test JVM
     * inherits the engine's environment and runs against the developer's real product layout.
     * Nested-engine suites (e2e/cli) isolate JK_STATE_DIR so EngineTestExtension cannot kill the
     * host engine running this test step; that isolation layers on top and wins on any key both set.
     */
    private static WorkerEnv testEnvironment(
            Path dir, JkBuild projectUnderTest, BuildLayout layout, @Nullable NodeHome node) throws Exception {
        warmTestRoots();
        WorkerEnv testEnv = TestEnv.forModule(projectUnderTest, dir, layout);
        if (needsNestedEngineIsolation(projectUnderTest)) {
            testEnv = testEnv.with(nestedEngineTestEnv(dir));
        }
        SiblingJars.stageSiblingRulePacks(dir, projectUnderTest, testEnv.extras());
        if (node != null) {
            // A module that declares node runs its tests with the locked Node.js first on PATH.
            testEnv = testEnv.with(Map.of(
                    "NODE_HOME",
                    node.home().toString(),
                    "PATH",
                    node.path(testEnv.environment().get("PATH"))));
        }
        return testEnv;
    }

    private static final Object TEST_ROOTS_WARMED = new Object();

    private static volatile boolean testRootsWarm;

    /**
     * Puts the injected test roots' POMs and version lists into this engine's store, once, by locking
     * a throwaway project the way a fixture does. The sandbox seed ({@link TestStoreSeed}) can only
     * hand a walled-off suite what this store holds, and a store filled from a lock holds jars alone.
     * Warmth only: a lock that fails (offline, no network) leaves the fixtures to fetch.
     */
    private static void warmTestRoots() {
        if (testRootsWarm) return;
        synchronized (TEST_ROOTS_WARMED) {
            if (testRootsWarm) return;
            testRootsWarm = true;
            if (TestStoreSeed.holdsTestRoots()) return;
            Path dir = null;
            try {
                dir = Files.createTempDirectory(Files.createDirectories(JkDirs.cache()), "test-roots-");
                Path project = Files.createDirectories(dir.resolve("project"));
                Files.writeString(project.resolve(ManifestNames.MANIFEST), """
                        name    = "test-roots"
                        group   = "jk.internal"
                        version = "0.0.0"
                        java    = 25
                        """);
                var lock = LockPlans.lockBuildPlan(
                                project,
                                Files.createDirectories(dir.resolve("cache")),
                                null,
                                List.of(),
                                true,
                                false,
                                ResolveObserver.NOOP,
                                null)
                        .run();
                if (!lock.errors().isEmpty()) {
                    Log.debug("SuiteEnvironment: the test roots stay cold: "
                            + lock.errors().getFirst().message());
                }
            } catch (Exception e) {
                Log.debug("SuiteEnvironment: the test roots stay cold; fixtures fetch them", e);
            } finally {
                PathUtil.deleteRecursively(dir);
            }
        }
    }

    /**
     * The environment the suite JVMs of {@code project} in {@code dir} get: its {@link
     * #testEnvironment}, the locked {@code node} first on {@code PATH} when the module declares one,
     * with the active profile's {@code env} on top. Each launch adds its suites' own {@code env}, and
     * its fork's defaults ({@link cc.jumpkick.test.ForkSpec}) under it.
     */
    static WorkerEnv of(
            Path dir, JkBuild project, BuildLayout layout, @Nullable NodeHome node, @Nullable String profileName)
            throws Exception {
        return testEnvironment(dir, project, layout, node).with(profileEnv(project, profileName));
    }
}
