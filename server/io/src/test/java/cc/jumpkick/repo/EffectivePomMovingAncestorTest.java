// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A release POM's own file never changes, but a {@code -SNAPSHOT} parent or BOM it names is
 * replaced in place by the next deploy: the next build reads the model through the parent or BOM
 * as it is now, not as the first build saw it.
 */
class EffectivePomMovingAncestorTest {

    private static final String SNAPSHOT = "1.0-SNAPSHOT";

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp().concurrent();

    @BeforeEach
    void start() {
        EffectivePomBuilder.clearProcessCache();
    }

    @Test
    void a_release_child_reads_its_republished_snapshot_parent(@TempDir Path tmp) throws Exception {
        Coordinate child = Coordinate.of("org.example", "child", "1.0");
        new MavenStub(http).pomOnly("org.example", "child", "1.0", """
                <project>
                  <parent><groupId>org.example</groupId><artifactId>parent</artifactId><version>%s</version></parent>
                  <artifactId>child</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency><groupId>org.example</groupId><artifactId>dep</artifactId></dependency>
                  </dependencies>
                </project>
                """.formatted(SNAPSHOT));
        publishParent("2.0");
        assertThat(new EffectivePomBuilder(group(tmp))
                        .build(child)
                        .dependencies()
                        .get(0)
                        .version())
                .isEqualTo("2.0");

        publishParent("3.0.1");

        assertThat(new EffectivePomBuilder(group(tmp))
                        .build(child)
                        .dependencies()
                        .get(0)
                        .version())
                .as("the managed version of the parent as republished")
                .isEqualTo("3.0.1");
    }

    @Test
    void a_release_child_reads_its_republished_snapshot_bom(@TempDir Path tmp) throws Exception {
        Coordinate child = Coordinate.of("org.example", "child", "1.0");
        new MavenStub(http).pomOnly("org.example", "child", "1.0", """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>child</artifactId>
                  <version>1.0</version>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>org.example</groupId><artifactId>bom</artifactId><version>%s</version>
                      <type>pom</type><scope>import</scope></dependency>
                  </dependencies></dependencyManagement>
                  <dependencies>
                    <dependency><groupId>org.example</groupId><artifactId>dep</artifactId></dependency>
                  </dependencies>
                </project>
                """.formatted(SNAPSHOT));
        publishBom("2.0");
        assertThat(new EffectivePomBuilder(group(tmp))
                        .build(child)
                        .dependencies()
                        .get(0)
                        .version())
                .isEqualTo("2.0");

        publishBom("3.0.1");

        assertThat(new EffectivePomBuilder(group(tmp))
                        .build(child)
                        .dependencies()
                        .get(0)
                        .version())
                .as("the managed version of the BOM as republished")
                .isEqualTo("3.0.1");
    }

    /** The repositories one build asks, over one store: the next build opens them again. */
    private RepoGroup group(Path tmp) {
        return RepoGroup.of(new MavenRepo("snapshots", http.base(), new Http(), new Cas(tmp.resolve("cache")))
                .withPolicy(true, true));
    }

    private void publishParent(String managed) {
        new MavenStub(http).pomOnly("org.example", "parent", SNAPSHOT, managing("parent", managed));
    }

    private void publishBom(String managed) {
        new MavenStub(http).pomOnly("org.example", "bom", SNAPSHOT, managing("bom", managed));
    }

    private static String managing(String artifact, String version) {
        return """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <packaging>pom</packaging>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>org.example</groupId><artifactId>dep</artifactId><version>%s</version></dependency>
                  </dependencies></dependencyManagement>
                </project>
                """.formatted(artifact, SNAPSHOT, version);
    }
}
