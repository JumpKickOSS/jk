// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.repo.EffectivePomBuilder;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolver.pubgrub.VersionSet;
import java.net.URI;
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
    void platform_present_unmapped_bare_is_exact_not_highest_wins(@TempDir Path tmp) {
        // BOM maps an unrelated GA — platform is still active. Bare filled version must not
        // become atLeast (named-locks / incomplete-BOM failure mode).
        MavenPackageSource src = source(tmp, Map.of("com.foo:other", "9.0.0"));
        VersionSet vs = src.constraintForManagedEdge("org.apache.maven.resolver:maven-resolver-named-locks:jar:", "1.9.24");
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
    void classified_artifact_pins_exact_declared_or_bom(@TempDir Path tmp) {
        // guice:jar:classes — highest-wins on the GA list misses classifier jars (JK-1202).
        MavenPackageSource withBom = source(tmp, Map.of("com.google.inject:guice", "5.1.0"));
        assertThat(withBom
                        .constraintForManagedEdge("com.google.inject:guice:jar:classes", "5.1.0")
                        .asExactSingleton())
                .contains("5.1.0");
        MavenPackageSource noBom = source(tmp, Map.of());
        assertThat(noBom.constraintForManagedEdge("com.google.inject:guice:jar:classes", "5.1.0")
                        .asExactSingleton())
                .contains("5.1.0");
    }

    private static MavenPackageSource source(Path tmp, Map<String, String> bom) {
        MavenRepo repo = new MavenRepo("local", URI.create("http://127.0.0.1:1"), new Http(), new Cas(tmp.resolve("c")));
        RepoGroup group = RepoGroup.of(repo);
        return new MavenPackageSource(group, new EffectivePomBuilder(group), bom);
    }
}
