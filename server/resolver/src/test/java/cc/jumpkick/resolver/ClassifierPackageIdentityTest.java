// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.PackageId;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.Pom;
import cc.jumpkick.resolver.pubgrub.InMemoryPackageSource;
import cc.jumpkick.resolver.pubgrub.VersionSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Dual-classifier package identity: plain jar and a classifier of the same GA must resolve as
 * distinct packages at the same version.
 */
class ClassifierPackageIdentityTest {

    @Test
    void two_classifiers_of_same_ga_resolve_as_distinct_packages() throws Exception {
        String plain = PackageId.ofGa("io.netty:netty-transport-native-epoll").key();
        String linux = PackageId.of("io.netty", "netty-transport-native-epoll", "jar", "linux-x86_64")
                .key();
        String root = PackageId.ofGa("com.example:app").key();

        InMemoryPackageSource source = InMemoryPackageSource.builder()
                .version(root, "1.0", deps -> deps.require(plain, VersionSet.exact("4.1.100.Final"))
                        .require(linux, VersionSet.exact("4.1.100.Final")))
                .version(plain, "4.1.100.Final")
                .version(linux, "4.1.100.Final")
                .build();

        PubGrubResolver resolver = new PubGrubResolver(source, null);
        Resolution r =
                resolver.resolve(List.of(Dependency.of("app", "com.example:app", VersionSelector.parse("=1.0"))));

        assertThat(r.modules()).containsKeys(root, plain, linux);
        assertThat(r.modules().get(plain).version()).isEqualTo("4.1.100.Final");
        assertThat(r.modules().get(linux).version()).isEqualTo("4.1.100.Final");
        assertThat(plain).isNotEqualTo(linux);
    }

    @Test
    void an_aar_edge_and_a_bare_edge_are_one_solver_package() {
        // One artifact, one version: the packaging an edge names is not another library. Two
        // packages here let a manifest pin and a transitive aar land two versions of core-ktx.
        Pom.Dep bare = new Pom.Dep("androidx.core", "core-ktx", "1.16.0", null, false, null, null, List.of());
        Pom.Dep aar = new Pom.Dep("androidx.core", "core-ktx", "1.19.0", null, false, null, "aar", List.of());
        assertThat(MavenPackageSource.packageKey(aar))
                .isEqualTo(MavenPackageSource.packageKey(bare))
                .isEqualTo(PackageId.ofGa("androidx.core:core-ktx").key());
        Pom.Dep testJar = new Pom.Dep("androidx.core", "core-ktx", "1.19.0", null, false, null, "test-jar", List.of());
        assertThat(MavenPackageSource.packageKey(testJar))
                .as("a secondary artifact type is still its own package")
                .isNotEqualTo(MavenPackageSource.packageKey(bare));
    }

    @Test
    void bare_ga_lock_name_normalizes_to_default_jar_package_key() {
        assertThat(PackageId.parse("com.google.guava:guava").key()).isEqualTo("com.google.guava:guava:jar:");
        var art = new Lockfile.Artifact(
                "com.google.guava:guava",
                "33.0.0-jre",
                "central+https://repo1.maven.org/maven2/",
                "sha256:" + "a".repeat(64),
                null,
                List.of());
        assertThat(art.packageKey()).isEqualTo("com.google.guava:guava:jar:");
        assertThat(art.moduleGroup()).isEqualTo("com.google.guava");
        assertThat(art.moduleArtifact()).isEqualTo("guava");
        assertThat(art.coordinate().type()).isEqualTo("jar");
        assertThat(art.coordinate().classifier()).isNull();
    }

    @Test
    void maven_package_source_package_key_includes_classifier() {
        var dep = new Pom.Dep(
                "io.netty",
                "netty-transport-native-epoll",
                "4.1.100.Final",
                "compile",
                false,
                "linux-x86_64",
                "jar",
                List.of());
        assertThat(MavenPackageSource.packageKey(dep))
                .isEqualTo("io.netty:netty-transport-native-epoll:jar:linux-x86_64");
    }
}
