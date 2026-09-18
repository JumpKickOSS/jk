// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** {@code --class} patterns as the runner's class-name regex and method arguments. */
class JUnitClassFilterTest {

    @Test
    void a_method_suffix_selects_the_class_by_its_class_half() {
        Pattern re = Pattern.compile(JUnitClassFilter.patternRegex(List.of("FooTest#adds")));
        assertThat(re.matcher("com.acme.FooTest").matches()).isTrue();
        assertThat(re.matcher("com.acme.FooTest#adds").matches()).isFalse();
    }

    @Test
    void a_method_suffix_becomes_a_runner_method_argument_and_whole_classes_ride_beside_it() {
        assertThat(JUnitClassFilter.methodArgs(List.of("com.acme.FooTest#adds", "BarTest")))
                .containsExactly("--method=^(\\Qcom.acme.FooTest\\E)$#adds", "--method=^((.*\\.)?\\QBarTest\\E)$#*");
        assertThat(JUnitClassFilter.methodArgs(List.of("FooTest", "*IT")))
                .as("no pattern names a method: the class filter alone selects")
                .isEmpty();
    }

    @Test
    void the_launcher_hands_every_test_running_jvm_the_method_arguments() {
        JUnitLauncher launcher = new JUnitLauncher().withClassPatterns(List.of("com.acme.FooTest#adds"));
        assertThat(launcher.pullWorkerArgs(1, Path.of("classes")))
                .contains("--filter=^(\\Qcom.acme.FooTest\\E)$", "--method=^(\\Qcom.acme.FooTest\\E)$#adds");
        assertThat(new JUnitLauncher().withClassPatterns(List.of("FooTest")).pullWorkerArgs(1, Path.of("classes")))
                .noneMatch(a -> a.startsWith("--method="));
    }

    @Test
    void a_simple_name_matches_that_class_in_any_package() {
        Pattern re = Pattern.compile(JUnitClassFilter.patternRegex(List.of("FooTest")));
        assertThat(re.matcher("com.acme.FooTest").matches()).isTrue();
        assertThat(re.matcher("FooTest").matches()).isTrue();
        assertThat(re.matcher("com.acme.BarFooTest").matches()).isFalse();
        assertThat(re.matcher("com.acme.FooTestCase").matches()).isFalse();
    }

    @Test
    void a_qualified_name_is_exact() {
        Pattern re = Pattern.compile(JUnitClassFilter.patternRegex(List.of("com.acme.FooTest")));
        assertThat(re.matcher("com.acme.FooTest").matches()).isTrue();
        assertThat(re.matcher("org.other.FooTest").matches()).isFalse();
        assertThat(re.matcher("com.acmeXFooTest").matches())
                .as("the dot is literal")
                .isFalse();
    }

    @Test
    void wildcards_and_several_patterns_union() {
        Pattern re = Pattern.compile(JUnitClassFilter.patternRegex(List.of("*IT", "com.acme.db.*")));
        assertThat(re.matcher("com.acme.OrdersIT").matches()).isTrue();
        assertThat(re.matcher("com.acme.db.PoolTest").matches()).isTrue();
        assertThat(re.matcher("com.acme.OrdersTest").matches()).isFalse();
    }
}
