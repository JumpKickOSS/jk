// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A dependency's type, not its POM's packaging, says which file it is: a {@code packaging=pom}
 * module published beside a jar — an assembly such as picketbox's — locks that jar, as Maven
 * fetches a type-less dependency's jar whatever the POM says; a BOM or aggregator with no jar
 * beside it stays a row without a file.
 */
class LockPomPackagingModuleTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp().concurrent();

    private MavenStub upstream;

    @BeforeEach
    void publishJunitDefaults() {
        RepoGroup.clearProcessFetchCache();
        RepoGroup.clearProcessVersionsCache();
        upstream = new MavenStub(http)
                .leaf("org.junit.jupiter", "junit-jupiter", "6.1.0")
                .leaf("org.junit.platform", "junit-platform-launcher", "6.1.0");
    }

    @Test
    void a_pom_packaging_module_beside_a_jar_locks_the_jar_and_one_without_stays_file_less(@TempDir Path dir)
            throws Exception {
        upstream.metadata("org.picketbox", "picketbox", "5.0.3.Final");
        upstream.pomOnly("org.picketbox", "picketbox", "5.0.3.Final", """
                <project>
                  <groupId>org.picketbox</groupId><artifactId>picketbox</artifactId><version>5.0.3.Final</version>
                  <packaging>pom</packaging>
                </project>
                """);
        upstream.jar("org.picketbox", "picketbox", "5.0.3.Final");
        upstream.metadata("com.foo", "aggregator", "1.0");
        upstream.pomOnly("com.foo", "aggregator", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>aggregator</artifactId><version>1.0</version>
                  <packaging>pom</packaging>
                </project>
                """);
        // A first repository that serves nothing at all, ahead of the one that holds the POMs.
        Path empty = Files.createDirectories(dir.resolve("empty"));
        Cas cas = new Cas(dir.resolve("cache"));
        RepoGroup repos = new RepoGroup(List.of(
                new MavenRepo("empty", empty.toUri(), new Http(), cas),
                new MavenRepo("maven-stub", http.base(), new Http(), cas)));

        Lockfile lock = new LockOrchestrator(repos).lock(project(), "test");

        Lockfile.Artifact picketbox = row(lock, "org.picketbox:picketbox");
        assertThat(picketbox.checksum())
                .as("the jar beside the pom-packaging POM is what the provided row means")
                .startsWith("sha256:");
        assertThat(picketbox.source()).startsWith("maven-stub+");
        assertThat(picketbox.path()).isNull();
        assertThat(picketbox.pomOnly()).isFalse();
        assertThat(picketbox.scopes()).containsExactly(Scope.PROVIDED);
        Lockfile.Artifact aggregator = row(lock, "com.foo:aggregator");
        assertThat(aggregator.checksum())
                .as("an aggregator with no jar is a row without a file")
                .isNull();
        assertThat(aggregator.source())
                .as("a row without a file records the repository that served its POM, never one that served nothing")
                .startsWith("maven-stub+");
        assertThat(aggregator.path())
                .as("the row names the POM it stands for, so a reader can tell it from a jar nobody fetched")
                .isEqualTo("aggregator-1.0.pom");
        assertThat(aggregator.pomOnly()).isTrue();
        Lockfile reread = LockfileReader.parse(LockfileWriter.render(lock));
        assertThat(row(reread, "com.foo:aggregator").pomOnly())
                .as("the mark survives the round trip through jk-lock.toml")
                .isTrue();
    }

    private static Lockfile.Artifact row(Lockfile lock, String ga) {
        return lock.artifacts().stream()
                .filter(a -> a.name().startsWith(ga + ":"))
                .findFirst()
                .orElseThrow();
    }

    private static JkBuild project() {
        EnumMap<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.putAll(Map.of(
                Scope.PROVIDED,
                List.of(new Dependency("org.picketbox:picketbox", VersionSelector.parse("=5.0.3.Final"))),
                Scope.MAIN,
                List.of(new Dependency("com.foo:aggregator", VersionSelector.parse("=1.0")))));
        return new JkBuild(new Project("com.example", "app", "1.0", 25), new JkBuild.Dependencies(byScope));
    }
}
