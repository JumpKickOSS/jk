// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * In a JVM that may not start an engine, the engine extension materializes nothing: no engine could
 * serve the class, and reading the engine assembly would key every later class of the JVM by the
 * engine's bytes.
 */
class EngineTestExtensionTest {

    @Test
    void a_fast_tier_jvm_materializes_no_engine() {
        assumeTrue(EngineSpawn.spawnForbidden(), "runs in the fast tier, where the launcher forbids an engine");
        ExtensionContext untouched = (ExtensionContext) Proxy.newProxyInstance(
                ExtensionContext.class.getClassLoader(),
                new Class<?>[] {ExtensionContext.class},
                (proxy, method, args) -> {
                    throw new AssertionError("the extension asked the context for " + method.getName());
                });

        new EngineTestExtension().beforeAll(untouched);

        assertThat(EngineTestSupport.materialized())
                .as("no engine is materialized where none may start")
                .isFalse();
    }
}
