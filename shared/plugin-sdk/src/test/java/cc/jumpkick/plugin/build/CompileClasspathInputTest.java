// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.SpecWriter;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A step may declare the module's compile classpath as an input beside the runtime one, and the
 * harness hands each to the body by the role the engine wrote it under: a {@code provided} jar is
 * on the compile classpath and not the runtime closure, and a step that reads contracts out of
 * jars needs both views.
 */
class CompileClasspathInputTest {

    @Test
    void compile_classpath_is_a_declared_input_with_its_own_wire_spelling() {
        assertThat(In.compileClasspath().wireName()).isEqualTo("compile-classpath");
        assertThat(In.fromWire("compile-classpath")).isEqualTo(In.compileClasspath());
        assertThat(In.compileClasspath().kind()).isEqualTo(In.Kind.COMPILE_CLASSPATH);
    }

    @Test
    void the_harness_hands_each_classpath_to_the_body_by_its_role(@TempDir Path tmp) throws Exception {
        Path runtimeOnly = tmp.resolve("driver-1.0.jar");
        Path shared = tmp.resolve("api-1.0.jar");
        Path compileOnly = tmp.resolve("contracts-1.0.jar");
        Path spec = new SpecWriter()
                .op(PluginProtocol.OP_RUN_STEP, "protoc", "jk-protobuf")
                .cp(runtimeOnly, PluginProtocol.ROLE_RUNTIME)
                .cp(shared, PluginProtocol.ROLE_RUNTIME)
                .cp(shared, PluginProtocol.ROLE_COMPILE)
                .cp(compileOnly, PluginProtocol.ROLE_COMPILE)
                .writeTempSpec();

        BuildPluginHarness.Spec read = BuildPluginHarness.Spec.read(spec);

        assertThat(read.runtimeClasspath()).containsExactly(runtimeOnly, shared);
        assertThat(read.compileClasspath()).containsExactly(shared, compileOnly);
    }
}
