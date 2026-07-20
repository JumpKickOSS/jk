// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import java.util.Set;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Global CLI suite extension (autodetected):
 *
 * <ul>
 *   <li>BeforeAll (per class) — materialize the engine jar into the test {@code JK_HOME}
 *   <li>AfterEach — force-stop only for classes that keep project-tree FDs open across methods
 *       (TempDir cleanup fails otherwise). Other classes reuse a warm engine (ticket-1042).
 *   <li>AfterAll (per class) — always force-stop so the next class and suite teardown are clean
 * </ul>
 *
 * <p>TempDir cleanup uses the default JUnit mode (ticket-1022). forceStop waits for process death
 * (ticket-1043). Shared dep cache lives outside {@code @TempDir} ({@code jk.test.cache.dir}).
 */
public final class EngineTestExtension implements BeforeAllCallback, AfterEachCallback, AfterAllCallback {

    /**
     * Wire tests that leave engine-held files under method-scoped {@code @TempDir} trees. Measured
     * by TempDirDeletionException when class-scoped stop alone was enabled (ticket-1042).
     */
    private static final Set<String> STOP_ENGINE_AFTER_EACH = Set.of(
            "cc.jumpkick.cli.ide.IdeEngineClientTest",
            "cc.jumpkick.command.BuildCacheTest",
            "cc.jumpkick.command.BuildCommandTest",
            "cc.jumpkick.command.IdeCommandTest",
            "cc.jumpkick.command.IdeIdeaGenerationTest",
            "cc.jumpkick.command.InstallAndBuildTest",
            "cc.jumpkick.command.ReadSideIntegrationTest",
            "cc.jumpkick.command.VscodeCommandTest");

    @Override
    public void beforeAll(ExtensionContext context) {
        EngineTestSupport.ensureEngineMaterialized();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        if (STOP_ENGINE_AFTER_EACH.contains(context.getRequiredTestClass().getName())) {
            EngineTestSupport.stopEngineOnly();
        }
    }

    @Override
    public void afterAll(ExtensionContext context) {
        EngineTestSupport.stopEngineAndRelease();
    }
}
