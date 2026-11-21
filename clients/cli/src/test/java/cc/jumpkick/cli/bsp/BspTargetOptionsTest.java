// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.bsp;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.WorkingDirectory;
import cc.jumpkick.wire.protocol.IdeWireModel;
import java.nio.file.Path;
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

    @Test
    void the_run_environment_holds_the_main_classpath_of_the_module_and_what_it_depends_on() {
        assertThat(BspTargetOptions.runtimeClasspath(model(IdeWireModel.SCOPE_COMPILE), 1, false))
                .containsExactly(u("/ws/app/target/classes"), u("/ws/testkit/target/classes"), u("/cache/a.jar"));
    }

    @Test
    void the_test_environment_adds_the_test_classes_and_the_libraries_only_tests_use() {
        assertThat(BspTargetOptions.runtimeClasspath(model(IdeWireModel.SCOPE_COMPILE), 1, true))
                .containsExactly(
                        u("/ws/app/target/test-classes"),
                        u("/ws/app/target/classes"),
                        u("/ws/testkit/target/classes"),
                        u("/cache/b.jar"),
                        u("/cache/a.jar"));
    }

    @Test
    void a_test_scoped_sibling_is_on_the_test_environment_alone() {
        IdeWireModel model = model(IdeWireModel.SCOPE_TEST);
        assertThat(BspTargetOptions.runtimeClasspath(model, 1, false)).containsExactly(u("/ws/app/target/classes"));
        assertThat(BspTargetOptions.runtimeClasspath(model, 1, true))
                .contains(u("/ws/testkit/target/classes"), u("/cache/a.jar"))
                .doesNotContain(u("/ws/testkit/target/test-classes"));
    }

    @Test
    void a_tests_kind_sibling_adds_its_test_classes_to_the_test_environment() {
        assertThat(BspTargetOptions.runtimeClasspath(model(IdeWireModel.SCOPE_TEST_KIND), 1, true))
                .contains(u("/ws/testkit/target/test-classes"), u("/ws/testkit/target/classes"));
    }

    @Test
    void a_library_is_test_only_when_every_scope_it_has_is_a_test_one() {
        assertThat(BspTargetOptions.testOnly("TEST")).isTrue();
        assertThat(BspTargetOptions.testOnly("TEST,TEST_DEV")).isTrue();
        assertThat(BspTargetOptions.testOnly("MAIN,TEST")).isFalse();
        assertThat(BspTargetOptions.testOnly("PROVIDED")).isFalse();
    }

    /** {@code path} as the server writes it in a classpath. */
    private static String u(String path) {
        return WorkingDirectory.resolve(Path.of(path)).toUri().toString();
    }
}
