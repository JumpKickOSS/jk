// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.PackageId;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.resolver.pubgrub.InMemoryPackageSource;
import cc.jumpkick.resolver.pubgrub.VersionSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Dual-classifier package identity: plain jar and a classifier of the same GA must resolve as
 * distinct packages at the same version (ticket-1002).
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
    void bare_ga_lock_name_normalizes_to_default_jar_package_key() {
        assertThat(PackageId.parse("com.google.guava:guava").key()).isEqualTo("com.google.guava:guava:jar:");
        var art = new cc.jumpkick.lock.Lockfile.Artifact(
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
        var dep = new cc.jumpkick.repo.Pom.Dep(
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
