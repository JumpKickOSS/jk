// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SuiteClassFilterTest {

    private static final Map<String, List<String>> FAILSAFE = Map.of("integration", List.of("IT*", "*IT", "*ITCase"));

    @Test
    void the_default_suite_leaves_every_pattern_suite_class_out(@TempDir Path module) {
        SuiteClassFilter suites = SuiteClassFilter.of(module, false, List.of("test"), FAILSAFE);
        Pattern runs = runs(suites);

        assertThat(runs.matcher("com.acme.FooTest").matches()).isTrue();
        assertThat(runs.matcher("com.acme.Items").matches()).isTrue();
        assertThat(runs.matcher("com.acme.FooIT").matches()).isFalse();
        assertThat(runs.matcher("com.acme.ITFoo").matches()).isFalse();
        assertThat(runs.matcher("com.acme.FooITCase").matches()).isFalse();
        assertThat(runs.matcher("com.acme.ITFoo$Nested").matches())
                .as("a nested class goes with its outer class")
                .isFalse();
        assertThat(suites.describe())
                .isEqualTo("test suites: test — left out: integration (IT*, *IT, *ITCase),"
                        + " run with jk test --suite <name>");
    }

    @Test
    void a_pattern_suite_runs_only_its_classes(@TempDir Path module) {
        SuiteClassFilter suites = SuiteClassFilter.of(module, false, List.of("integration"), FAILSAFE);
        Pattern runs = runs(suites);

        assertThat(runs.matcher("com.acme.FooIT").matches()).isTrue();
        assertThat(runs.matcher("com.acme.ITFoo$Nested").matches()).isTrue();
        assertThat(runs.matcher("com.acme.FooTest").matches()).isFalse();
        assertThat(suites.describe())
                .isEqualTo("test suites: integration — integration runs the test suite's classes matching"
                        + " IT*, *IT, *ITCase");
        assertThat(suites.ownersLeftOut("com.acme.FooTest")).containsExactly("test");
        assertThat(suites.ownersLeftOut("com.acme.FooIT")).isEmpty();
    }

    @Test
    void the_guard_and_all_keep_every_class() {
        Path module = Path.of("unused");
        assertThat(SuiteClassFilter.of(module, false, List.of("test", "integration"), FAILSAFE)
                        .body())
                .isNull();
        assertThat(SuiteClassFilter.of(module, false, List.of("test"), Map.of()))
                .isSameAs(SuiteClassFilter.NONE);
        assertThat(SuiteClassFilter.NONE.body()).isNull();
        assertThat(SuiteClassFilter.NONE.describe()).isNull();
    }

    @Test
    void another_suite_directory_keeps_its_classes_whatever_their_names(@TempDir Path module) throws Exception {
        Path e2e = Files.createDirectories(module.resolve("src/e2e/java/com/acme"));
        Files.writeString(e2e.resolve("CheckoutIT.java"), "package com.acme; class CheckoutIT {}");
        SuiteClassFilter suites = SuiteClassFilter.of(module, false, List.of("test", "e2e"), FAILSAFE);
        Pattern runs = runs(suites);

        assertThat(runs.matcher("com.acme.CheckoutIT").matches()).isTrue();
        assertThat(runs.matcher("com.acme.FooIT").matches()).isFalse();
        assertThat(runs.matcher("com.acme.FooTest").matches()).isTrue();
    }

    @Test
    void a_class_pattern_naming_a_left_out_class_names_its_suite(@TempDir Path classes) throws Exception {
        Files.createDirectories(classes.resolve("com/acme"));
        for (String name : List.of("FooTest", "FooIT", "FooIT$Inner", "BarIT")) {
            Files.writeString(classes.resolve("com/acme/" + name + ".class"), "");
        }
        SuiteClassFilter suites = SuiteClassFilter.of(classes, false, List.of("test"), FAILSAFE);

        assertThat(suites.classHint(classes, List.of("FooIT")))
                .isEqualTo("FooIT is in the integration suite (jk test --suite integration --class FooIT)");
        assertThat(suites.classHint(classes, List.of("*IT")))
                .isEqualTo("BarIT, FooIT are in the integration suite (jk test --suite integration --class *IT)");
        assertThat(suites.classHint(classes, List.of("FooTest"))).isNull();
        assertThat(SuiteClassFilter.of(classes, false, List.of("integration"), FAILSAFE)
                        .classHint(classes, List.of("FooTest")))
                .isEqualTo("FooTest is in the test suite (jk test --suite test --class FooTest)");
    }

    private static Pattern runs(SuiteClassFilter suites) {
        return Pattern.compile(JUnitClassFilter.filter(null, null, suites.body()));
    }
}
