// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R4: POM {@code <exclusions>} strip modules from the excluded package's expansion (and cascade),
 * so an only-path exclusion drops the module from the resolution.
 */
class MavenPackageSourceExclusionTest {

    private HttpServer server;
    private URI base;
    private final Map<String, byte[]> served = new HashMap<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = served.get(exchange.getRequestURI().getPath());
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void exclusion_drops_module_when_only_path(@TempDir Path tempDir) throws Exception {
        // root → parent@1.0 → child@1.0, but parent excludes leaf; parent→child→leaf would pull leaf
        // without exclusion. parent excludes com.foo:leaf.
        serveMetadata("/com/foo/parent/maven-metadata.xml", "com.foo", "parent", List.of("1.0"));
        serveMetadata("/com/foo/child/maven-metadata.xml", "com.foo", "child", List.of("1.0"));
        serveMetadata("/com/foo/leaf/maven-metadata.xml", "com.foo", "leaf", List.of("1.0"));
        servePom("com.foo", "parent", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>parent</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>child</artifactId><version>1.0</version>
                      <exclusions>
                        <exclusion>
                          <groupId>com.foo</groupId><artifactId>leaf</artifactId>
                        </exclusion>
                      </exclusions>
                    </dependency>
                  </dependencies>
                </project>
                """);
        servePom("com.foo", "child", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>child</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>leaf</artifactId><version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        servePom("com.foo", "leaf", "1.0", emptyPom("com.foo", "leaf", "1.0"));

        PubGrubResolver resolver = new PubGrubResolver(repoGroup(tempDir));
        Resolution result = resolver.resolve(List.of(new Dependency("com.foo:parent", VersionSelector.parse("=1.0"))));

        assertThat(result.modules()).containsKeys("com.foo:parent:jar:", "com.foo:child:jar:");
        assertThat(result.modules()).doesNotContainKey("com.foo:leaf");
    }

    @Test
    void other_parent_can_still_pull_excluded_module(@TempDir Path tempDir) throws Exception {
        // root → parent (excludes leaf via child) + other → leaf directly.
        serveMetadata("/com/foo/parent/maven-metadata.xml", "com.foo", "parent", List.of("1.0"));
        serveMetadata("/com/foo/child/maven-metadata.xml", "com.foo", "child", List.of("1.0"));
        serveMetadata("/com/foo/other/maven-metadata.xml", "com.foo", "other", List.of("1.0"));
        serveMetadata("/com/foo/leaf/maven-metadata.xml", "com.foo", "leaf", List.of("1.0"));
        servePom("com.foo", "parent", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>parent</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>child</artifactId><version>1.0</version>
                      <exclusions>
                        <exclusion>
                          <groupId>com.foo</groupId><artifactId>leaf</artifactId>
                        </exclusion>
                      </exclusions>
                    </dependency>
                  </dependencies>
                </project>
                """);
        servePom("com.foo", "child", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>child</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>leaf</artifactId><version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        servePom("com.foo", "other", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>other</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>leaf</artifactId><version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        servePom("com.foo", "leaf", "1.0", emptyPom("com.foo", "leaf", "1.0"));

        PubGrubResolver resolver = new PubGrubResolver(repoGroup(tempDir));
        Resolution result = resolver.resolve(List.of(
                new Dependency("com.foo:parent", VersionSelector.parse("=1.0")),
                new Dependency("com.foo:other", VersionSelector.parse("=1.0"))));

        assertThat(result.modules()).containsKey("com.foo:leaf:jar:");
    }

    /**
     * two paths reach the same package, one under an exclusion and one not. Maven drops a
     * dependency only when EVERY path that reaches it excludes it, so leaf must survive on the
     * app → target edge. Ordering matters — app lists the excluding edge first, so a resolver that
     * accumulates exclusions per package sees the excluded view before the clean one.
     *
     * <p>This is the shape that breaks Quarkus in the real world: quarkus-bootstrap-maven-resolver
     * excludes httpclient on its smallrye-beanbag-maven edge, and its own direct edge to
     * maven-resolver-transport-http does not — Gradle keeps httpclient, jk dropped it.
     */
    @Test
    void an_unexcluded_path_wins_over_an_excluded_one_regardless_of_order(@TempDir Path tempDir) throws Exception {
        serveMetadata("/com/foo/app/maven-metadata.xml", "com.foo", "app", List.of("1.0"));
        serveMetadata("/com/foo/mid/maven-metadata.xml", "com.foo", "mid", List.of("1.0"));
        serveMetadata("/com/foo/target/maven-metadata.xml", "com.foo", "target", List.of("1.0"));
        serveMetadata("/com/foo/leaf/maven-metadata.xml", "com.foo", "leaf", List.of("1.0"));
        servePom("com.foo", "app", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>app</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>mid</artifactId><version>1.0</version>
                      <exclusions>
                        <exclusion>
                          <groupId>com.foo</groupId><artifactId>leaf</artifactId>
                        </exclusion>
                      </exclusions>
                    </dependency>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>target</artifactId><version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        servePom("com.foo", "mid", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>mid</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>target</artifactId><version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        servePom("com.foo", "target", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>target</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>leaf</artifactId><version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        servePom("com.foo", "leaf", "1.0", emptyPom("com.foo", "leaf", "1.0"));

        Resolution result = new PubGrubResolver(repoGroup(tempDir))
                .resolve(List.of(new Dependency("com.foo:app", VersionSelector.parse("=1.0"))));

        assertThat(result.modules()).containsKey("com.foo:leaf:jar:");
        assertThat(result.modules().get("com.foo:target:jar:").deps()).anyMatch(d -> d.startsWith("com.foo:leaf"));
    }

    /**
     * The clean path is discovered a level deeper, after the excluded subtree has already been
     * walked: root → a → mid (excludes leaf) → target, and root → b → target. Whichever order
     * PubGrub explores in, leaf is reachable un-excluded, so it stays.
     */
    @Test
    void a_clean_path_found_later_still_restores_the_module(@TempDir Path tempDir) throws Exception {
        for (String a : List.of("a", "b", "mid", "target", "leaf")) {
            serveMetadata("/com/foo/" + a + "/maven-metadata.xml", "com.foo", a, List.of("1.0"));
        }
        servePom("com.foo", "a", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>a</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>mid</artifactId><version>1.0</version>
                      <exclusions>
                        <exclusion><groupId>com.foo</groupId><artifactId>leaf</artifactId></exclusion>
                      </exclusions>
                    </dependency>
                  </dependencies>
                </project>
                """);
        servePom("com.foo", "b", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>b</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>target</artifactId><version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        servePom("com.foo", "mid", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>mid</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>target</artifactId><version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        servePom("com.foo", "target", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>target</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>leaf</artifactId><version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        servePom("com.foo", "leaf", "1.0", emptyPom("com.foo", "leaf", "1.0"));

        Resolution result = new PubGrubResolver(repoGroup(tempDir))
                .resolve(List.of(
                        new Dependency("com.foo:a", VersionSelector.parse("=1.0")),
                        new Dependency("com.foo:b", VersionSelector.parse("=1.0"))));

        assertThat(result.modules()).containsKey("com.foo:leaf:jar:");
    }

    /** Excluded on every path that reaches it — still dropped. */
    /**
     * The clean path is discovered <em>deeper</em> than the excluding one: root → a → mid
     * (excludes leaf) → target, and root → c → d → e → target. `target` is decided while only
     * mid's {leaf} registration exists; e's empty registration arrives after. The resolver's
     * stale-expansion fixpoint must re-solve with the converged (empty) set so leaf stays.
     */
    @Test
    void a_clean_path_found_deeper_still_restores_the_module(@TempDir Path tempDir) throws Exception {
        for (String a : List.of("app", "a", "c", "d", "e", "mid", "target", "leaf")) {
            serveMetadata("/com/foo/" + a + "/maven-metadata.xml", "com.foo", a, List.of("1.0"));
        }
        servePom("com.foo", "app", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>app</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>a</artifactId><version>1.0</version>
                    </dependency>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>c</artifactId><version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        servePom("com.foo", "a", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>a</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>mid</artifactId><version>1.0</version>
                      <exclusions>
                        <exclusion><groupId>com.foo</groupId><artifactId>leaf</artifactId></exclusion>
                      </exclusions>
                    </dependency>
                  </dependencies>
                </project>
                """);
        servePom("com.foo", "c", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>c</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>d</artifactId><version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        servePom("com.foo", "d", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>d</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>e</artifactId><version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        servePom("com.foo", "e", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>e</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>target</artifactId><version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        servePom("com.foo", "mid", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>mid</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>target</artifactId><version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        servePom("com.foo", "target", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>target</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>leaf</artifactId><version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        servePom("com.foo", "leaf", "1.0", emptyPom("com.foo", "leaf", "1.0"));

        Resolution result = new PubGrubResolver(repoGroup(tempDir))
                .resolve(List.of(new Dependency("com.foo:app", VersionSelector.parse("=1.0"))));

        assertThat(result.modules()).containsKey("com.foo:leaf:jar:");
        assertThat(result.modules().get("com.foo:target:jar:").deps()).anyMatch(d -> d.startsWith("com.foo:leaf"));
    }

    @Test
    void an_exclusion_on_every_path_still_drops_the_module(@TempDir Path tempDir) throws Exception {
        serveMetadata("/com/foo/app/maven-metadata.xml", "com.foo", "app", List.of("1.0"));
        serveMetadata("/com/foo/mid/maven-metadata.xml", "com.foo", "mid", List.of("1.0"));
        serveMetadata("/com/foo/target/maven-metadata.xml", "com.foo", "target", List.of("1.0"));
        serveMetadata("/com/foo/leaf/maven-metadata.xml", "com.foo", "leaf", List.of("1.0"));
        servePom("com.foo", "app", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>app</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>mid</artifactId><version>1.0</version>
                      <exclusions>
                        <exclusion><groupId>com.foo</groupId><artifactId>leaf</artifactId></exclusion>
                      </exclusions>
                    </dependency>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>target</artifactId><version>1.0</version>
                      <exclusions>
                        <exclusion><groupId>com.foo</groupId><artifactId>leaf</artifactId></exclusion>
                      </exclusions>
                    </dependency>
                  </dependencies>
                </project>
                """);
        servePom("com.foo", "mid", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>mid</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>target</artifactId><version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        servePom("com.foo", "target", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>target</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>leaf</artifactId><version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        servePom("com.foo", "leaf", "1.0", emptyPom("com.foo", "leaf", "1.0"));

        Resolution result = new PubGrubResolver(repoGroup(tempDir))
                .resolve(List.of(new Dependency("com.foo:app", VersionSelector.parse("=1.0"))));

        assertThat(result.modules()).doesNotContainKey("com.foo:leaf:jar:");
    }

    /**
     * The accumulator's own contract: registrations intersect, so a path that strips nothing
     * clears the set no matter which order the paths arrive in.
     */
    @Test
    void registered_exclusions_intersect_across_paths(@TempDir Path tempDir) {
        MavenPackageSource src = new MavenPackageSource(
                repoGroup(tempDir), new cc.jumpkick.repo.EffectivePomBuilder(repoGroup(tempDir)));

        src.registerExclusions("com.foo:target", Set.of("com.foo:leaf", "com.foo:other"));
        assertThat(src.exclusionsFor("com.foo:target")).containsExactlyInAnyOrder("com.foo:leaf", "com.foo:other");

        // A second path strips only one of them — the other is no longer excluded everywhere.
        src.registerExclusions("com.foo:target", Set.of("com.foo:leaf"));
        assertThat(src.exclusionsFor("com.foo:target")).containsExactly("com.foo:leaf");

        // A path that strips nothing clears the set.
        src.registerExclusions("com.foo:target", Set.of());
        assertThat(src.exclusionsFor("com.foo:target")).isEmpty();

        // And an unencumbered path first is just as final.
        src.registerExclusions("com.foo:second", Set.of());
        src.registerExclusions("com.foo:second", Set.of("com.foo:leaf"));
        assertThat(src.exclusionsFor("com.foo:second")).isEmpty();
    }

    /**
     * one shared source serves the main → test → processor scope solves. A clean
     * main-scope path collapses target's exclusion set to empty (intersection semantics); the
     * per-solve reset must keep that from bleeding into the test solve, where EVERY path
     * excludes leaf — otherwise the test graph over-includes it.
     */
    @Test
    void a_later_scope_solve_honors_its_own_exclusions_after_a_clean_main_path(@TempDir Path tempDir) throws Exception {
        for (String a : List.of("clean", "excluder", "target", "leaf")) {
            serveMetadata("/com/foo/" + a + "/maven-metadata.xml", "com.foo", a, List.of("1.0"));
        }
        servePom("com.foo", "clean", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>clean</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>target</artifactId><version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        servePom("com.foo", "excluder", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>excluder</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>target</artifactId><version>1.0</version>
                      <exclusions>
                        <exclusion><groupId>com.foo</groupId><artifactId>leaf</artifactId></exclusion>
                      </exclusions>
                    </dependency>
                  </dependencies>
                </project>
                """);
        servePom("com.foo", "target", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>target</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>leaf</artifactId><version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        servePom("com.foo", "leaf", "1.0", emptyPom("com.foo", "leaf", "1.0"));

        RepoGroup repos = repoGroup(tempDir);
        cc.jumpkick.repo.EffectivePomBuilder pomBuilder = new cc.jumpkick.repo.EffectivePomBuilder(repos);
        MavenPackageSource shared = new MavenPackageSource(repos, pomBuilder);

        // Main scope: the only path to target is clean, so leaf stays (and target's exclusion
        // set converges to empty).
        Resolution main = new PubGrubResolver(shared, pomBuilder, KmpRedirects.NONE)
                .resolve(List.of(new Dependency("com.foo:clean", VersionSelector.parse("=1.0"))));
        assertThat(main.modules()).containsKey("com.foo:leaf:jar:");

        // The orchestrator's per-solve reset between scope solves.
        shared.resetSolveScopedState();

        // Test scope: every path to target excludes leaf — it must be dropped, not inherited
        // from main's clean-path registration.
        Resolution test = new PubGrubResolver(shared, pomBuilder, KmpRedirects.NONE)
                .resolve(List.of(new Dependency("com.foo:excluder", VersionSelector.parse("=1.0"))));
        assertThat(test.modules()).containsKeys("com.foo:excluder:jar:", "com.foo:target:jar:");
        assertThat(test.modules()).doesNotContainKey("com.foo:leaf:jar:");
    }

    /**
     * {@code <distributionManagement><relocation>} moves a coordinate. The stub carries no
     * classes and no dependencies, so anything that stops there resolves to nothing. Maven and
     * Gradle both render the stub with a single edge to its target; so does jk.
     */
    @Test
    void a_relocation_resolves_the_target_and_its_tree(@TempDir Path tempDir) throws Exception {
        for (String a : List.of("old", "new", "leaf")) {
            serveMetadata("/com/foo/" + a + "/maven-metadata.xml", "com.foo", a, List.of("1.0"));
        }
        servePom("com.foo", "old", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>old</artifactId><version>1.0</version>
                  <distributionManagement>
                    <relocation>
                      <groupId>com.foo</groupId>
                      <artifactId>new</artifactId>
                      <version>${project.version}</version>
                      <message>renamed in 1.0</message>
                    </relocation>
                  </distributionManagement>
                </project>
                """);
        servePom("com.foo", "new", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>new</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>leaf</artifactId><version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        servePom("com.foo", "leaf", "1.0", emptyPom("com.foo", "leaf", "1.0"));

        Resolution result = new PubGrubResolver(repoGroup(tempDir))
                .resolve(List.of(new Dependency("com.foo:old", VersionSelector.parse("=1.0"))));

        assertThat(result.modules()).containsKeys("com.foo:old:jar:", "com.foo:new:jar:", "com.foo:leaf:jar:");
        assertThat(result.modules().get("com.foo:old:jar:").deps())
                .as("the stub's only edge is the redirect")
                .anyMatch(d -> d.startsWith("com.foo:new"));
    }

    /** A relocation with no {@code <version>} keeps the requesting version, as Maven does. */
    @Test
    void a_relocation_without_a_version_keeps_the_requested_one(@TempDir Path tempDir) throws Exception {
        serveMetadata("/com/foo/old/maven-metadata.xml", "com.foo", "old", List.of("2.5"));
        serveMetadata("/com/foo/new/maven-metadata.xml", "com.foo", "new", List.of("2.5", "9.9"));
        servePom("com.foo", "old", "2.5", """
                <project>
                  <groupId>com.foo</groupId><artifactId>old</artifactId><version>2.5</version>
                  <distributionManagement>
                    <relocation>
                      <artifactId>new</artifactId>
                    </relocation>
                  </distributionManagement>
                </project>
                """);
        servePom("com.foo", "new", "2.5", emptyPom("com.foo", "new", "2.5"));
        servePom("com.foo", "new", "9.9", emptyPom("com.foo", "new", "9.9"));

        Resolution result = new PubGrubResolver(repoGroup(tempDir))
                .resolve(List.of(new Dependency("com.foo:old", VersionSelector.parse("=2.5"))));

        assertThat(result.modules().get("com.foo:new:jar:").version()).isEqualTo("2.5");
    }

    /** A → B → C: each hop is a normal expansion, so the chain terminates at real content. */
    @Test
    void a_relocation_chain_follows_to_the_end(@TempDir Path tempDir) throws Exception {
        for (String a : List.of("a", "b", "c")) {
            serveMetadata("/com/foo/" + a + "/maven-metadata.xml", "com.foo", a, List.of("1.0"));
        }
        servePom("com.foo", "a", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>a</artifactId><version>1.0</version>
                  <distributionManagement>
                    <relocation><artifactId>b</artifactId></relocation>
                  </distributionManagement>
                </project>
                """);
        servePom("com.foo", "b", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>b</artifactId><version>1.0</version>
                  <distributionManagement>
                    <relocation><artifactId>c</artifactId></relocation>
                  </distributionManagement>
                </project>
                """);
        servePom("com.foo", "c", "1.0", emptyPom("com.foo", "c", "1.0"));

        Resolution result = new PubGrubResolver(repoGroup(tempDir))
                .resolve(List.of(new Dependency("com.foo:a", VersionSelector.parse("=1.0"))));

        assertThat(result.modules()).containsKeys("com.foo:a:jar:", "com.foo:b:jar:", "com.foo:c:jar:");
    }

    /** A relocation cycle must terminate — an unsatisfiable graph, never a hang. */
    @org.junit.jupiter.api.Timeout(60)
    @Test
    void a_relocation_cycle_terminates(@TempDir Path tempDir) throws Exception {
        for (String a : List.of("x", "y")) {
            serveMetadata("/com/foo/" + a + "/maven-metadata.xml", "com.foo", a, List.of("1.0"));
        }
        servePom("com.foo", "x", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>x</artifactId><version>1.0</version>
                  <distributionManagement>
                    <relocation><artifactId>y</artifactId></relocation>
                  </distributionManagement>
                </project>
                """);
        servePom("com.foo", "y", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>y</artifactId><version>1.0</version>
                  <distributionManagement>
                    <relocation><artifactId>x</artifactId></relocation>
                  </distributionManagement>
                </project>
                """);

        // Mutually-referencing packages are an ordinary dependency cycle to the solver.
        Resolution result = new PubGrubResolver(repoGroup(tempDir))
                .resolve(List.of(new Dependency("com.foo:x", VersionSelector.parse("=1.0"))));
        assertThat(result.modules()).containsKeys("com.foo:x:jar:", "com.foo:y:jar:");
    }

    @Test
    void isExcluded_wildcards() {
        assertThat(MavenPackageSource.isExcluded("com.foo:leaf", Set.of("com.foo:leaf")))
                .isTrue();
        assertThat(MavenPackageSource.isExcluded("com.foo:leaf", Set.of("com.foo:*")))
                .isTrue();
        assertThat(MavenPackageSource.isExcluded("com.foo:leaf", Set.of("*:leaf")))
                .isTrue();
        assertThat(MavenPackageSource.isExcluded("com.foo:leaf", Set.of("*:*"))).isTrue();
        assertThat(MavenPackageSource.isExcluded("com.foo:leaf", Set.of("com.bar:leaf")))
                .isFalse();
    }

    @Test
    void pom_range_constraint_limits_candidates(@TempDir Path tempDir) throws Exception {
        // middle depends on leaf with Maven range [1.0,2.0) — must not pick 2.0.
        serveMetadata("/com/foo/middle/maven-metadata.xml", "com.foo", "middle", List.of("1.0"));
        serveMetadata("/com/foo/leaf/maven-metadata.xml", "com.foo", "leaf", List.of("1.0", "1.5", "2.0"));
        servePom("com.foo", "middle", "1.0", """
                <project>
                  <groupId>com.foo</groupId><artifactId>middle</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>leaf</artifactId>
                      <version>[1.0,2.0)</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        servePom("com.foo", "leaf", "1.0", emptyPom("com.foo", "leaf", "1.0"));
        servePom("com.foo", "leaf", "1.5", emptyPom("com.foo", "leaf", "1.5"));
        servePom("com.foo", "leaf", "2.0", emptyPom("com.foo", "leaf", "2.0"));

        Resolution result = new PubGrubResolver(repoGroup(tempDir))
                .resolve(List.of(new Dependency("com.foo:middle", VersionSelector.parse("=1.0"))));

        assertThat(result.modules().get("com.foo:leaf:jar:").version()).isEqualTo("1.5");
    }

    private RepoGroup repoGroup(Path tempDir) {
        Cas cas = new Cas(tempDir.resolve("cache"));
        return RepoGroup.of(new MavenRepo("local", base, new Http(), cas));
    }

    private void servePath(String path, String body) {
        served.put(path, body.getBytes(StandardCharsets.UTF_8));
    }

    private void servePom(String group, String artifact, String version, String body) {
        String path = "/"
                + group.replace('.', '/')
                + "/"
                + artifact
                + "/"
                + version
                + "/"
                + artifact
                + "-"
                + version
                + ".pom";
        servePath(path, body);
    }

    private void serveMetadata(String path, String group, String artifact, List<String> versions) {
        StringBuilder body = new StringBuilder();
        body.append("<metadata><groupId>")
                .append(group)
                .append("</groupId><artifactId>")
                .append(artifact)
                .append("</artifactId><versioning><versions>");
        for (String v : versions) body.append("<version>").append(v).append("</version>");
        body.append("</versions></versioning></metadata>");
        servePath(path, body.toString());
    }

    private static String emptyPom(String group, String artifact, String version) {
        return """
                <project>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(group, artifact, version);
    }
}
