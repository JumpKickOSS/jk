// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

/**
 * Every path a task declares resolves against its scratch root, so one that is absolute, names the
 * root itself or climbs out of it with {@code ..} is refused where the task declares it.
 */
class TaskSpecPathsTest {

    private static final Map<String, BiConsumer<TaskSpec, String>> FIELDS = Map.of(
            "outputs", TaskSpec::outputs,
            "contributesClasses", TaskSpec::contributesClasses,
            "contributesResources", TaskSpec::contributesResources,
            "contributesSources", TaskSpec::contributesSources,
            "contributesTestSources", TaskSpec::contributesTestSources,
            "contributesTestClasspath", TaskSpec::contributesTestClasspath,
            "contributesTestJvmArgs", TaskSpec::contributesTestJvmArgs,
            "transformsClasses", TaskSpec::transformsClasses);

    private static final List<String> ESCAPES = List.of(
            "..",
            "../../src/main/java",
            "out/../../x",
            Path.of("/").toAbsolutePath().resolve("etc").toString(),
            ".",
            "");

    @Test
    void every_declared_path_outside_the_scratch_is_refused() {
        FIELDS.forEach((field, declare) -> {
            for (String rel : ESCAPES) {
                TaskSpec spec = TaskSpec.named("gen");
                assertThatThrownBy(() -> declare.accept(spec, rel))
                        .as(field + " " + rel)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage(
                                "plugin task gen: " + field + "(\"" + rel + "\") is not under the task's output root");
            }
        });
    }

    @Test
    void a_path_below_the_scratch_is_declared_as_written() {
        TaskSpec spec = TaskSpec.named("gen")
                .outputs("out", "a/./b")
                .contributesClasses("out/classes")
                .contributesTestJvmArgs("out/x/../jvm.args")
                .transformsClasses("out");

        assertThat(spec.declaredOutputs()).containsExactly("out", "a/./b");
        assertThat(spec.classesContributions()).containsExactly("out/classes");
        assertThat(spec.testJvmArgsContributions()).containsExactly("out/x/../jvm.args");
        assertThat(spec.classesTransform()).isEqualTo("out");
    }
}
