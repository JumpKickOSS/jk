// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.bsp;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.wire.protocol.IdeWireModel;
import java.util.List;
import org.junit.jupiter.api.Test;

class BspTargetOptionsTest {

    private static IdeWireModel model(String siblingScope) {
        return new IdeWireModel(
                null,
                "/ws",
                "demo",
                true,
                List.of("/ws/testkit", "/ws/app"),
                List.of("testkit", "app"),
                List.of("25", "25"),
                List.of("", ""),
                List.of("/ws/testkit/target/classes", "/ws/app/target/classes"),
                List.of("/ws/testkit/target/test-classes", "/ws/app/target/test-classes"),
                List.of("", ""),
                List.of("", ""),
                List.of("", ""),
                List.of("", ""),
                List.of("g:a:jar::1", "g:b:jar::1"),
                List.of("g_a_jar__1", "g_b_jar__1"),
                List.of("/cache/a.jar", "/cache/b.jar"),
                List.of("", ""),
                List.of("1|testkit|" + siblingScope),
                List.of("0|g:a:jar::1|MAIN", "1|g:b:jar::1|TEST"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "",
                "",
                25,
                "",
                "",
                List.of(),
                List.of("java", "java"),
                List.of("", ""),
                List.of());
    }

    @Test
    void a_test_scoped_sibling_stays_on_the_one_target_that_also_holds_the_test_sources() {
        // A module has one BSP target for main and test sources, so its tests need the testkit classes.
        assertThat(BspTargetOptions.classpath(model(IdeWireModel.SCOPE_TEST), 1))
                .anySatisfy(entry -> assertThat(entry).contains("/ws/testkit/target/classes"));
    }

    @Test
    void the_classpath_carries_only_the_module_s_own_libraries() {
        assertThat(BspTargetOptions.classpath(model(IdeWireModel.SCOPE_COMPILE), 1))
                .anySatisfy(entry -> assertThat(entry).contains("/cache/b.jar"))
                .noneSatisfy(entry -> assertThat(entry).contains("/cache/a.jar"));
    }
}
