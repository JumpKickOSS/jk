// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver.pubgrub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class PubGrubBudgetTest {

    @Test
    void exceeds_max_decisions() {
        // Deep chain forces many decisions.
        InMemoryPackageSource.Builder b = InMemoryPackageSource.builder();
        for (int i = 0; i < 50; i++) {
            final int n = i;
            if (i == 49) {
                b.version("p" + n, "1.0");
            } else {
                b.version("p" + n, "1.0", deps -> deps.require("p" + (n + 1), VersionSet.exact("1.0")));
            }
        }
        PubGrubSolver solver = new PubGrubSolver(b.build(), /* maxDecisions */ 5, /* timeoutMs */ 0);
        assertThatThrownBy(() -> solver.solve("root", "1.0", List.of(Term.positive("p0", VersionSet.exact("1.0")))))
                .isInstanceOf(UnsatisfiableException.class)
                .satisfies(ex -> {
                    String msg = Diagnostics.render(((UnsatisfiableException) ex).rootCause());
                    assertThat(msg).contains("budget exceeded").containsIgnoringCase("decision");
                });
    }

    @Test
    void generous_budget_still_solves() throws Exception {
        PackageSource src = InMemoryPackageSource.builder()
                .version("leaf", "1.0")
                .version("mid", "1.0", deps -> deps.require("leaf", VersionSet.exact("1.0")))
                .build();
        var solution = new PubGrubSolver(src, 1000, 0)
                .solve("root", "1.0", List.of(Term.positive("mid", VersionSet.exact("1.0"))));
        assertThat(solution).containsEntry("leaf", "1.0");
    }
}
