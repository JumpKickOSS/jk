// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver.pubgrub;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A long chain whose last link conflicts with a package pinned near the root. Every version of the
 * last link carries the same conflicting dependency, so the solver excludes them one at a time,
 * backjumping to the pin and re-deciding the whole chain each time; once the link is exhausted the
 * same happens to the link before it. The solve is large by construction, and it finishes only
 * when each of those steps costs a bounded amount of work: clauses recorded once, projections
 * memoized, and a backjump that leaves untouched packages alone.
 */
class PubGrubDeepChainTest {

    /**
     * p0 -> p1 -> ... -> p{links-1}, every link and {@code shared} advertising {@code
     * versionsPerPkg} versions with 50.0.0 preferred; p0 wants any shared, the last link wants
     * shared >= 90.
     */
    static PackageSource deepChain(int links, int versionsPerPkg) {
        return new PackageSource() {
            @Override
            public List<String> versions(String pkg) {
                if (!pkg.startsWith("p") && !pkg.equals("shared")) return List.of();
                List<String> vs = new ArrayList<>(versionsPerPkg);
                vs.add("50.0.0");
                for (int v = versionsPerPkg; v >= 1; v--) {
                    String s = v + ".0.0";
                    if (!s.equals("50.0.0")) vs.add(s);
                }
                return vs;
            }

            @Override
            public List<Term> dependencies(String pkg, String version) {
                if (!pkg.startsWith("p")) return List.of();
                int idx = Integer.parseInt(pkg.substring(1));
                List<Term> deps = new ArrayList<>();
                if (idx == 0) deps.add(Term.positive("shared", VersionSet.atLeast("1.0", true)));
                if (idx + 1 < links) deps.add(Term.positive("p" + (idx + 1), VersionSet.atLeast("1.0", true)));
                else deps.add(Term.positive("shared", VersionSet.atLeast("90.0", true)));
                return deps;
            }
        };
    }

    @Test
    @Timeout(10)
    void a_conflict_at_the_end_of_a_deep_chain_lifts_the_shared_pin() throws Exception {
        int links = 60;
        PubGrubSolver solver = new PubGrubSolver(deepChain(links, 100));
        Map<String, String> solution =
                solver.solve("root", "1.0", List.of(Term.positive("p0", VersionSet.atLeast("1.0", true))));

        assertThat(solution).containsEntry("shared", "100.0.0");
        assertThat(solution).hasSize(links + 2);
        for (int i = 0; i < links; i++) assertThat(solution).containsKey("p" + i);
    }

    @Test
    void a_version_decided_again_after_a_backjump_records_its_dependency_clauses_once() throws Exception {
        int links = 5;
        PubGrubSolver solver = new PubGrubSolver(deepChain(links, 100));
        solver.solve("root", "1.0", List.of(Term.positive("p0", VersionSet.atLeast("1.0", true))));

        // Each package@version decided contributes its dependency clauses exactly once; the rest
        // of the list is the no-candidates clauses the exhausted links produce.
        long dependencyClauses = solver.incompatibilities.stream()
                .filter(i -> i.cause() instanceof Incompatibility.Cause.Dependency)
                .count();
        long distinctDependencyClauses = solver.incompatibilities.stream()
                .filter(i -> i.cause() instanceof Incompatibility.Cause.Dependency)
                .map(Incompatibility::terms)
                .distinct()
                .count();
        assertThat(dependencyClauses).isEqualTo(distinctDependencyClauses);
        assertThat(solver.incompatibilities)
                .as("the list holds each clause once")
                .doesNotHaveDuplicates();
    }
}
