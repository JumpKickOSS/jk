// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.http.Http;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.maven.model.Model;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * The reactor walk hands each module to its visitor with the effective model and then drops that
 * model from the resolver's memo, so a reactor of hundreds of modules holds one module's model at
 * a time; only the root and the BOMs other modules import stay built.
 */
class ReactorModulesTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp().concurrent();

    /**
     * The modules of one aggregator have their effective models built side by side — the parent
     * and BOM POMs they read from a repository are fetched several at a time — and are handed to
     * the visitor in walk order all the same: six modules each importing a BOM of its own from a
     * repository that holds every answer for a moment have more than one read in flight at once.
     */
    @Test
    void sibling_modules_read_their_parent_and_bom_poms_in_parallel_and_are_visited_in_order(@TempDir Path root)
            throws Exception {
        MavenStub upstream = new MavenStub(http);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger widest = new AtomicInteger();
        http.beforeServe(path -> {
            if (!path.endsWith(".pom")) return; // a checksum sidecar rides beside its POM
            widest.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
        });
        StringBuilder modules = new StringBuilder();
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String name = "m" + i;
            upstream.pomOnly(
                    "org.remote", "bom-" + i, "1.0", MavenStub.bom("org.remote", "bom-" + i, "1.0", List.of()));
            modules.append("<module>").append(name).append("</module>");
            expected.add(name + "=" + name);
            write(root, name + "/pom.xml", """
                    <project>
                      <modelVersion>4.0.0</modelVersion>
                      %s
                      <artifactId>%s</artifactId>
                      <dependencyManagement>
                        <dependencies>
                          <dependency>
                            <groupId>org.remote</groupId>
                            <artifactId>bom-%d</artifactId>
                            <version>1.0</version>
                            <type>pom</type>
                            <scope>import</scope>
                          </dependency>
                        </dependencies>
                      </dependencyManagement>
                    </project>
                    """.formatted(parent(), name, i));
        }
        write(root, "pom.xml", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.demo</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <modules>%s</modules>
                </project>
                """.formatted(modules));
        Path rootPom = root.resolve("pom.xml");
        byte[] rootXml = Files.readAllBytes(rootPom);
        // No Maven local-repository adoption: a copy a run before left there would answer without a read.
        Cas cas = new Cas(root.resolve("cache"));
        MavenRepo fixture = new MavenRepo("fixture", http.base(), new Http(), cas, RepoCredential.ANONYMOUS, false);
        ReactorModelResolver reactor =
                new ReactorModelResolver(new RepoModelResolver(RepoGroup.of(fixture), cas), List.of());
        List<String> visited = new ArrayList<>();

        ReactorModules.collect(
                rootPom,
                rootXml,
                EffectiveModel.rawModel(rootXml),
                reactor,
                ImportReport.builder(),
                (leaf, model) -> visited.add(leaf.path() + "=" + model.model().getArtifactId()));

        assertThat(visited).containsExactlyElementsOf(expected);
        assertThat(widest.get()).as("POM reads in flight at once").isGreaterThan(1);
    }

    @Test
    void modules_are_visited_in_walk_order_and_their_models_leave_the_memo(@TempDir Path root) throws Exception {
        write(root, "pom.xml", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.demo</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <modules>
                    <module>bom</module>
                    <module>libs</module>
                    <module>app</module>
                  </modules>
                </project>
                """);
        write(root, "bom/pom.xml", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  %s
                  <artifactId>bom</artifactId>
                  <packaging>pom</packaging>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.demo</groupId>
                        <artifactId>core</artifactId>
                        <version>1.0.0</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """.formatted(parent()));
        write(root, "libs/pom.xml", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  %s
                  <artifactId>libs</artifactId>
                  <packaging>pom</packaging>
                  <modules>
                    <module>core</module>
                  </modules>
                </project>
                """.formatted(parent()));
        write(root, "libs/core/pom.xml", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>org.demo</groupId>
                    <artifactId>libs</artifactId>
                    <version>1.0.0</version>
                  </parent>
                  <artifactId>core</artifactId>
                </project>
                """);
        write(root, "app/pom.xml", """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  %s
                  <artifactId>app</artifactId>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.demo</groupId>
                        <artifactId>bom</artifactId>
                        <version>1.0.0</version>
                        <type>pom</type>
                        <scope>import</scope>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                  <dependencies>
                    <dependency>
                      <groupId>org.demo</groupId>
                      <artifactId>core</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(parent()));

        Path rootPom = root.resolve("pom.xml");
        byte[] rootXml = Files.readAllBytes(rootPom);
        Model rootRaw = EffectiveModel.rawModel(rootXml);
        ReactorModelResolver reactor = new ReactorModelResolver(TestImporters.offline(root).resolver, List.of());
        List<String> visited = new ArrayList<>();
        List<Path> retainedWhileVisiting = new ArrayList<>();
        ImportReport.Builder report = ImportReport.builder();

        ReactorModules.Reactor found =
                ReactorModules.collect(rootPom, rootXml, rootRaw, reactor, report, (leaf, model) -> {
                    visited.add(leaf.path() + "=" + model.model().getArtifactId());
                    retainedWhileVisiting.addAll(reactor.retained());
                });

        assertThat(visited).containsExactly("libs/core=core", "app=app");
        assertThat(found.modules())
                .extracting(ReactorModules.Leaf::ga)
                .containsExactly("org.demo:core", "org.demo:app");
        assertThat(found.boms()).extracting(ReactorModules.Leaf::path).containsExactly("bom");
        assertThat(retainedWhileVisiting)
                .as("a module's model is in the memo while its visitor runs")
                .contains(root.resolve("libs/core/pom.xml"), root.resolve("app/pom.xml"));
        assertThat(reactor.retained())
                .as("after the walk only the root and the BOM another module imports stay built")
                .containsExactlyInAnyOrder(rootPom, root.resolve("bom/pom.xml"));
        assertThat(requireNonNull(reactor.effective(root.resolve("app/pom.xml")).model())
                        .getDependencies())
                .as("a released model is rebuilt on demand, BOM import included")
                .singleElement()
                .satisfies(d -> assertThat(d.getVersion()).isEqualTo("1.0.0"));
    }

    private static String parent() {
        return """
                <parent>
                  <groupId>org.demo</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0.0</version>
                </parent>
                """;
    }

    private static void write(Path root, String path, String xml) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(requireNonNull(file.getParent()));
        Files.writeString(file, xml);
    }
}
