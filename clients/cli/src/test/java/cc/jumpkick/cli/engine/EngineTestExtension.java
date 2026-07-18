// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Auto-registers for the CLI suite via {@code META-INF/services}… actually registered from a global
 * {@code junit-platform.properties} or package-level — simpler: tests that need the engine call
 * {@link EngineTestSupport#ensureEngineMaterialized()} or extend this as {@code @ExtendWith}.
 *
 * <p>Registered globally via {@code junit-platform.properties} {@code junit.jupiter.extensions.autodetection.enabled}
 * is off by default; instead {@link EngineMaterializeListener} uses a launcher session listener.
 * For reliability under Gradle's JUnit Platform, this extension is applied suite-wide from
 * {@code META-INF/services/org.junit.jupiter.api.extension.Extension} when autodetection is enabled
 * in the test task.
 */
public final class EngineTestExtension implements BeforeAllCallback {
    @Override
    public void beforeAll(ExtensionContext context) {
        EngineTestSupport.ensureEngineMaterialized();
    }
}
