// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.m2.MavenSettings;
import cc.jumpkick.model.Scope;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import cc.jumpkick.testing.SysProps;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk why} reads a row's POM where the lock says the row came from; when that is a repository
 * the project does not configure (one a dependency's POM declared), it is still asked through the
 * mirror {@code ~/.jk/config.toml} puts in front of it, never at its own URL.
 */
@ExtendWith(SysProps.class)
class EdgeSelectorsMirrorTest {

    /** The repository a dependency's POM declared, at its own URL: the lock's {@code source}. */
    @RegisterExtension
    final LoopbackHttp origin = new LoopbackHttp();

    /** What a {@code mirror-of} table routes that repository to. */
    @RegisterExtension
    final LoopbackHttp mirror = new LoopbackHttp();

    /** The project's own repository, which holds neither POM. */
    @RegisterExtension
    final LoopbackHttp project = new LoopbackHttp();

    private static final String PARENT_POM = """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.foo</groupId>
              <artifactId>parent</artifactId>
              <version>1.0</version>
              <dependencies>
                <dependency>
                  <groupId>com.foo</groupId>
                  <artifactId>child</artifactId>
                  <version>[1.0,2.0)</version>
                </dependency>
              </dependencies>
            </project>
            """;

    @Test
    void a_lock_source_outside_the_project_is_read_through_its_mirror(@TempDir Path tmp) throws Exception {
        for (LoopbackHttp http : List.of(origin, mirror)) {
            new MavenStub(http)
                    .pom("com.foo", "parent", "1.0", PARENT_POM)
                    .pom("com.foo", "child", "1.0", MavenStub.emptyPom("com.foo", "child", "1.0"));
        }
        Path home = Files.createDirectories(tmp.resolve("home"));
        Files.writeString(home.resolve("config.toml"), """
                [repositories.corp-mirror]
                url = "%s"
                mirror-of = ["corp"]
                """.formatted(mirror.baseUrl()));
        System.setProperty("jk.env.JK_HOME", home.toString());
        System.setProperty(
                MavenSettings.SETTINGS_PROPERTY,
                tmp.resolve("absent-settings.xml").toString());
        String source = "corp+" + origin.baseUrl();
        Lockfile.Artifact parent = row("com.foo:parent", source);
        Lockfile.Artifact child = row("com.foo:child", source);
        Lockfile lock = new Lockfile(
                Lockfile.CURRENT_VERSION, "jk test", Lockfile.RESOLUTION_ALGORITHM, List.of(parent, child));
        RepoGroup repos =
                RepoGroup.of(new MavenRepo("local", project.base(), new Http(), new Cas(tmp.resolve("cache"))));

        String declared = new EdgeSelectors(repos, lock).declared(parent, child);

        assertThat(declared).isEqualTo("[1.0,2.0)");
        assertThat(origin.requested())
                .as("the mirrored repository is never asked at its own URL")
                .isEmpty();
        assertThat(mirror.requested()).contains(MavenStub.path("com.foo", "parent", "1.0", ".pom"));
    }

    private static Lockfile.Artifact row(String name, String source) {
        return new Lockfile.Artifact(name, "1.0", source, null, null, List.of(Scope.MAIN), List.of(), null);
    }
}
