// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.DeclaredSuite;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Suite claims the runner must apply: a class suite's exclude-tags, and a directory suite's Kotlin classes. */
class SuiteFilterClaimsTest {

    @Test
    void a_class_pattern_suite_s_exclude_tags_reach_the_runner_when_no_suite_claims_a_tag(@TempDir Path module) {
        Map<String, DeclaredSuite> declared =
                Map.of("integration", new DeclaredSuite(List.of("*IT"), List.of(), List.of(), List.of("slow")));
        SuiteFilter run = SuiteFilter.of(module, false, List.of("integration"), declared);

        assertThat(run.members())
                .as("a tagged slow test in a *IT class runs in no suite, so the runner must be told")
                .anySatisfy(member -> assertThat(member.split("\\|", -1)[1]).contains("slow"));
    }

    @Test
    void a_kotlin_suite_class_outside_its_package_directory_stays_out_of_the_default_suite(@TempDir Path module)
            throws Exception {
        Files.createDirectories(module.resolve("src/test/java/com/acme"));
        Files.writeString(
                module.resolve("src/test/java/com/acme/UnitTest.java"), "package com.acme; class UnitTest {}");
        Files.createDirectories(module.resolve("src/integration/kotlin"));
        Files.writeString(
                module.resolve("src/integration/kotlin/OrderSpecs.kt"),
                "package com.acme\nclass FirstIT { @org.junit.jupiter.api.Test fun ok() {} }\n");
        Map<String, DeclaredSuite> declared = Map.of("slow", DeclaredSuite.ofTags(List.of("slow")));

        SuiteFilter unitAndSlow = SuiteFilter.of(module, false, List.of("test", "slow"), declared);
        SuiteFilter beside = Objects.requireNonNull(unitAndSlow.part("test"));
        Pattern runs = Pattern.compile(JUnitClassFilter.filter(null, null, beside.body()));

        assertThat(runs.matcher("com.acme.UnitTest").matches()).isTrue();
        assertThat(runs.matcher("com.acme.FirstIT").matches())
                .as("FirstIT is compiled from src/integration, a suite this run did not select")
                .isFalse();
    }
}
