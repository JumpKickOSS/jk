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

/** JK-1202: platform-aligned edges become exact, open floors stay highest-wins. */
class MavenPackageSourceConstraintTest {

    @Test
    void platform_pin_matching_bare_version_is_exact(@TempDir Path tmp) {
        MavenPackageSource src = source(tmp, Map.of("com.foo:widget", "1.2.3"));
        VersionSet vs = src.constraintForManagedEdge("com.foo:widget:jar:", "1.2.3");
        assertThat(vs.asExactSingleton()).contains("1.2.3");
    }

    @Test
    void platform_pin_above_pom_floor_hard_aligns(@TempDir Path tmp) {
        // POM says 1.0 (highest-wins floor); BOM pins 1.2.3 which satisfies >=1.0 → exact pin.
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
    void without_bom_bare_version_stays_highest_wins(@TempDir Path tmp) {
        MavenPackageSource src = source(tmp, Map.of());
        VersionSet vs = src.constraintForManagedEdge("com.foo:widget:jar:", "1.2.3");
        assertThat(vs.asExactSingleton()).isEmpty();
        assertThat(vs.contains("1.2.3")).isTrue();
        assertThat(vs.contains("9.0.0")).isTrue();
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
