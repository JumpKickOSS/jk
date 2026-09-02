// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.testing.PropertyRoots;

/**
 * The CLI's contribution to {@link PropertyRoots#runTeardownHooks}: while an isolated root's
 * overlays are still in force, {@code EnginePaths.current()} resolves to the per-method engine
 * key — stop exactly that engine before the roots are restored and reaped, or per-method daemons
 * accumulate. Registered via {@code META-INF/services}; {@code :host}'s fixture never learns the
 * CLI exists.
 */
public final class EngineStopHook implements PropertyRoots.RootTeardownHook {

    @Override
    public void beforeRootTeardown() {
        EngineTestSupport.stopEngineOnly();
    }
}
