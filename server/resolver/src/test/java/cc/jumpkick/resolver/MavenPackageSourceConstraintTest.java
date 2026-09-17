// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.PlatformPolicy;
import cc.jumpkick.model.UnmappedPolicy;
import cc.jumpkick.repo.EffectivePomBuilder;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolver.pubgrub.VersionSet;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Platform BOM present → bare EffectivePom versions are exact (enforced contract). Without a
 * platform, bare versions stay highest-wins floors.
 */
class MavenPackageSourceConstraintTest {

    @Test
    void platform_pin_matching_bare_version_is_exact(@TempDir Path tmp) {
        MavenPackageSource src = source(tmp, Map.of("com.foo:widget", "1.2.3"));
        VersionSet vs = src.constraintForManagedEdge("com.foo:widget:jar:", "1.2.3");
        assertThat(vs.asExactSingleton()).contains("1.2.3");
    }

    @Test
    void platform_pin_overrides_different_bare_on_edge(@TempDir Path tmp) {
        // POM says 1.0; BOM pins 1.2.3 → enforced platform pin, not a soft prefer.
        MavenPackageSource src = source(tmp, Map.of("com.foo:widget", "1.2.3"));
        VersionSet vs = src.constraintForManagedEdge("com.foo:widget:jar:", "1.0.0");
        assertThat(vs.asExactSingleton()).contains("1.2.3");
    }

    @Test
    void platform_pin_enforced_even_when_pom_declares_higher(@TempDir Path tmp) {
        MavenPackageSource src = source(tmp, Map.of("com.foo:widget", "1.0.0"));
        VersionSet vs = src.constraintForManagedEdge("com.foo:widget:jar:", "1.5.0");
        assertThat(vs.asExactSingleton()).contains("1.0.0");
    }

    @Test
    void platform_present_unmapped_bare_mediates_by_default(@TempDir Path tmp) {
        // unmapped GAs go back to highest-wins mediation (Maven/Gradle parity) — the
        // named-locks hazard class is covered by family-align MAPPING those GAs into the BOM.
        MavenPackageSource src = source(tmp, Map.of("com.foo:other", "9.0.0"));
        VersionSet vs = src.constraintForManagedEdge("org.example:unmanaged:jar:", "1.9.24");
        assertThat(vs.asExactSingleton()).isEmpty();
        assertThat(vs.contains("1.9.24")).isTrue();
        assertThat(vs.contains("2.0.21")).isTrue(); // diamond skew mediates instead of hard-failing
    }

    @Test
    void unmapped_strict_opt_in_restores_exact_fills(@TempDir Path tmp) {
        // [resolve] unmapped = "strict": incomplete-BOM reproducibility posture.
        MavenPackageSource src = strictSource(tmp, Map.of("com.foo:other", "9.0.0"), PlatformPolicy.ENFORCED);
        VersionSet vs = src.constraintForManagedEdge("org.example:unmanaged:jar:", "1.9.24");
        assertThat(vs.asExactSingleton()).contains("1.9.24");
        assertThat(vs.contains("2.0.21")).isFalse();
    }

    @Test
    void without_bom_bare_version_stays_highest_wins(@TempDir Path tmp) {
        MavenPackageSource src = source(tmp, Map.of());
        VersionSet vs = src.constraintForManagedEdge("com.foo:widget:jar:", "1.2.3");
        assertThat(vs.asExactSingleton()).isEmpty();
        assertThat(vs.contains("1.2.3")).isTrue();
        assertThat(vs.contains("9.0.0")).isTrue();
    }

    @Test
    void maven_range_still_open_under_platform(@TempDir Path tmp) {
        MavenPackageSource src = source(tmp, Map.of("com.foo:other", "1.0"));
        VersionSet vs = src.constraintForManagedEdge("com.foo:widget:jar:", "[1.0,2.0)");
        assertThat(vs.asExactSingleton()).isEmpty();
        assertThat(vs.contains("1.5.0")).isTrue();
        assertThat(vs.contains("2.0.0")).isFalse();
    }

    @Test
    void classified_artifact_follows_the_plain_modules_rules(@TempDir Path tmp) {
        // A platform pin on the module governs its classified edge as it does the plain one.
        MavenPackageSource withBom = source(tmp, Map.of("com.google.inject:guice", "5.1.0"));
        assertThat(withBom.constraintForManagedEdge("com.google.inject:guice:jar:classes", "4.2.1")
                        .asExactSingleton())
                .contains("5.1.0");
        // Without one, a bare classified edge is a floor: two POMs naming different versions of
        // netty-transport-native-epoll:linux-x86_64 mediate instead of conflicting as exact pins.
        MavenPackageSource noBom = source(tmp, Map.of());
        VersionSet floor = noBom.constraintForManagedEdge(
                "io.netty:netty-transport-native-epoll:jar:linux-x86_64", "4.1.132.Final");
        assertThat(floor.asExactSingleton()).isEmpty();
        assertThat(floor.contains("4.1.132.Final")).isTrue();
        assertThat(floor.contains("4.1.133.Final")).isTrue();
        assertThat(floor.contains("4.1.131.Final")).isFalse();
    }

    @Test
    void floor_policy_bom_pin_is_at_least_not_exact(@TempDir Path tmp) {
        MavenPackageSource src = source(tmp, Map.of("com.foo:widget", "1.0.0"), PlatformPolicy.FLOOR);
        VersionSet vs = src.constraintForManagedEdge("com.foo:widget:jar:", "1.0.0");
        assertThat(vs.asExactSingleton()).isEmpty();
        assertThat(vs.contains("1.0.0")).isTrue();
        assertThat(vs.contains("2.0.0")).isTrue(); // may lift above BOM pin
    }

    @Test
    void floor_never_clamps_below_the_edges_declared_version(@TempDir Path tmp) {
        // platform pins 1.0.0 as a floor, but this edge's POM requires 2.17.1
        // the constraint must be atLeast(2.17.1), not atLeast(1.0.0).
        MavenPackageSource src = source(tmp, Map.of("com.foo:widget", "1.0.0"), PlatformPolicy.FLOOR);
        VersionSet vs = src.constraintForManagedEdge("com.foo:widget:jar:", "2.17.1");
        assertThat(vs.contains("1.0.0")).isFalse();
        assertThat(vs.contains("2.17.1")).isTrue();
        assertThat(vs.contains("3.0.0")).isTrue();

        // Edge below the pin: the pin is the floor.
        VersionSet below = src.constraintForManagedEdge("com.foo:widget:jar:", "0.9.0");
        assertThat(below.contains("0.9.0")).isFalse();
        assertThat(below.contains("1.0.0")).isTrue();
    }

    @Test
    void floor_classifier_edge_also_lifts_to_the_declared_version(@TempDir Path tmp) {
        MavenPackageSource src = source(tmp, Map.of("com.foo:widget", "1.0.0"), PlatformPolicy.FLOOR);
        VersionSet vs = src.constraintForManagedEdge("com.foo:widget:jar:classes", "2.0.0");
        assertThat(vs.contains("1.0.0")).isFalse();
        assertThat(vs.contains("2.0.0")).isTrue();
    }

    @Test
    void floor_policy_unmapped_strict_stays_exact(@TempDir Path tmp) {
        MavenPackageSource src = strictSource(tmp, Map.of("com.foo:other", "1.0"), PlatformPolicy.FLOOR);
        VersionSet vs = src.constraintForManagedEdge("org.example:leaf:jar:", "1.9.24");
        assertThat(vs.asExactSingleton()).contains("1.9.24");
        assertThat(vs.contains("2.0.0")).isFalse();
    }

    private static MavenPackageSource source(Path tmp, Map<String, String> bom) {
        return source(tmp, bom, PlatformPolicy.ENFORCED);
    }

    private static MavenPackageSource source(Path tmp, Map<String, String> bom, PlatformPolicy policy) {
        MavenRepo repo = neverDialed(tmp);
        RepoGroup group = RepoGroup.of(repo);
        return new MavenPackageSource(group, new EffectivePomBuilder(group), bom, Map.of(), KmpRedirects.NONE, policy);
    }

    private static MavenPackageSource strictSource(Path tmp, Map<String, String> bom, PlatformPolicy policy) {
        MavenRepo repo = neverDialed(tmp);
        RepoGroup group = RepoGroup.of(repo);
        return new MavenPackageSource(
                group, new EffectivePomBuilder(group), bom, Map.of(), KmpRedirects.NONE, policy, UnmappedPolicy.STRICT);
    }

    /** These constraints resolve before any fetch; an empty local repo makes an accidental one a miss. */
    private static MavenRepo neverDialed(Path tmp) {
        return new MavenRepo("local", tmp.resolve("never-dialed").toUri(), new Http(), new Cas(tmp.resolve("c")));
    }
}
