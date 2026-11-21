// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.jsonl.Jsonl;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    /**
     * Every declared path of a step resolves against its scratch: a reply that declares one that is
     * absolute, names the scratch itself or climbs out of it is refused before anything resolves it.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "outputs",
                "contributesClasses",
                "contributesResources",
                "contributesSources",
                "contributesTestSources",
                "contributesTestClasspath",
                "contributesTestJvmArgs",
                "transformsClasses"
            })
    void a_declared_path_outside_the_step_scratch_is_refused(String field) {
        String outside = Path.of("/").toAbsolutePath().resolve("etc").toString();
        for (String rel : List.of("..", "../../src/main/java", "out/../../x", outside, ".", "")) {
            boolean single = field.equals("transformsClasses");
            if (single && rel.isEmpty()) continue; // an empty transform is no transform
            String value = single ? Jsonl.quote(rel) : "[" + Jsonl.quote(rel) + "]";
            String line = "{\"t\":\"step\",\"name\":\"gen\",\"inputs\":[\"classes\"],\"" + field + "\":" + value + "}";

            assertThatThrownBy(() -> PluginDeclarations.decode(List.of(line)))
                    .as(field + " " + rel)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("plugin task gen: " + field + "(\"" + rel + "\") is not under the task's output root");
        }
    }

    /** A path below the scratch, nested or not, is read as declared; an empty transform is no transform. */
    @Test
    void a_declared_path_below_the_step_scratch_is_kept() {
        var decls = PluginDeclarations.decode(List.of("{\"t\":\"step\",\"name\":\"gen\",\"inputs\":[\"classes\"],"
                + "\"outputs\":[\"out\",\"a/./b\"],\"contributesClasses\":[\"out/classes\"],"
                + "\"contributesTestJvmArgs\":[\"out/x/../jvm.args\"],\"transformsClasses\":\"\"}"));
        TaskDecl step = requireNonNull(decls.step("gen"));
        assertThat(step.outputs()).containsExactly("out", "a/./b");
        assertThat(step.contributesClasses()).containsExactly("out/classes");
        assertThat(step.contributesTestJvmArgs()).containsExactly("out/x/../jvm.args");
        assertThat(step.transforms()).isFalse();
    }
}
