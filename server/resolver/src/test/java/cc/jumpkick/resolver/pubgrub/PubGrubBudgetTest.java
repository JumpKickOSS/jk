// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver.pubgrub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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

    @Test
    void step_budget_covers_propagation_storms_not_only_decides() {
        // Tiny maxDecisions → tiny maxSteps (decisions * STEPS_PER_DECISION). A long dependency
        // chain needs many outer-loop / prop steps even when each package decides once.
        InMemoryPackageSource.Builder b = InMemoryPackageSource.builder();
        int n = PubGrubSolver.STEPS_PER_DECISION * 3;
        for (int i = 0; i < n; i++) {
            final int idx = i;
            if (i == n - 1) {
                b.version("p" + idx, "1.0");
            } else {
                b.version("p" + idx, "1.0", deps -> deps.require("p" + (idx + 1), VersionSet.exact("1.0")));
            }
        }
        // maxDecisions=2 → maxSteps = 32; chain of length ~48 burns steps quickly.
        PubGrubSolver solver = new PubGrubSolver(b.build(), /* maxDecisions */ 2, /* timeoutMs */ 0);
        assertThatThrownBy(() -> solver.solve("root", "1.0", List.of(Term.positive("p0", VersionSet.exact("1.0")))))
                .isInstanceOf(UnsatisfiableException.class)
                .satisfies(ex -> {
                    String msg = Diagnostics.render(((UnsatisfiableException) ex).rootCause());
                    assertThat(msg).contains("budget exceeded");
                    assertThat(msg.toLowerCase()).containsAnyOf("step", "decision");
                });
    }

    @Test
    @Timeout(5)
    void unsat_graph_terminates_without_spinning() {
        // Classic diamond conflict: a wants leaf 1, b wants leaf 2 — no solution, must not hang.
        PackageSource src = InMemoryPackageSource.builder()
                .version("a", "1.0", deps -> deps.require("leaf", VersionSet.exact("1.0")))
                .version("b", "1.0", deps -> deps.require("leaf", VersionSet.exact("2.0")))
                .version("leaf", "1.0")
                .version("leaf", "2.0")
                .build();
        PubGrubSolver solver = new PubGrubSolver(src, 10_000, 5_000);
        long start = System.nanoTime();
        assertThatThrownBy(() -> solver.solve(
                        "root",
                        "1.0",
                        List.of(
                                Term.positive("a", VersionSet.exact("1.0")),
                                Term.positive("b", VersionSet.exact("1.0")))))
                .isInstanceOf(UnsatisfiableException.class);
        long ms = (System.nanoTime() - start) / 1_000_000L;
        assertThat(ms).as("unsat should fail closed quickly, not thrash").isLessThan(2_000L);
    }

    @Test
    @Timeout(5)
    void conflict_with_missing_version_terminates() {
        // leaf only has 1.0; b requires 2.0 → must fail closed quickly.
        PackageSource src = new PackageSource() {
            @Override
            public List<String> versions(String pkg) {
                return List.of("1.0");
            }

            @Override
            public List<Term> dependencies(String pkg, String version) throws IOException, InterruptedException {
                if (pkg.equals("a")) {
                    return List.of(Term.positive("leaf", VersionSet.exact("1.0")));
                }
                if (pkg.equals("b")) {
                    return List.of(Term.positive("leaf", VersionSet.exact("2.0")));
                }
                return List.of();
            }
        };

        PubGrubSolver solver = new PubGrubSolver(src, 50_000, 0);
        long start = System.nanoTime();
        assertThatThrownBy(() -> solver.solve(
                        "root",
                        "1.0",
                        List.of(
                                Term.positive("a", VersionSet.exact("1.0")),
                                Term.positive("b", VersionSet.exact("1.0")))))
                .isInstanceOf(UnsatisfiableException.class)
                .satisfies(ex -> {
                    String msg = Diagnostics.render(((UnsatisfiableException) ex).rootCause());
                    assertThat(msg.toLowerCase()).containsAnyOf("budget", "loop", "watermark", "no version", "cannot");
                });
        long ms = (System.nanoTime() - start) / 1_000_000L;
        assertThat(ms).as("must not spin for minutes").isLessThan(5_000L);
    }

    @Test
    void steps_per_decision_constant_is_sane() {
        assertThat(PubGrubSolver.STEPS_PER_DECISION).isGreaterThanOrEqualTo(4);
        long defaultSteps = (long) PubGrubSolver.DEFAULT_MAX_DECISIONS * PubGrubSolver.STEPS_PER_DECISION;
        assertThat(defaultSteps).isEqualTo(1_600_000L);
    }

    /**
     * Prefer-pin backtrack (existing interning scenario) still finds a solution — watermarks must
     * not turn a legitimate alternate assignment into a hard unsat.
     */
    @Test
    @Timeout(5)
    void watermark_allows_prefer_pin_backtrack() throws Exception {
        InMemoryPackageSource.Builder b = InMemoryPackageSource.builder();
        for (int v = 100; v >= 1; v--) {
            b.version("shared", v + ".0.0");
        }
        b.version("a", "1.0", deps -> deps.require("shared", VersionSet.atLeast("1.0", true)));
        b.version("b", "1.0", deps -> deps.require("shared", VersionSet.atLeast("90.0", true)));

        PackageSource base = b.build();
        PackageSource src = new PackageSource() {
            @Override
            public List<String> versions(String pkg) throws IOException, InterruptedException {
                List<String> v = new java.util.ArrayList<>(base.versions(pkg));
                if (pkg.equals("shared")) {
                    v.remove("50.0.0");
                    v.add(0, "50.0.0");
                }
                return v;
            }

            @Override
            public List<Term> dependencies(String pkg, String version) throws IOException, InterruptedException {
                return base.dependencies(pkg, version);
            }
        };

        Map<String, String> solution = new PubGrubSolver(src, 50_000, 0)
                .solve(
                        "root",
                        "1.0",
                        List.of(
                                Term.positive("a", VersionSet.exact("1.0")),
                                Term.positive("b", VersionSet.exact("1.0"))));

        assertThat(solution.get("shared")).isNotNull();
        // Prefer 50 is lifted; solution must meet b's floor.
        assertThat(solution.get("shared").split("\\.")[0]).asString().satisfies(major -> {
            assertThat(Integer.parseInt(major)).isGreaterThanOrEqualTo(90);
        });
    }
}
