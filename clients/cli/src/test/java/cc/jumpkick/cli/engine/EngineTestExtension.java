// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Global CLI suite extension (autodetected):
 *
 * <ul>
 *   <li>BeforeAll — materialize the engine jar into the test {@code JK_HOME}
 *   <li>AfterEach — force-stop the resident engine so method-scoped {@code @TempDir} trees can be
 *       deleted (CAS hardlinks from the engine process otherwise keep files open)
 *   <li>AfterAll — force-stop again and best-effort delete the short {@code JK_STATE_DIR}
 * </ul>
 *
 * <p>Stopping after every test costs a spawn for the next engine-using test; AOT keeps that in the
 * low-millis range (ticket-1021).
 */
public final class EngineTestExtension implements BeforeAllCallback, AfterEachCallback, AfterAllCallback {

    @Override
    public void beforeAll(ExtensionContext context) {
        EngineTestSupport.ensureEngineMaterialized();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        EngineTestSupport.stopEngineOnly();
    }

    @Override
    public void afterAll(ExtensionContext context) {
        EngineTestSupport.stopEngineAndRelease();
    }
}
