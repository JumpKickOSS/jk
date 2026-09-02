// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.PropertyRoots;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;

/**
 * The engine-stop hook must be discoverable, or isolated-root teardown silently stops stopping
 * per-method engine daemons — a typo in the {@code META-INF/services} file is invisible at
 * compile time and shows up only as daemon accumulation.
 */
class EngineStopHookTest {

    @Test
    void the_service_loader_finds_the_cli_teardown_hook() {
        assertThat(ServiceLoader.load(PropertyRoots.RootTeardownHook.class))
                .anySatisfy(hook -> assertThat(hook).isInstanceOf(EngineStopHook.class));
    }
}
