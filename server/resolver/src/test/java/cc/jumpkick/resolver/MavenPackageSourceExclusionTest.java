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
        servePom(
                "com.foo",
                "parent",
                "1.0",
                """
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
        servePom(
                "com.foo",
                "child",
                "1.0",
                """
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
        Resolution result =
                resolver.resolve(List.of(new Dependency("com.foo:parent", VersionSelector.parse("=1.0"))));

        assertThat(result.modules()).containsKeys("com.foo:parent", "com.foo:child");
        assertThat(result.modules()).doesNotContainKey("com.foo:leaf");
    }

    @Test
    void other_parent_can_still_pull_excluded_module(@TempDir Path tempDir) throws Exception {
        // root → parent (excludes leaf via child) + other → leaf directly.
        serveMetadata("/com/foo/parent/maven-metadata.xml", "com.foo", "parent", List.of("1.0"));
        serveMetadata("/com/foo/child/maven-metadata.xml", "com.foo", "child", List.of("1.0"));
        serveMetadata("/com/foo/other/maven-metadata.xml", "com.foo", "other", List.of("1.0"));
        serveMetadata("/com/foo/leaf/maven-metadata.xml", "com.foo", "leaf", List.of("1.0"));
        servePom(
                "com.foo",
                "parent",
                "1.0",
                """
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
        servePom(
                "com.foo",
                "child",
                "1.0",
                """
                <project>
                  <groupId>com.foo</groupId><artifactId>child</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>leaf</artifactId><version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        servePom(
                "com.foo",
                "other",
                "1.0",
                """
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

        assertThat(result.modules()).containsKey("com.foo:leaf");
    }

    @Test
    void isExcluded_wildcards() {
        assertThat(MavenPackageSource.isExcluded("com.foo:leaf", Set.of("com.foo:leaf"))).isTrue();
        assertThat(MavenPackageSource.isExcluded("com.foo:leaf", Set.of("com.foo:*"))).isTrue();
        assertThat(MavenPackageSource.isExcluded("com.foo:leaf", Set.of("*:leaf"))).isTrue();
        assertThat(MavenPackageSource.isExcluded("com.foo:leaf", Set.of("*:*"))).isTrue();
        assertThat(MavenPackageSource.isExcluded("com.foo:leaf", Set.of("com.bar:leaf"))).isFalse();
    }

    @Test
    void pom_range_constraint_limits_candidates(@TempDir Path tempDir) throws Exception {
        // middle depends on leaf with Maven range [1.0,2.0) — must not pick 2.0.
        serveMetadata("/com/foo/middle/maven-metadata.xml", "com.foo", "middle", List.of("1.0"));
        serveMetadata("/com/foo/leaf/maven-metadata.xml", "com.foo", "leaf", List.of("1.0", "1.5", "2.0"));
        servePom(
                "com.foo",
                "middle",
                "1.0",
                """
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

        assertThat(result.modules().get("com.foo:leaf").version()).isEqualTo("1.5");
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
                """
                .formatted(group, artifact, version);
    }
}
