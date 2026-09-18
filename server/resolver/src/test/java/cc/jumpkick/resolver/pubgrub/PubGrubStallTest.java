// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver.pubgrub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The solver's time budget is a stall window, not a wall clock: a read that never answers stops
 * the solve and is named, while a solve that keeps advancing runs for as long as it needs.
 */
class PubGrubStallTest {

    /** Every package has one version; {@code stuck} never answers for its dependencies. */
    private static PackageSource stallingOn(String stuck) {
        return new PackageSource() {
            @Override
            public List<String> versions(String pkg) {
                return List.of("1.0");
            }

            @Override
            public List<Term> dependencies(String pkg, String version) throws InterruptedException {
                if (pkg.equals(stuck)) new CountDownLatch(1).await();
                return List.of();
            }

            @Override
            public String waitingOn() {
                return "waiting on https://repo.example/never/" + stuck + ".pom (1 s)";
            }
        };
    }

    @Test
    @Timeout(20)
    void a_read_that_never_answers_stops_the_solve_and_is_named() {
        PubGrubSolver solver = new PubGrubSolver(stallingOn("stuck"), 1_000, /* stallWindowMs */ 200);
        assertThatThrownBy(() -> solver.solve(
                        "root",
                        "1.0",
                        List.of(
                                Term.positive("fine", VersionSet.exact("1.0")),
                                Term.positive("stuck", VersionSet.exact("1.0")))))
                .isInstanceOf(UnsatisfiableException.class)
                .satisfies(ex -> {
                    String msg = Diagnostics.render(((UnsatisfiableException) ex).rootCause());
                    assertThat(msg).contains("budget exceeded");
                    assertThat(msg).as("names the read that stood still").contains("stuck@1.0");
                    assertThat(msg)
                            .as("names the URL the read was parked on")
                            .contains("waiting on https://repo.example/never/stuck.pom (1 s)");
                    assertThat(msg).contains("JK_RESOLVE_TIMEOUT_MS");
                });
        assertThat(Thread.currentThread().isInterrupted())
                .as("the watch's interrupt does not outlive the solve")
                .isFalse();
    }

    @Test
    @Timeout(60)
    void a_slow_solve_that_keeps_advancing_is_never_stopped() throws Exception {
        int chain = 30;
        long readMs = 100;
        PackageSource slow = new PackageSource() {
            @Override
            public List<String> versions(String pkg) {
                return List.of("1.0");
            }

            @Override
            public List<Term> dependencies(String pkg, String version) throws IOException, InterruptedException {
                Thread.sleep(readMs);
                int n = Integer.parseInt(pkg.substring(1));
                return n + 1 < chain ? List.of(Term.positive("p" + (n + 1), VersionSet.exact("1.0"))) : List.of();
            }
        };
        // The whole solve takes chain × readMs, several windows long; each read is progress.
        PubGrubSolver solver = new PubGrubSolver(slow, 100_000, /* stallWindowMs */ 1_000);
        Map<String, String> solution =
                solver.solve("root", "1.0", List.of(Term.positive("p0", VersionSet.exact("1.0"))));
        assertThat(solution).hasSize(chain + 1);
    }

    @Test
    void a_zero_window_never_watches() throws Exception {
        PackageSource src =
                InMemoryPackageSource.builder().version("leaf", "1.0").build();
        Map<String, String> solution = new PubGrubSolver(src, 1_000, 0)
                .solve("root", "1.0", List.of(Term.positive("leaf", VersionSet.exact("1.0"))));
        assertThat(solution).containsEntry("leaf", "1.0");
    }
}
