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
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A dependency POM that asks for {@code LATEST} or {@code RELEASE} resolves as Maven reads the
 * repository's metadata: the newest version, or the newest release, never a version literally so
 * named. The lock pins the number; {@code jk why} reads the metaversion back from the POM.
 */
class MetaversionLockTest {

    private static final String LEAF = "com.foo:leaf:jar:";

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    private final MavenStub upstream = new MavenStub(http);

    @BeforeEach
    void publish() {
        upstream.leaf("org.junit.jupiter", "junit-jupiter", "6.1.0");
        upstream.leaf("org.junit.platform", "junit-platform-launcher", "6.1.0");
        upstream.metadata("com.foo", "leaf", "1.0", "2.0", "3.0-SNAPSHOT");
        for (String v : List.of("1.0", "2.0", "3.0-SNAPSHOT")) {
            upstream.pom("com.foo", "leaf", v, MavenStub.emptyPom("com.foo", "leaf", v));
            upstream.jar("com.foo", "leaf", v);
        }
        for (String v : List.of("1.0", "2.0")) {
            upstream.bytes(MavenStub.path("com.foo", "leaf", v, "-natives.jar"), new byte[] {0x50, 0x4b});
        }
        for (String meta : List.of("LATEST", "RELEASE")) {
            String artifact = "asks-" + meta.toLowerCase(Locale.ROOT);
            upstream.metadata("com.foo", artifact, "1.0");
            upstream.pom("com.foo", artifact, "1.0", """
                    <project>
                      <groupId>com.foo</groupId><artifactId>%s</artifactId><version>1.0</version>
                      <dependencies>
                        <dependency>
                          <groupId>com.foo</groupId><artifactId>leaf</artifactId><version>%s</version>
                        </dependency>
                      </dependencies>
                    </project>
                    """.formatted(artifact, meta));
            upstream.jar("com.foo", artifact, "1.0");
        }
        upstream.metadata("com.foo", "asks-classified", "1.0");
        upstream.pom("com.foo", "asks-classified", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>asks-classified</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>leaf</artifactId><version>RELEASE</version>
                      <classifier>natives</classifier>
                    </dependency>
                  </dependencies>
                </project>
                """);
        upstream.jar("com.foo", "asks-classified", "1.0");
    }

    /** A classified edge is otherwise exact on the version the POM wrote; the metaversion is never that version. */
    @Test
    void a_classified_edge_asking_for_release_lands_on_the_newest_release(@TempDir Path tempDir) throws Exception {
        Lockfile lock = new LockOrchestrator(repoGroup(tempDir)).lock(project("asks-classified"), "test");

        Lockfile.Artifact natives = row(lock, "com.foo:leaf:jar:natives");
        assertThat(natives.version()).isEqualTo("2.0");
        assertThat(declared(tempDir, lock, "com.foo:asks-classified:jar:", natives))
                .isEqualTo("RELEASE");
    }

    @Test
    void release_lands_on_the_newest_release_and_never_a_snapshot(@TempDir Path tempDir) throws Exception {
        Lockfile lock = new LockOrchestrator(repoGroup(tempDir)).lock(project("asks-release"), "test");

        assertThat(row(lock, LEAF).version()).isEqualTo("2.0");
        assertThat(declared(tempDir, lock, "com.foo:asks-release:jar:", row(lock, LEAF)))
                .isEqualTo("RELEASE");
    }

    @Test
    void latest_lands_on_the_newest_version_the_repository_serves(@TempDir Path tempDir) throws Exception {
        Lockfile lock = new LockOrchestrator(repoGroup(tempDir)).lock(project("asks-latest"), "test");

        Lockfile.Artifact leaf = row(lock, LEAF);
        assertThat(leaf.version()).isEqualTo("3.0-SNAPSHOT");
        assertThat(declared(tempDir, lock, "com.foo:asks-latest:jar:", leaf)).isEqualTo("LATEST");
    }

    private static JkBuild project(String middle) {
        EnumMap<Scope, List<Dependency>> byScope = new EnumMap<>(Scope.class);
        byScope.put(Scope.MAIN, List.of(new Dependency("com.foo:" + middle, VersionSelector.parse("=1.0"))));
        return new JkBuild(new Project("com.example", "test", "0.1.0", 25), new JkBuild.Dependencies(byScope));
    }

    /** What {@code parent}'s POM asked for on its edge to {@code child}, read as {@code jk why} reads it. */
    private @Nullable String declared(Path tempDir, Lockfile lock, String parent, Lockfile.Artifact child) {
        return new EdgeSelectors(repoGroup(tempDir), lock).declared(row(lock, parent), child);
    }

    private static Lockfile.Artifact row(Lockfile lock, String packageKey) {
        return lock.artifacts().stream()
                .filter(a -> a.packageKey().equals(packageKey))
                .findFirst()
                .orElseThrow(() -> new AssertionError(packageKey + " is not in the lock: " + lock.artifacts()));
    }

    private RepoGroup repoGroup(Path dir) {
        return RepoGroup.of(new MavenRepo("local", http.base(), new Http(), new Cas(dir.resolve("cache"))));
    }
}
