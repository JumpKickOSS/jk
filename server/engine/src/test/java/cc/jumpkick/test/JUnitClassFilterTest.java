// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** {@code --class} patterns as the runner's class-name regex. */
class JUnitClassFilterTest {

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
