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
 *   <li>AfterEach — stop engine only for classes that still pin project-tree FDs (see denylist)
 *   <li>AfterAll (per class) — force-stop so the next class and suite teardown are clean
 * </ul>
 *
 * <p>Warm engine across methods for most classes (ticket-1042). {@link JkTempDirFactory} retries
 * TempDir delete and force-stops as a last resort (ticket-1052). The denylist is the documented
 * exception set where OS hardlinks/open jars still require stop-after-each until engine FD
 * lifetime is fully fixed.
 */
public final class EngineTestExtension implements BeforeAllCallback, AfterEachCallback, AfterAllCallback {

    /**
     * Wire tests that leave engine-held files under method-scoped {@code @TempDir} trees even with
     * {@link JkTempDirFactory} retry (ticket-1052). Prefer shrinking this set as FD hygiene improves.
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
