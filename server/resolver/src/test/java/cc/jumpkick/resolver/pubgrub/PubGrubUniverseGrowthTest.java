// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver.pubgrub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A version that only a POM names, and that the catalog omits, joins its package's candidates when
 * that POM is read. A clause learned before it joined holds for the old candidates only, so the
 * solve must not keep it.
 */
class PubGrubUniverseGrowthTest {

    @Test
    void a_floating_root_keeps_its_newest_release_once_a_later_pom_fills_the_gap() throws Exception {
        // a@2 wants p in [1.5, 2.0) and the catalog lists only p 1.0 and 2.0, so p is found to have no
        // candidate there, and a@2 is ruled out, before c's POM names p 1.5. That clause must not
        // outlive p 1.5 joining the candidates.
        PomSource src = new PomSource()
                .advertise("a", "2.0", "1.0")
                .advertise("b", "1.0")
                .advertise("c", "1.0")
                .advertise("p", "2.0", "1.0")
                .pom("a", "2.0", Term.positive("p", VersionSet.between("1.5", true, "2.0", false)))
                .pom("a", "1.0")
                .pom("b", "1.0", Term.positive("c", VersionSet.ALL))
                .pomPlain("c", "1.0", "p", "1.5")
                .pom("p", "1.5")
                .pom("p", "1.0")
                .pom("p", "2.0");

        Map<String, String> solution = new PubGrubSolver(src)
                .solve("root", "1.0", List.of(Term.positive("a", VersionSet.ALL), Term.positive("b", VersionSet.ALL)));

        assertThat(solution).containsEntry("a", "2.0").containsEntry("p", "1.5");
    }

    @Test
    void a_range_with_no_listed_version_waits_for_a_later_pom_to_name_one() throws Exception {
        // a has no other release, so ruling out a@2 when p is first found to have no candidate in
        // [1.5, 2.0) would fail the root before c's POM, which names p 1.5, is ever read.
        PomSource src = new PomSource()
                .advertise("a", "2.0")
                .advertise("b", "1.0")
                .advertise("c", "1.0")
                .advertise("p", "2.0", "1.0")
                .pom("a", "2.0", Term.positive("p", VersionSet.between("1.5", true, "2.0", false)))
                .pom("b", "1.0", Term.positive("c", VersionSet.ALL))
                .pomPlain("c", "1.0", "p", "1.5")
                .pom("p", "1.5")
                .pom("p", "1.0")
                .pom("p", "2.0");

        Map<String, String> solution = new PubGrubSolver(src)
                .solve("root", "1.0", List.of(Term.positive("a", VersionSet.ALL), Term.positive("b", VersionSet.ALL)));

        assertThat(solution).containsEntry("a", "2.0").containsEntry("c", "1.0").containsEntry("p", "1.5");
    }

    @Test
    void a_range_no_pom_ever_fills_still_fails() throws Exception {
        PomSource src = new PomSource()
                .advertise("a", "2.0")
                .advertise("b", "1.0")
                .advertise("p", "2.0", "1.0")
                .pom("a", "2.0", Term.positive("p", VersionSet.between("1.5", true, "2.0", false)))
                .pom("b", "1.0")
                .pom("p", "1.0")
                .pom("p", "2.0");

        assertThatThrownBy(() -> new PubGrubSolver(src)
                        .solve(
                                "root",
                                "1.0",
                                List.of(Term.positive("a", VersionSet.ALL), Term.positive("b", VersionSet.ALL))))
                .isInstanceOf(UnsatisfiableException.class);
    }

    /**
     * Serves a POM for every version it has one for, advertised or not, and reports a declared
     * version only once the POM naming it was read, as a repository-backed source does.
     */
    private static final class PomSource implements PackageSource {
        private final Map<String, List<String>> catalog = new HashMap<>();
        private final Map<String, List<Term>> poms = new HashMap<>();
        private final Map<String, Map<String, String>> plainEdges = new HashMap<>();
        private final Map<String, Map<String, Set<String>>> declared = new HashMap<>();

        PomSource advertise(String pkg, String... versions) {
            catalog.put(pkg, List.of(versions));
            return this;
        }

        PomSource pom(String pkg, String version, Term... deps) {
            poms.put(pkg + "@" + version, List.of(deps));
            return this;
        }

        PomSource pomPlain(String pkg, String version, String dep, String depVersion) {
            plainEdges
                    .computeIfAbsent(pkg + "@" + version, k -> new LinkedHashMap<>())
                    .put(dep, depVersion);
            return pom(pkg, version, Term.positive(dep, VersionSet.atLeast(depVersion, true)));
        }

        @Override
        public List<String> versions(String pkg) {
            return catalog.getOrDefault(pkg, List.of());
        }

        @Override
        public Map<String, Set<String>> declaredVersions(String pkg) {
            return declared.getOrDefault(pkg, Map.of());
        }

        @Override
        public List<Term> dependencies(String pkg, String version) throws VersionUnavailableException {
            String coord = pkg + "@" + version;
            List<Term> deps = poms.get(coord);
            if (deps == null) throw new VersionUnavailableException(coord + " has no POM");
            plainEdges.getOrDefault(coord, Map.of()).forEach((dep, v) -> declared.computeIfAbsent(
                            dep, k -> new LinkedHashMap<>())
                    .computeIfAbsent(v, k -> new LinkedHashSet<>())
                    .add(coord));
            return deps;
        }
    }
}
