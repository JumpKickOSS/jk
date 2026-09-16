// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A manifest entry with {@code classifier = "natives-linux"} locks the classified jar of its module
 * — the artifact a Maven {@code <classifier>} names — and a second entry for the plain jar of the
 * same module locks beside it; a module whose only jar is classified locks without the plain one.
 */
class LockClassifiedRootTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp().concurrent();

    @Test
    void a_classified_entry_locks_the_classified_jar_beside_the_plain_one(@TempDir Path dir) throws Exception {
        RepoGroup.clearProcessFetchCache();
        RepoGroup.clearProcessVersionsCache();
        new MavenStub(http)
                .leaf("org.junit.jupiter", "junit-jupiter", "6.1.0")
                .leaf("org.junit.platform", "junit-platform-launcher", "6.1.0")
                .leaf("org.lwjgl", "lwjgl", "3.3.6")
                .bytes(MavenStub.path("org.lwjgl", "lwjgl", "3.3.6", "-natives-linux.jar"), new byte[] {0x50, 0x4b});
        Cas cas = new Cas(dir.resolve("cache"));
        RepoGroup repos = RepoGroup.of(new MavenRepo("maven-stub", http.base(), new Http(), cas));

        EnumMap<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.putAll(Map.of(
                Scope.MAIN,
                List.of(
                        Dependency.of("lwjgl", "org.lwjgl:lwjgl", VersionSelector.parse("=3.3.6")),
                        Dependency.of("lwjgl-natives-linux", "org.lwjgl:lwjgl", VersionSelector.parse("=3.3.6"))
                                .withClassifier("natives-linux"))));
        JkBuild project = new JkBuild(new Project("com.example", "app", "1.0", 25), new JkBuild.Dependencies(byScope));

        Lockfile lock = new LockOrchestrator(repos).lock(project, "test", List.of(), true, ResolveObserver.NOOP);

        assertThat(lock.artifacts())
                .extracting(Lockfile.Artifact::name)
                .contains("org.lwjgl:lwjgl:jar:", "org.lwjgl:lwjgl:jar:natives-linux");
        assertThat(lock.artifacts())
                .filteredOn(a -> a.name().equals("org.lwjgl:lwjgl:jar:natives-linux"))
                .singleElement()
                .satisfies(a -> {
                    assertThat(a.version()).isEqualTo("3.3.6");
                    assertThat(a.coordinate().classifier()).isEqualTo("natives-linux");
                });
    }

    /**
     * A module published with a classified jar and no plain one — the POM and {@code
     * calcite-core-1.35.36-de.jar} with no {@code calcite-core-1.35.36.jar} beside them, as
     * fit2cloud-public serves calcite-core — locks from the classifier alone, the way Maven
     * resolves a {@code <classifier>de</classifier>} dependency, without asking for the plain jar.
     */
    @Test
    void a_module_whose_only_jar_is_classified_locks_from_the_classifier_alone(@TempDir Path dir) throws Exception {
        RepoGroup.clearProcessFetchCache();
        RepoGroup.clearProcessVersionsCache();
        new MavenStub(http)
                .leaf("org.junit.jupiter", "junit-jupiter", "6.1.0")
                .leaf("org.junit.platform", "junit-platform-launcher", "6.1.0")
                .metadata("org.apache.calcite", "calcite-core", "1.35.36")
                .pomWithoutJar("org.apache.calcite", "calcite-core", "1.35.36")
                .bytes(
                        MavenStub.path("org.apache.calcite", "calcite-core", "1.35.36", "-de.jar"),
                        new byte[] {0x50, 0x4b});
        Cas cas = new Cas(dir.resolve("cache"));
        RepoGroup repos = RepoGroup.of(new MavenRepo("fit2cloud-public", http.base(), new Http(), cas));

        EnumMap<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.put(
                Scope.MAIN,
                List.of(Dependency.of(
                                "calcite-core-de", "org.apache.calcite:calcite-core", VersionSelector.parse("=1.35.36"))
                        .withClassifier("de")));
        JkBuild project = new JkBuild(new Project("com.example", "app", "1.0", 25), new JkBuild.Dependencies(byScope));

        Lockfile lock = new LockOrchestrator(repos).lock(project, "test", List.of(), true, ResolveObserver.NOOP);

        assertThat(lock.artifacts())
                .extracting(Lockfile.Artifact::name)
                .contains("org.apache.calcite:calcite-core:jar:de")
                .doesNotContain("org.apache.calcite:calcite-core:jar:");
        assertThat(lock.artifacts())
                .filteredOn(a -> a.name().equals("org.apache.calcite:calcite-core:jar:de"))
                .singleElement()
                .satisfies(a -> {
                    assertThat(a.checksum()).startsWith("sha256:");
                    assertThat(a.source()).startsWith("fit2cloud-public+");
                });
    }
}
