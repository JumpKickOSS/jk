// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.format;

import static org.assertj.core.api.Assertions.assertThat;

import com.diffplug.spotless.FormatterStep;
import java.io.File;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pipeline shape for {@code jk format}'s Java Spotless steps: import hygiene before the style
 * formatter (Spotless recipe order), with knobs to disable each hygiene step.
 */
class CodeFormatterStepsTest {

    @Test
    void java_pipeline_is_importOrder_removeUnused_then_style() {
        var spec = baseSpec();
        spec.importOrder = true;
        spec.removeUnusedImports = true;

        List<FormatterStep> steps = CodeFormatter.javaSteps(spec);

        assertThat(steps)
                .extracting(FormatterStep::getName)
                .containsExactly("importOrder", "removeUnusedImports", "palantir-java-format");
    }

    @Test
    void google_style_still_ends_with_google_java_format() {
        var spec = baseSpec();
        spec.javaStyle = "google";
        spec.javaVersion = "1.28.0";
        spec.javaJars = jars("/tmp/gjf.jar");
        spec.removeUnusedJars = Set.of();
        spec.importOrder = true;
        spec.removeUnusedImports = true;

        List<FormatterStep> steps = CodeFormatter.javaSteps(spec);

        assertThat(steps)
                .extracting(FormatterStep::getName)
                .containsExactly("importOrder", "removeUnusedImports", "google-java-format");
    }

    @Test
    void hygiene_steps_can_be_disabled_independently() {
        var spec = baseSpec();
        spec.importOrder = false;
        spec.removeUnusedImports = true;
        assertThat(CodeFormatter.javaSteps(spec))
                .extracting(FormatterStep::getName)
                .containsExactly("removeUnusedImports", "palantir-java-format");

        spec.importOrder = true;
        spec.removeUnusedImports = false;
        assertThat(CodeFormatter.javaSteps(spec))
                .extracting(FormatterStep::getName)
                .containsExactly("importOrder", "palantir-java-format");

        spec.importOrder = false;
        spec.removeUnusedImports = false;
        assertThat(CodeFormatter.javaSteps(spec))
                .extracting(FormatterStep::getName)
                .containsExactly("palantir-java-format");
    }

    @Test
    void unnamed_class_probe_uses_already_read_bytes() {
        assertThat(CodeFormatter.isUnnamedClass("void main() { IO.println(1); }".getBytes()))
                .isTrue();
        assertThat(CodeFormatter.isUnnamedClass("public class Foo {}".getBytes()))
                .isFalse();
    }

    private static CodeFormatter.Spec baseSpec() {
        var spec = new CodeFormatter.Spec();
        spec.javaStyle = "palantir";
        spec.javaJars = jars("/tmp/palantir.jar");
        spec.removeUnusedJars = jars("/tmp/gjf.jar");
        return spec;
    }

    private static Set<File> jars(String path) {
        var out = new LinkedHashSet<File>();
        out.add(new File(path));
        return out;
    }
}
