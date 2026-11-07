// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.SpecWriter;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two spec facts a step reads: the module's test classes ({@code In.testClasses()}, for a TEST-window
 * step such as Helidon's test binding) and whether the build is a recompile ({@code jk compile},
 * {@code jk dev}'s rebuild). A spec without either line reads as none and as a full build.
 */
class TestClassesAndRecompileSpecTest {

    @Test
    void test_classes_is_a_declared_input_with_its_own_wire_spelling() {
        assertThat(In.testClasses().wireName()).isEqualTo("test-classes");
        assertThat(In.fromWire("test-classes")).isEqualTo(In.testClasses());
    }

    @Test
    void recompile_is_a_declared_input_with_its_own_wire_spelling() {
        assertThat(In.recompile().wireName()).isEqualTo("recompile");
        assertThat(In.fromWire("recompile")).isEqualTo(In.recompile());
    }

    @Test
    void the_harness_hands_the_test_classes_and_the_recompile_to_the_body(@TempDir Path tmp) throws Exception {
        Path testClasses = tmp.resolve("target/test-classes");
        Path spec = new SpecWriter()
                .op(PluginProtocol.OP_RUN_STEP, "helidon-test-binding", "helidon")
                .testClasses(testClasses)
                .recompile()
                .writeTempSpec();

        BuildPluginHarness.Spec read = BuildPluginHarness.Spec.read(spec);

        assertThat(read.testClassesDir()).isEqualTo(testClasses);
        assertThat(read.recompile()).isTrue();
    }

    @Test
    void a_spec_without_them_is_a_full_build_with_no_test_classes() throws Exception {
        Path spec = new SpecWriter()
                .op(PluginProtocol.OP_RUN_STEP, "helidon-binding", "helidon")
                .writeTempSpec();

        BuildPluginHarness.Spec read = BuildPluginHarness.Spec.read(spec);

        assertThat(read.testClassesDir()).isNull();
        assertThat(read.recompile()).isFalse();
    }
}
