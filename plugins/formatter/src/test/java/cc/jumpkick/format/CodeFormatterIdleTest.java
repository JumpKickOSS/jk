// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.format;

import static org.assertj.core.api.Assertions.assertThat;

import com.diffplug.spotless.Formatter;
import com.diffplug.spotless.LineEnding;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A served worker keeps the idle formatters of the shapes it used last, not of every shape it saw. */
class CodeFormatterIdleTest {

    @Test
    void idle_formatters_are_kept_for_the_shapes_used_last() {
        for (int i = 0; i < CodeFormatter.Formatters.KEPT_SHAPES + 5; i++) {
            new CodeFormatter.Formatters(spec("1." + i + ".0")).giveBack(CodeFormatter.Kind.JAVA, noSteps());
        }
        CodeFormatter.Formatters last = new CodeFormatter.Formatters(spec("2.0.0"));
        Formatter lastGiven = noSteps();
        last.giveBack(CodeFormatter.Kind.JAVA, lastGiven);

        assertThat(CodeFormatter.Formatters.keptShapes()).isLessThanOrEqualTo(CodeFormatter.Formatters.KEPT_SHAPES);
        assertThat(last.borrow(CodeFormatter.Kind.JAVA))
                .as("the shape used last keeps its formatter")
                .isSameAs(lastGiven);
    }

    private static CodeFormatter.Spec spec(String version) {
        var spec = new CodeFormatter.Spec();
        spec.javaStyle = "palantir";
        spec.javaVersion = version;
        spec.javaJars = Set.of(new File("/tmp/palantir-" + version + ".jar"));
        spec.removeUnusedJars = Set.of();
        return spec;
    }

    private static Formatter noSteps() {
        return Formatter.builder()
                .lineEndingsPolicy(LineEnding.UNIX.createPolicy())
                .encoding(StandardCharsets.UTF_8)
                .steps(List.of())
                .build();
    }
}
