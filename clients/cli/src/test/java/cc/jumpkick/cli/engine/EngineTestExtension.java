// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Global CLI suite extension (autodetected):
 *
 * <ul>
 * <li>BeforeAll (per class) — materialize the engine jar into the test {@code JK_HOME}
 * <li>AfterAll (per class) — force-stop so the next class and suite teardown are clean
 * </ul>
 *
 * <p>Warm engine across methods. TempDir cleanup uses {@link
 * JkTempDirDeletionStrategy}: stop engine only when a delete fails, so the former
 * stop-after-each denylist is empty.
 */
public final class EngineTestExtension implements BeforeAllCallback, AfterAllCallback {

    @Override
    public void beforeAll(ExtensionContext context) {
        EngineTestSupport.ensureEngineMaterialized();
    }

    @Override
    public void afterAll(ExtensionContext context) {
        EngineTestSupport.stopEngineAndRelease();
    }
}
