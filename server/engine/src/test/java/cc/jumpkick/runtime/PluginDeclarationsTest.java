// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The describe-reply decode — exactly what the content-keyed cache file replays. */
class PluginDeclarationsTest {

    @Test
    void decodes_steps_packager_and_verbs() {
        var decls = PluginDeclarations.decode(List.of(
                "{\"t\":\"step\",\"name\":\"gen\",\"after\":\"compile\",\"before\":\"package\","
                        + "\"inputs\":[\"classes\"],\"outputs\":[\"out\"],\"contributesClasses\":[\"out\"],"
                        + "\"contributesResources\":[],\"contributesSources\":[]}",
                "{\"t\":\"packager\",\"name\":\"pkg\",\"inputs\":[\"classes\"]}",
                "{\"t\":\"command\",\"name\":\"devices\",\"description\":\"list attached devices\"}",
                "{\"t\":\"label\",\"text\":\"noise\"}"));
        assertThat(decls.steps()).hasSize(1);
        assertThat(requireNonNull(decls.step("gen")).contributesClasses()).containsExactly("out");
        assertThat(requireNonNull(decls.packager()).name()).isEqualTo("pkg");
        assertThat(requireNonNull(decls.step("gen")).contributesTestSources()).isEmpty();
        assertThat(decls.commands()).hasSize(1);
        assertThat(requireNonNull(decls.command("devices")).description()).isEqualTo("list attached devices");
        assertThat(decls.command("nope")).isNull();
        assertThat(requireNonNull(decls.step("gen")).oneTestJvm()).isFalse();
    }

    /** A step that declares {@code oneTestJvm} makes the module's suite one JVM; steps that say nothing do not. */
    @Test
    void a_step_declaring_one_test_jvm_pins_the_modules_suite_to_one_jvm() {
        var decls = PluginDeclarations.decode(List.of(
                "{\"t\":\"step\",\"name\":\"quarkus-test-model\",\"inputs\":[\"classes\"],\"outputs\":[\"tm\"],"
                        + "\"contributesTestJvmArgs\":[\"tm/jvm.args\"],\"oneTestJvm\":true}",
                "{\"t\":\"step\",\"name\":\"gen\",\"inputs\":[\"classes\"],\"outputs\":[\"out\"]}"));
        assertThat(requireNonNull(decls.step("quarkus-test-model")).oneTestJvm())
                .isTrue();
        assertThat(requireNonNull(decls.step("gen")).oneTestJvm()).isFalse();
        assertThat(TestLaunch.oneTestJvm(decls)).isTrue();
        assertThat(TestLaunch.oneTestJvm(
                        new PluginDeclarations(List.of(requireNonNull(decls.step("gen"))), null, List.of())))
                .isFalse();
        assertThat(TestLaunch.oneTestJvm(null)).isFalse();
    }
}
