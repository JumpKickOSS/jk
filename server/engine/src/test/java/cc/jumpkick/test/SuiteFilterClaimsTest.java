// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;


import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.DeclaredSuite;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Suite claims the runner must apply. */
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
}
