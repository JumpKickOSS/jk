// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.runtime.LockFlow;
import cc.jumpkick.runtime.ShadowManifests;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A {@code pom.xml} built in place pins {@code jakarta.inject-api 2.0.1}, below the {@code 2.0.1.MR}
 * its dependency {@code cryptofs 2.10.0} needs. The shadow raises the pin as {@code jk import} would,
 * says so once, and the in-place build locks.
 */
class ShadowPinRaisesTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    private final MavenStub upstream = new MavenStub(http);

    @TempDir
    Path isolatedStore;

    @BeforeEach
    void publish() {
        System.setProperty("jk.env.JK_STORE_DIR", isolatedStore.resolve("store").toString());
        upstream.leaf("org.junit.jupiter", "junit-jupiter", "6.1.0");
        upstream.leaf("org.junit.platform", "junit-platform-launcher", "6.1.0");
        upstream.metadata("jakarta.inject", "jakarta.inject-api", "2.0.1", "2.0.1.MR");
        for (String v : List.of("2.0.1", "2.0.1.MR")) {
            upstream.pom(
                    "jakarta.inject",
                    "jakarta.inject-api",
                    v,
                    MavenStub.emptyPom("jakarta.inject", "jakarta.inject-api", v));
            upstream.jar("jakarta.inject", "jakarta.inject-api", v);
        }
        upstream.metadata("org.cryptomator", "cryptofs", "2.10.0");
        upstream.pom("org.cryptomator", "cryptofs", "2.10.0", """
                <project>
                  <groupId>org.cryptomator</groupId><artifactId>cryptofs</artifactId><version>2.10.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>jakarta.inject</groupId><artifactId>jakarta.inject-api</artifactId><version>2.0.1.MR</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        upstream.jar("org.cryptomator", "cryptofs", "2.10.0");
    }

    @AfterEach
    void releaseStoreOverride() {
        System.clearProperty("jk.env.JK_STORE_DIR");
        ShadowManifests.install(dir -> List.of());
    }

    @Test
    void the_in_place_build_raises_a_pin_below_a_dependency_s_floor_and_locks(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.cryptomator</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0</version>
                  <properties><maven.compiler.release>25</maven.compiler.release></properties>
                  <repositories>
                    <repository><id>stub</id><url>%s</url></repository>
                  </repositories>
                  <dependencies>
                    <dependency><groupId>org.cryptomator</groupId><artifactId>cryptofs</artifactId><version>2.10.0</version></dependency>
                    <dependency><groupId>jakarta.inject</groupId><artifactId>jakarta.inject-api</artifactId><version>2.0.1</version></dependency>
                  </dependencies>
                </project>
                """.formatted(http.base()));
        ShadowManifests.install(dir -> ImportPinRaises.apply(dir, project.resolve("cache-probe"), http.base()));

        Path shadow = ManifestPaths.manifestIn(project);

        assertThat(shadow).isEqualTo(ManifestPaths.shadowManifestPath(project));
        assertThat(Files.readString(shadow))
                .contains("jakarta.inject:jakarta.inject-api:2.0.1.MR")
                .doesNotContain("jakarta.inject:jakarta.inject-api:2.0.1\"");
        assertThat(ShadowManifests.drainTier3(project)).anySatisfy(line -> assertThat(line)
                .startsWith("`jakarta.inject:jakarta.inject-api` 2.0.1 → 2.0.1.MR")
                .contains("highest wins"));
        assertThat(ShadowManifests.drainTier3(project))
                .as("said once per POM change")
                .isEmpty();
        assertThat(project.resolve("jk.toml")).as("the tree is not written").doesNotExist();

        LockFlow.Result lock = LockFlow.run(project, project.resolve("cache-lock"), List.of(), false, http.base());
        assertThat(lock.status()).as(String.valueOf(lock.error())).isZero();
        assertThat(requireNonNull(lock.lockfile()).artifacts())
                .filteredOn(a -> a.name().startsWith("jakarta.inject:jakarta.inject-api:"))
                .extracting(Lockfile.Artifact::version)
                .containsExactly("2.0.1.MR");
    }
}
