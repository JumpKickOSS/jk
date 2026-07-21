// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver.pubgrub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * R2: version interning keeps wide candidate lists + exclusions solvable. Semantics match the
 * existing PubGrub tests; this file adds scale and soft-prefer ordering.
 */
class PubGrubInterningTest {

    @Test
    void soft_prefer_pin_selected_when_compatible() throws Exception {
        // versions() order: pin 1.5 first, then higher 2.0 — pin should win when both allowed.
        PackageSource src = new PackageSource() {
            @Override
            public List<String> versions(String pkg) {
                if (pkg.equals("widget")) return List.of("1.5", "2.0", "1.0");
                return List.of();
            }

            @Override
            public List<Term> dependencies(String pkg, String version) {
                return List.of();
            }
        };
        Map<String, String> solution = new PubGrubSolver(src)
                .solve("root", "1.0", List.of(Term.positive("widget", VersionSet.atLeast("1.0", true))));
        assertThat(solution).containsEntry("widget", "1.5");
    }

    @Test
    void soft_prefer_pin_yields_to_higher_floor() throws Exception {
        PackageSource src = new PackageSource() {
            @Override
            public List<String> versions(String pkg) {
                if (pkg.equals("widget")) return List.of("1.5", "2.0", "1.0");
                return List.of();
            }

            @Override
            public List<Term> dependencies(String pkg, String version) {
                return List.of();
            }
        };
        Map<String, String> solution = new PubGrubSolver(src)
                .solve("root", "1.0", List.of(Term.positive("widget", VersionSet.atLeast("2.0", true))));
        assertThat(solution).containsEntry("widget", "2.0");
    }

    @Test
    void unavailable_version_skips_to_next() throws Exception {
        PackageSource src = new PackageSource() {
            @Override
            public List<String> versions(String pkg) {
                if (pkg.equals("widget")) return List.of("2.0", "1.0");
                return List.of();
            }

            @Override
            public List<Term> dependencies(String pkg, String version)
                    throws PackageSource.VersionUnavailableException {
                if (pkg.equals("widget") && version.equals("2.0")) {
                    throw new PackageSource.VersionUnavailableException("half-published");
                }
                return List.of();
            }
        };
        Map<String, String> solution = new PubGrubSolver(src)
                .solve("root", "1.0", List.of(Term.positive("widget", VersionSet.atLeast("1.0", true))));
        assertThat(solution).containsEntry("widget", "1.0");
    }

    @Test
    void no_matching_version_is_unsatisfiable() {
        PackageSource src =
                InMemoryPackageSource.builder().version("widget", "1.0").build();
        assertThatThrownBy(() -> new PubGrubSolver(src)
                        .solve("root", "1.0", List.of(Term.positive("widget", VersionSet.exact("9.9.9")))))
                .isInstanceOf(UnsatisfiableException.class);
    }

    /**
     * Compose-shaped stress: many packages, wide version lists, diamond-ish deps, prefer-first
     * order. Must finish well under a second with interning (was the R2 exit criterion).
     */
    @Test
    @Timeout(5)
    void wide_graph_with_prefer_first_solves_quickly() throws Exception {
        int packages = 80;
        int versionsPerPkg = 100;
        PackageSource src = wideGraph(packages, versionsPerPkg);

        long start = System.nanoTime();
        Map<String, String> solution = new PubGrubSolver(src)
                .solve("root", "1.0", List.of(Term.positive("p0", VersionSet.atLeast("1.0", true))));
        long ms = (System.nanoTime() - start) / 1_000_000L;

        assertThat(solution).containsKey("p0");
        // Prefer-first puts "50.0.0" at index 0 for every package when still allowed.
        assertThat(solution.get("p0")).isEqualTo("50.0.0");
        // Chain root → p0 → p1 → … should pull a long prefix of packages.
        assertThat(solution.size()).isGreaterThan(packages / 2);
        assertThat(ms)
                .as("solve should be well under 2s with interning; was %d ms", ms)
                .isLessThan(2000L);
    }

    /**
     * Prefer pin first; a mid-chain package requires a high floor on a shared leaf so the pin is
     * lifted — exercises backtracking over interned sets.
     */
    @Test
    @Timeout(5)
    void wide_graph_backtracks_past_prefer_pin() throws Exception {
        InMemoryPackageSource.Builder b = InMemoryPackageSource.builder();
        // shared: 100 versions; a wants shared >= 1, b wants shared >= 90
        for (int v = 100; v >= 1; v--) {
            b.version("shared", v + ".0.0");
        }
        b.version("a", "1.0", deps -> deps.require("shared", VersionSet.atLeast("1.0", true)));
        b.version("b", "1.0", deps -> deps.require("shared", VersionSet.atLeast("90.0", true)));

        // Prefer shared 50 at front via custom source wrapping sorted list with pin first.
        PackageSource base = b.build();
        PackageSource src = new PackageSource() {
            @Override
            public List<String> versions(String pkg) throws java.io.IOException, InterruptedException {
                List<String> v = new ArrayList<>(base.versions(pkg));
                if (pkg.equals("shared")) {
                    v.remove("50.0.0");
                    v.add(0, "50.0.0");
                }
                return v;
            }

            @Override
            public List<Term> dependencies(String pkg, String version)
                    throws java.io.IOException, InterruptedException {
                return base.dependencies(pkg, version);
            }
        };

        Map<String, String> solution = new PubGrubSolver(src)
                .solve(
                        "root",
                        "1.0",
                        List.of(
                                Term.positive("a", VersionSet.exact("1.0")),
                                Term.positive("b", VersionSet.exact("1.0"))));

        // 50 is preferred but fails b's floor; must lift to >= 90 (highest first among those).
        assertThat(solution.get("shared")).isEqualTo("100.0.0");
    }

    /** p0..p_{n-1}, each version i depends on p_{k+1} at >= 1.0 for k < n-1. Prefer pin 50.0.0. */
    private static PackageSource wideGraph(int packages, int versionsPerPkg) {
        return new PackageSource() {
            @Override
            public List<String> versions(String pkg) {
                if (!pkg.startsWith("p")) return List.of();
                List<String> vs = new ArrayList<>(versionsPerPkg);
                // Soft prefer: pin 50.0.0 first when present.
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
                if (idx + 1 >= packages) return List.of();
                return List.of(Term.positive("p" + (idx + 1), VersionSet.atLeast("1.0", true)));
            }
        };
    }
}
