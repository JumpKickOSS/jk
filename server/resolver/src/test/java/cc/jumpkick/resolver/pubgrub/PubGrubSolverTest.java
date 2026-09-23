// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver.pubgrub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class PubGrubSolverTest {

    @Test
    void root_only_no_deps() throws Exception {
        PackageSource src = InMemoryPackageSource.builder().build();
        PubGrubSolver solver = new PubGrubSolver(src);

        Map<String, String> solution = solver.solve("root", "1.0", List.of());
        assertThat(solution).containsExactly(Map.entry("root", "1.0"));
    }

    @Test
    void single_dep_chain() throws Exception {
        PackageSource src =
                InMemoryPackageSource.builder().version("leaf", "1.0").build();

        PubGrubSolver solver = new PubGrubSolver(src);
        Map<String, String> solution =
                solver.solve("root", "1.0", List.of(Term.positive("leaf", VersionSet.exact("1.0"))));

        assertThat(solution).containsOnly(Map.entry("root", "1.0"), Map.entry("leaf", "1.0"));
    }

    @Test
    void exact_constraint_skips_versions_lookup() throws Exception {
        AtomicInteger versionsCalls = new AtomicInteger();
        PackageSource inner = InMemoryPackageSource.builder()
                .version("leaf", "1.0")
                .version("leaf", "2.0")
                .build();
        PackageSource src = counting(inner, versionsCalls, null);

        Map<String, String> solution =
                new PubGrubSolver(src).solve("root", "1.0", List.of(Term.positive("leaf", VersionSet.exact("1.0"))));

        assertThat(solution).containsEntry("leaf", "1.0");
        assertThat(versionsCalls.get()).isZero();
    }

    @Test
    void preferred_version_skips_versions_lookup_on_happy_path() throws Exception {
        AtomicInteger versionsCalls = new AtomicInteger();
        PackageSource inner = InMemoryPackageSource.builder()
                .version("widget", "1.0")
                .version("widget", "2.0")
                .version("widget", "3.0")
                .build();
        PackageSource src = counting(inner, versionsCalls, Map.of("widget", "2.0"));

        // Open constraint (highest-wins style) + soft-prefer pin → seed 2.0 without metadata.
        Map<String, String> solution = new PubGrubSolver(src)
                .solve("root", "1.0", List.of(Term.positive("widget", VersionSet.atLeast("1.0", true))));

        assertThat(solution).containsEntry("widget", "2.0");
        assertThat(versionsCalls.get()).isZero();
    }

    @Test
    void preferred_version_expands_when_pin_pom_unavailable() throws Exception {
        AtomicInteger versionsCalls = new AtomicInteger();
        PackageSource inner = new PackageSource() {
            private final PackageSource data = InMemoryPackageSource.builder()
                    .version("widget", "1.0")
                    .version("widget", "2.0")
                    .version("widget", "3.0")
                    .build();

            @Override
            public List<String> versions(String pkg) throws IOException, InterruptedException {
                return data.versions(pkg);
            }

            @Override
            public Optional<String> preferredVersion(String pkg) {
                return Optional.of("3.0");
            }

            @Override
            public List<Term> dependencies(String pkg, String version) throws IOException, InterruptedException {
                if ("widget".equals(pkg) && "3.0".equals(version)) {
                    throw new VersionUnavailableException("3.0 yanked");
                }
                return data.dependencies(pkg, version);
            }
        };
        PackageSource src = counting(inner, versionsCalls, null);

        Map<String, String> solution = new PubGrubSolver(src)
                .solve("root", "1.0", List.of(Term.positive("widget", VersionSet.atLeast("1.0", true))));

        // Soft-prefer 3.0 fails; expand + highest-wins among remaining → 2.0.
        assertThat(solution).containsEntry("widget", "2.0");
        assertThat(versionsCalls.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void preferred_outside_constraint_loads_full_versions() throws Exception {
        AtomicInteger versionsCalls = new AtomicInteger();
        PackageSource inner = InMemoryPackageSource.builder()
                .version("widget", "1.0")
                .version("widget", "2.0")
                .version("widget", "3.0")
                .build();
        // Prefer 1.0 but constraint is >= 2.0 — seed would project empty; expand immediately.
        PackageSource src = counting(inner, versionsCalls, Map.of("widget", "1.0"));

        Map<String, String> solution = new PubGrubSolver(src)
                .solve("root", "1.0", List.of(Term.positive("widget", VersionSet.atLeast("2.0", true))));

        assertThat(solution).containsEntry("widget", "3.0");
        assertThat(versionsCalls.get()).isGreaterThanOrEqualTo(1);
    }

    /** Wraps {@code inner}; counts {@link PackageSource#versions} and optionally injects prefs. */
    @Test
    void widens_capped_universe_when_constraint_needs_an_older_release() throws Exception {
        // versions is compacted to the top releases (MavenPackageSource caps at 4);
        // a range below them must trigger expandedVersions, not NoVersions.
        InMemoryPackageSource full = InMemoryPackageSource.builder()
                .version("leaf", "1.0")
                .version("leaf", "2.0")
                .version("leaf", "3.0")
                .version("leaf", "4.0")
                .version("leaf", "5.0")
                .build();
        PackageSource capped = new PackageSource() {
            @Override
            public List<String> versions(String pkg) throws IOException, InterruptedException {
                List<String> all = full.versions(pkg);
                return all.size() <= 4 ? all : all.subList(0, 4); // [5.0, 4.0, 3.0, 2.0]
            }

            @Override
            public List<String> expandedVersions(String pkg) throws IOException, InterruptedException {
                return full.versions(pkg);
            }

            @Override
            public List<Term> dependencies(String pkg, String version) throws IOException, InterruptedException {
                return full.dependencies(pkg, version);
            }
        };

        Map<String, String> solution = new PubGrubSolver(capped)
                .solve("root", "1.0", List.of(Term.positive("leaf", VersionSet.lessThan("2.0", false))));

        assertThat(solution).containsEntry("leaf", "1.0");
    }

    /** A range below the newest 48 releases is still selectable after the widen cap. */
    @Test
    void a_range_below_the_newest_forty_eight_releases_still_resolves() throws Exception {
        InMemoryPackageSource.Builder builder = InMemoryPackageSource.builder();
        for (int n = 80; n >= 21; n--) builder.version("leaf", n + ".0");
        builder.version("leaf", "20.9");
        builder.version("leaf", "20.0");
        InMemoryPackageSource full = builder.build();
        PackageSource capped = new PackageSource() {
            @Override
            public List<String> versions(String pkg) throws IOException, InterruptedException {
                List<String> all = full.versions(pkg);
                return all.size() <= 4 ? all : all.subList(0, 4);
            }

            @Override
            public List<String> expandedVersions(String pkg) throws IOException, InterruptedException {
                return full.versions(pkg);
            }

            @Override
            public List<Term> dependencies(String pkg, String version) throws IOException, InterruptedException {
                return full.dependencies(pkg, version);
            }
        };
        Map<String, String> solution = new PubGrubSolver(capped)
                .solve("root", "1.0", List.of(Term.positive("leaf", VersionSet.between("20.0", true, "21.0", false))));
        assertThat(solution).containsEntry("leaf", "20.9");
    }

    /** An open lower bound has no ceiling, so the widen cap still trims and the newest wins. */
    @Test
    void an_open_lower_bound_still_selects_the_newest_release() throws Exception {
        InMemoryPackageSource.Builder builder = InMemoryPackageSource.builder();
        for (int n = 80; n >= 21; n--) builder.version("leaf", n + ".0");
        builder.version("leaf", "20.0");
        InMemoryPackageSource full = builder.build();
        PackageSource capped = new PackageSource() {
            @Override
            public List<String> versions(String pkg) throws IOException, InterruptedException {
                List<String> all = full.versions(pkg);
                return all.size() <= 4 ? all : all.subList(0, 4);
            }

            @Override
            public List<String> expandedVersions(String pkg) throws IOException, InterruptedException {
                return full.versions(pkg);
            }

            @Override
            public List<Term> dependencies(String pkg, String version) throws IOException, InterruptedException {
                return full.dependencies(pkg, version);
            }
        };
        Map<String, String> solution = new PubGrubSolver(capped)
                .solve("root", "1.0", List.of(Term.positive("leaf", VersionSet.atLeast("20.0", true))));
        assertThat(solution).containsEntry("leaf", "80.0");
    }

    private static PackageSource counting(
            PackageSource inner, AtomicInteger versionsCalls, @Nullable Map<String, String> preferredOrNull) {
        return new PackageSource() {
            @Override
            public List<String> versions(String pkg) throws IOException, InterruptedException {
                versionsCalls.incrementAndGet();
                return inner.versions(pkg);
            }

            @Override
            public Optional<String> preferredVersion(String pkg) {
                if (preferredOrNull != null && preferredOrNull.containsKey(pkg)) {
                    return Optional.of(preferredOrNull.get(pkg));
                }
                return inner.preferredVersion(pkg);
            }

            @Override
            public List<Term> dependencies(String pkg, String version) throws IOException, InterruptedException {
                return inner.dependencies(pkg, version);
            }
        };
    }

    /**
     * A package first met through a floor gets the compact window; when an exact pin on a release
     * deep in a long history arrives later, widening must still offer that release, however far
     * below the newest entries it sits.
     */
    @Test
    void an_exact_pin_deep_in_a_long_history_survives_the_expanded_cap() throws Exception {
        InMemoryPackageSource.Builder b = InMemoryPackageSource.builder();
        for (int i = 1; i <= 80; i++) b.version("lib", i + ".0");
        b.version("floor", "1.0", deps -> deps.require("lib", VersionSet.atLeast("1.0", true)));
        b.version("pinner", "1.0", deps -> deps.require("lib", VersionSet.exact("3.0")));
        PackageSource inner = b.build();
        PackageSource src = new PackageSource() {
            @Override
            public List<String> versions(String pkg) throws IOException, InterruptedException {
                return inner.versions(pkg);
            }

            @Override
            public Set<String> declaredVersions(String pkg) {
                return pkg.equals("lib") ? Set.of("3.0") : Set.of();
            }

            @Override
            public List<Term> dependencies(String pkg, String version) throws IOException, InterruptedException {
                return inner.dependencies(pkg, version);
            }
        };

        Map<String, String> solution = new PubGrubSolver(src)
                .solve(
                        "root",
                        "1.0",
                        List.of(
                                Term.positive("floor", VersionSet.exact("1.0")),
                                Term.positive("pinner", VersionSet.exact("1.0"))));

        assertThat(solution).containsEntry("lib", "3.0");
    }

    @Test
    void transitive_chain() throws Exception {
        PackageSource src = InMemoryPackageSource.builder()
                .version("leaf", "1.0")
                .version("middle", "1.0", deps -> deps.require("leaf", VersionSet.exact("1.0")))
                .build();

        PubGrubSolver solver = new PubGrubSolver(src);
        Map<String, String> solution =
                solver.solve("root", "1.0", List.of(Term.positive("middle", VersionSet.exact("1.0"))));

        assertThat(solution).containsOnlyKeys("root", "middle", "leaf");
    }

    @Test
    void picks_highest_satisfying_version() throws Exception {
        PackageSource src = InMemoryPackageSource.builder()
                .version("widget", "1.0")
                .version("widget", "2.0")
                .version("widget", "3.0")
                .build();

        PubGrubSolver solver = new PubGrubSolver(src);
        Map<String, String> solution = solver.solve(
                "root", "1.0", List.of(Term.positive("widget", VersionSet.between("1.0", true, "3.0", false))));

        assertThat(solution).containsEntry("widget", "2.0");
    }

    @Test
    void skips_a_higher_pre_release_for_a_floating_constraint() throws Exception {
        // 3.0.0-RC1 is the highest version, but a floating >=1.0 constraint
        // must resolve to the highest *stable* version, 2.0.0.
        PackageSource src = InMemoryPackageSource.builder()
                .version("widget", "1.0.0")
                .version("widget", "2.0.0")
                .version("widget", "3.0.0-RC1")
                .build();

        PubGrubSolver solver = new PubGrubSolver(src);
        Map<String, String> solution =
                solver.solve("root", "1.0", List.of(Term.positive("widget", VersionSet.atLeast("1.0.0", true))));

        assertThat(solution).containsEntry("widget", "2.0.0");
    }

    @Test
    void selects_a_pre_release_when_no_stable_version_satisfies() throws Exception {
        // Only pre-releases exist — the solver must still resolve.
        PackageSource src = InMemoryPackageSource.builder()
                .version("widget", "3.0.0-RC1")
                .version("widget", "3.0.0-RC2")
                .build();

        PubGrubSolver solver = new PubGrubSolver(src);
        Map<String, String> solution =
                solver.solve("root", "1.0", List.of(Term.positive("widget", VersionSet.atLeast("1.0.0", true))));

        assertThat(solution).containsEntry("widget", "3.0.0-RC2");
    }

    @Test
    void diamond_with_compatible_versions() throws Exception {
        // root -> a -> shared >= 1.0
        // root -> b -> shared <= 2.0
        PackageSource src = InMemoryPackageSource.builder()
                .version("shared", "1.5")
                .version("shared", "1.0")
                .version("a", "1.0", deps -> deps.require("shared", VersionSet.atLeast("1.0", true)))
                .version("b", "1.0", deps -> deps.require("shared", VersionSet.lessThan("2.0", false)))
                .build();

        PubGrubSolver solver = new PubGrubSolver(src);
        Map<String, String> solution = solver.solve(
                "root",
                "1.0",
                List.of(Term.positive("a", VersionSet.exact("1.0")), Term.positive("b", VersionSet.exact("1.0"))));

        // shared must be in [1.0, 2.0). Highest version we have is 1.5.
        assertThat(solution).containsEntry("shared", "1.5");
    }

    @Test
    void unsatisfiable_throws() throws Exception {
        // Two declared deps with incompatible exact pins on the same module.
        PackageSource src = InMemoryPackageSource.builder()
                .version("shared", "1.0")
                .version("shared", "2.0")
                .version("a", "1.0", deps -> deps.require("shared", VersionSet.exact("1.0")))
                .version("b", "1.0", deps -> deps.require("shared", VersionSet.exact("2.0")))
                .build();

        PubGrubSolver solver = new PubGrubSolver(src);
        assertThatThrownBy(() -> solver.solve(
                        "root",
                        "1.0",
                        List.of(
                                Term.positive("a", VersionSet.exact("1.0")),
                                Term.positive("b", VersionSet.exact("1.0")))))
                .isInstanceOf(UnsatisfiableException.class);
    }

    @Test
    void no_version_satisfies_constraint() {
        PackageSource src =
                InMemoryPackageSource.builder().version("widget", "1.0").build();

        PubGrubSolver solver = new PubGrubSolver(src);
        assertThatThrownBy(
                        () -> solver.solve("root", "1.0", List.of(Term.positive("widget", VersionSet.exact("9.9.9")))))
                .isInstanceOf(UnsatisfiableException.class);
    }

    // --- half-published releases (metadata advertises a version whose POM 404s) ---------

    @Test
    void half_published_version_retreats_to_next_candidate() throws Exception {
        PackageSource src = withUnavailable(
                InMemoryPackageSource.builder()
                        .version("widget", "3.0")
                        .version("widget", "2.0")
                        .build(),
                "widget@3.0");

        PubGrubSolver solver = new PubGrubSolver(src);
        Map<String, String> solution =
                solver.solve("root", "1.0", List.of(Term.positive("widget", VersionSet.atLeast("1.0", true))));

        assertThat(solution).containsEntry("widget", "2.0");
    }

    @Test
    void half_published_transitive_retreats_too() throws Exception {
        // The live junit shape: a root dep's floating transitive resolves to the advertised
        // latest, whose POM is still propagating — the solve must land on the prior release.
        PackageSource src = withUnavailable(
                InMemoryPackageSource.builder()
                        .version("app", "1.0", deps -> deps.require("widget", VersionSet.atLeast("1.0", true)))
                        .version("widget", "6.1.2")
                        .version("widget", "6.1.1")
                        .build(),
                "widget@6.1.2");

        PubGrubSolver solver = new PubGrubSolver(src);
        Map<String, String> solution =
                solver.solve("root", "1.0", List.of(Term.positive("app", VersionSet.exact("1.0"))));

        assertThat(solution).containsEntry("widget", "6.1.1");
    }

    @Test
    void every_candidate_unavailable_fails_cleanly() {
        PackageSource src = withUnavailable(
                InMemoryPackageSource.builder().version("widget", "3.0").build(), "widget@3.0");

        PubGrubSolver solver = new PubGrubSolver(src);
        assertThatThrownBy(() ->
                        solver.solve("root", "1.0", List.of(Term.positive("widget", VersionSet.atLeast("1.0", true)))))
                .isInstanceOf(UnsatisfiableException.class);
    }

    @Test
    void transitive_floors_resolve_to_the_highest_declared_version() throws Exception {
        // stdlib names 13.0, coroutines names 23.0.0, the repository has 26.1.0: nothing asked for
        // 26.1.0, so the pick is the highest version an edge declared.
        PackageSource src = InMemoryPackageSource.builder()
                .version("annotations", "13.0")
                .version("annotations", "23.0.0")
                .version("annotations", "26.1.0")
                .version("stdlib", "2.4", d -> d.requirePlain("annotations", "13.0"))
                .version("coroutines", "1.11", d -> d.requirePlain("annotations", "23.0.0"))
                .build();
        Map<String, String> solution = new PubGrubSolver(src)
                .solve(
                        "root",
                        "1.0",
                        List.of(
                                Term.positive("stdlib", VersionSet.exact("2.4")),
                                Term.positive("coroutines", VersionSet.exact("1.11"))));
        assertThat(solution).containsEntry("annotations", "23.0.0");
    }

    @Test
    void a_floating_root_selector_still_takes_the_newest_release_in_its_range() throws Exception {
        // The manifest asked for annotations with a floor; that is a request for the newest
        // release, and a transitive naming 23.0.0 does not hold it back.
        PackageSource src = InMemoryPackageSource.builder()
                .version("annotations", "13.0")
                .version("annotations", "23.0.0")
                .version("annotations", "26.1.0")
                .version("coroutines", "1.11", d -> d.requirePlain("annotations", "23.0.0"))
                .build();
        Map<String, String> solution = new PubGrubSolver(src)
                .solve(
                        "root",
                        "1.0",
                        List.of(
                                Term.positive("annotations", VersionSet.atLeast("13.0", true)),
                                Term.positive("coroutines", VersionSet.exact("1.11"))));
        assertThat(solution).containsEntry("annotations", "26.1.0");
    }

    @Test
    void a_range_edge_takes_the_newest_release_in_range_over_a_declared_version() throws Exception {
        // A Maven range on any edge asks for the newest release inside it (Maven and Gradle both
        // resolve a range that way), so a plain 23.0.0 elsewhere does not steer the pick.
        PackageSource src = InMemoryPackageSource.builder()
                .version("annotations", "13.0")
                .version("annotations", "23.0.0")
                .version("annotations", "26.1.0")
                .version(
                        "ranged", "1.0", d -> d.require("annotations", VersionSet.between("13.0", true, "27.0", false)))
                .version("coroutines", "1.11", d -> d.requirePlain("annotations", "23.0.0"))
                .build();
        Map<String, String> solution = new PubGrubSolver(src)
                .solve(
                        "root",
                        "1.0",
                        List.of(
                                Term.positive("ranged", VersionSet.exact("1.0")),
                                Term.positive("coroutines", VersionSet.exact("1.11"))));
        assertThat(solution).containsEntry("annotations", "26.1.0");
    }

    @Test
    void a_declared_pre_release_is_taken_over_a_newer_stable_nobody_named() throws Exception {
        PackageSource src = InMemoryPackageSource.builder()
                .version("widget", "1.0-M3")
                .version("widget", "1.0")
                .version("lib", "1.0", d -> d.requirePlain("widget", "1.0-M3"))
                .build();
        Map<String, String> solution =
                new PubGrubSolver(src).solve("root", "1.0", List.of(Term.positive("lib", VersionSet.exact("1.0"))));
        assertThat(solution).containsEntry("widget", "1.0-M3");
    }

    @Test
    void a_preferred_pin_is_kept_over_the_declared_version() throws Exception {
        // A lock (or BOM) preference at 26.1.0 is what the project already has; the declared
        // 23.0.0 only steers packages nothing pinned.
        PackageSource inner = InMemoryPackageSource.builder()
                .version("annotations", "13.0")
                .version("annotations", "23.0.0")
                .version("annotations", "26.1.0")
                .version("coroutines", "1.11", d -> d.requirePlain("annotations", "23.0.0"))
                .build();
        PackageSource src = preferring(inner, "annotations", "26.1.0");
        Map<String, String> solution = new PubGrubSolver(src)
                .solve("root", "1.0", List.of(Term.positive("coroutines", VersionSet.exact("1.11"))));
        assertThat(solution).containsEntry("annotations", "26.1.0");
    }

    @Test
    void a_floor_above_every_declared_version_is_the_pick() throws Exception {
        // The old lock held util at 2.0.8 while every edge names 2.0.0: a floating pass keeps 2.0.8
        // and does not reach for 2.0.12, which nothing asked for either.
        PackageSource src = InMemoryPackageSource.builder()
                .version("util", "2.0.0")
                .version("util", "2.0.8")
                .version("util", "2.0.12")
                .version("zinc", "2.0.4", d -> d.requirePlain("util", "2.0.0"))
                .floor("util", "2.0.8")
                .build();
        Map<String, String> solution =
                new PubGrubSolver(src).solve("root", "1.0", List.of(Term.positive("zinc", VersionSet.exact("2.0.4"))));
        assertThat(solution).containsEntry("util", "2.0.8");
    }

    @Test
    void a_declared_version_above_the_floor_is_still_the_pick() throws Exception {
        PackageSource src = InMemoryPackageSource.builder()
                .version("annotations", "13.0")
                .version("annotations", "23.0.0")
                .version("annotations", "26.1.0")
                .version("coroutines", "1.11", d -> d.requirePlain("annotations", "23.0.0"))
                .floor("annotations", "13.0")
                .build();
        Map<String, String> solution = new PubGrubSolver(src)
                .solve("root", "1.0", List.of(Term.positive("coroutines", VersionSet.exact("1.11"))));
        assertThat(solution).containsEntry("annotations", "23.0.0");
    }

    @Test
    void an_exact_constraint_below_the_floor_wins_over_it() throws Exception {
        // A platform pin arrives as an exact constraint; a floor it rules out has no say.
        PackageSource src = InMemoryPackageSource.builder()
                .version("widget", "1.0")
                .version("widget", "2.0")
                .version("lib", "1.0", d -> d.require("widget", VersionSet.exact("1.0")))
                .floor("widget", "2.0")
                .build();
        Map<String, String> solution =
                new PubGrubSolver(src).solve("root", "1.0", List.of(Term.positive("lib", VersionSet.exact("1.0"))));
        assertThat(solution).containsEntry("widget", "1.0");
    }

    /** Wrap a source with a soft-prefer pin for one package. */
    private static PackageSource preferring(PackageSource delegate, String pkg, String version) {
        return new PackageSource() {
            @Override
            public List<String> versions(String p) throws IOException, InterruptedException {
                return delegate.versions(p);
            }

            @Override
            public List<Term> dependencies(String p, String v) throws IOException, InterruptedException {
                return delegate.dependencies(p, v);
            }

            @Override
            public Set<String> declaredVersions(String p) {
                return delegate.declaredVersions(p);
            }

            @Override
            public Optional<String> preferredVersion(String p) {
                return p.equals(pkg) ? Optional.of(version) : Optional.empty();
            }
        };
    }

    /** Wrap a source so the given {@code pkg@version} coords throw the unavailable signal. */
    private static PackageSource withUnavailable(PackageSource delegate, String... coords) {
        Set<String> dead = Set.of(coords);
        return new PackageSource() {
            @Override
            public List<String> versions(String pkg) throws IOException, InterruptedException {
                return delegate.versions(pkg);
            }

            @Override
            public List<Term> dependencies(String pkg, String version) throws IOException, InterruptedException {
                if (dead.contains(pkg + "@" + version)) {
                    throw new VersionUnavailableException("POM not found in any declared repo: " + pkg + ":" + version);
                }
                return delegate.dependencies(pkg, version);
            }
        };
    }
}
