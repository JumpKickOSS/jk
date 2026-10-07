// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.testing.LoopbackHttp;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * The process memo answers for the POM file a group serves, not for the group: a group grown by a
 * POM's own repositories, or the next lock's builder, reaches the same file and the same model.
 */
class EffectivePomProcessMemoTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    @BeforeEach
    void start() {
        EffectivePomBuilder.clearProcessCache();
        http.served().put("/org/example/parent/1.0/parent-1.0.pom", """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>org.example</groupId><artifactId>dep</artifactId><version>2.0</version></dependency>
                  </dependencies></dependencyManagement>
                </project>
                """.getBytes(StandardCharsets.UTF_8));
        http.served().put("/org/example/child/1.0/child-1.0.pom", """
                <project>
                  <parent><groupId>org.example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                  <artifactId>child</artifactId>
                  <dependencies>
                    <dependency><groupId>org.example</groupId><artifactId>dep</artifactId></dependency>
                  </dependencies>
                </project>
                """.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void a_second_group_reaching_the_same_file_reuses_the_model(@TempDir Path tmp) throws Exception {
        Cas cas = new Cas(tmp.resolve("cache"));
        MavenRepo local = new MavenRepo("local", http.base(), new Http(), cas);
        Coordinate child = Coordinate.of("org.example", "child", "1.0");
        EffectivePom first = new EffectivePomBuilder(RepoGroup.of(local)).build(child);
        assertThat(first.dependencies().get(0).version()).isEqualTo("2.0");

        long parsed = StorePoms.reads();
        int requests = http.requested().size();
        MavenRepo declared =
                new MavenRepo("declared", tmp.resolve("never-dialed").toUri(), new Http(), cas);
        RepoGroup grown = new RepoGroup(List.of(local, declared));
        assertThat(grown.processIdentity()).isNotEqualTo(RepoGroup.of(local).processIdentity());

        EffectivePom second = new EffectivePomBuilder(grown).build(child);

        assertThat(second).as("the model built from the same file").isSameAs(first);
        assertThat(StorePoms.reads()).as("no POM parsed again").isEqualTo(parsed);
        assertThat(http.requested()).hasSize(requests);
    }

    @Test
    void the_next_builder_over_the_same_group_hits_the_memo(@TempDir Path tmp) throws Exception {
        Cas cas = new Cas(tmp.resolve("cache"));
        RepoGroup group = RepoGroup.of(new MavenRepo("local", http.base(), new Http(), cas));
        Coordinate child = Coordinate.of("org.example", "child", "1.0");
        EffectivePom first = new EffectivePomBuilder(group).build(child);
        long parsed = StorePoms.reads();

        assertThat(new EffectivePomBuilder(group).build(child)).isSameAs(first);
        assertThat(StorePoms.reads()).isEqualTo(parsed);
    }

    @Test
    void the_raw_pom_is_parsed_once_across_builders_while_the_model_memo_is_dropped(@TempDir Path tmp)
            throws Exception {
        Cas cas = new Cas(tmp.resolve("cache"));
        RepoGroup group = RepoGroup.of(new MavenRepo("local", http.base(), new Http(), cas));
        Coordinate child = Coordinate.of("org.example", "child", "1.0");
        new EffectivePomBuilder(group).build(child);
        long parsed = StorePoms.reads();

        EffectivePomBuilder.dropProcessMemo();
        EffectivePom rebuilt = new EffectivePomBuilder(group).build(child);

        assertThat(rebuilt.dependencies().get(0).version()).isEqualTo("2.0");
        assertThat(StorePoms.reads())
                .as("the store's POM files were not read again")
                .isEqualTo(parsed);
    }
}
