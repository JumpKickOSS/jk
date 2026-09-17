// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.testing.LoopbackHttp;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * The managed tables of a chain are shared objects: a BOM's rows are valued once and every POM that
 * imports or inherits them carries the same objects, and an intermediate that manages nothing of its
 * own carries its parent's table itself. This is what keeps a reactor whose every module chains to a
 * three-thousand-row BOM inside the engine heap.
 */
class EffectivePomSharedTablesTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    @BeforeEach
    void start() {
        EffectivePomBuilder.clearProcessCache();
    }

    @Test
    void a_managed_entry_a_bom_supplies_is_one_object_in_every_table_that_carries_it(@TempDir Path tempDir)
            throws Exception {
        registerPom("org.example", "bom", "1.0", """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>bom</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <properties><lib.version>2.5</lib.version></properties>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>org.example</groupId><artifactId>lib</artifactId><version>${lib.version}</version>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """);
        registerPom("org.example", "parent", "1.0", """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>org.example</groupId><artifactId>bom</artifactId><version>1.0</version>
                      <type>pom</type><scope>import</scope>
                    </dependency>
                  </dependencies></dependencyManagement>
                </project>
                """);
        registerPom("org.example", "middle", "1.0", """
                <project>
                  <parent>
                    <groupId>org.example</groupId><artifactId>parent</artifactId><version>1.0</version>
                  </parent>
                  <artifactId>middle</artifactId>
                  <packaging>pom</packaging>
                </project>
                """);
        registerPom("org.example", "leaf", "1.0", """
                <project>
                  <parent>
                    <groupId>org.example</groupId><artifactId>middle</artifactId><version>1.0</version>
                  </parent>
                  <artifactId>leaf</artifactId>
                  <dependencies>
                    <dependency><groupId>org.example</groupId><artifactId>lib</artifactId></dependency>
                  </dependencies>
                </project>
                """);

        EffectivePomBuilder builder = newBuilder(tempDir);
        EffectivePom leaf = builder.build(Coordinate.of("org.example", "leaf", "1.0"));
        EffectivePom bom = builder.build(Coordinate.of("org.example", "bom", "1.0"));
        EffectivePom parent = builder.build(Coordinate.of("org.example", "parent", "1.0"));
        EffectivePom middle = builder.build(Coordinate.of("org.example", "middle", "1.0"));

        Pom.Dep row = bom.managedDependencies().get(0);
        assertThat(row.version()).as("valued once, in the BOM's own merge").isEqualTo("2.5");
        assertThat(parent.managedDependencies())
                .as("the importer carries the BOM's object, not a copy")
                .hasSize(1)
                .first()
                .isSameAs(row);
        assertThat(middle.managedDependencies())
                .as("an intermediate that manages nothing of its own shares its parent's table")
                .isSameAs(parent.managedDependencies());
        assertThat(middle.importedManagedKeys()).isSameAs(parent.importedManagedKeys());
        assertThat(leaf.dependencies().get(0).version()).isEqualTo("2.5");
    }

    private EffectivePomBuilder newBuilder(Path tempDir) {
        Cas cas = new Cas(tempDir.resolve("cache"));
        return new EffectivePomBuilder(new MavenRepo("local", http.base(), new Http(), cas));
    }

    private void registerPom(String group, String artifact, String version, String body) {
        String path = "/" + group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-" + version
                + ".pom";
        http.served().put(path, body.getBytes(StandardCharsets.UTF_8));
    }
}
