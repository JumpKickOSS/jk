// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.SpecWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A step that declares the module's source roots reads the ones the engine resolved, main and test apart. */
class SourceRootsInputTest {

    @Test
    void source_roots_is_a_declared_input_with_its_own_wire_spelling() {
        assertThat(In.sourceRoots().wireName()).isEqualTo("source-roots");
        assertThat(In.fromWire("source-roots")).isEqualTo(In.sourceRoots());
    }

    @Test
    void the_harness_hands_the_main_and_test_roots_to_the_body(@TempDir Path tmp) throws Exception {
        Path main = tmp.resolve("src/main/java");
        Path generated = tmp.resolve("src/gen/java");
        Path test = tmp.resolve("src/test/java");
        Path spec = new SpecWriter()
                .op(PluginProtocol.OP_RUN_STEP, "helidon-binding", "jk-helidon")
                .sourceRoots(List.of(main, generated), List.of(test))
                .writeTempSpec();

        BuildPluginHarness.Spec.SourceRoots read =
                Objects.requireNonNull(BuildPluginHarness.Spec.read(spec).sourceRoots());

        assertThat(read.main()).containsExactly(main, generated);
        assertThat(read.test()).containsExactly(test);
    }

    @Test
    void a_spec_without_them_says_the_step_did_not_declare_them(@TempDir Path tmp) throws Exception {
        Path spec = new SpecWriter()
                .op(PluginProtocol.OP_RUN_STEP, "helidon-binding", "jk-helidon")
                .writeTempSpec();

        assertThat(BuildPluginHarness.Spec.read(spec).sourceRoots()).isNull();
    }
}
