// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.EffectivePomBuilder;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end tests for {@link PubGrubResolver} with a platform BOM map. Other PubGrub semantics are
 * covered under {@code pubgrub/}. With a non-empty platform map, bare POM edges are exact
 * (enforced); GAs listed in the map use the BOM pin over a different bare string on the edge.
 */
class PubGrubResolverTest {

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
    void bom_pin_overrides_lower_bare_on_transitive_edge(@TempDir Path tempDir) throws Exception {
        // root → middle@1.0 → leaf@1.0 bare; BOM pins leaf = 1.5 → enforced 1.5 (not soft lift).
        serveMetadata("/com/foo/middle/maven-metadata.xml", "com.foo", "middle", List.of("1.0"));
        serveMetadata("/com/foo/leaf/maven-metadata.xml", "com.foo", "leaf", List.of("1.0", "1.5", "2.0"));
        servePom("com.foo", "middle", "1.0", """
                <project>
                  <groupId>com.foo</groupId>
                  <artifactId>middle</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>leaf</artifactId><version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        servePom("com.foo", "leaf", "1.0", emptyPom("com.foo", "leaf", "1.0"));
        servePom("com.foo", "leaf", "1.5", emptyPom("com.foo", "leaf", "1.5"));
        servePom("com.foo", "leaf", "2.0", emptyPom("com.foo", "leaf", "2.0"));

        RepoGroup repos = repoGroup(tempDir);
        Map<String, String> bom = Map.of("com.foo:leaf", "1.5");
        PubGrubResolver resolver = new PubGrubResolver(repos, bom);

        Resolution result = resolver.resolve(List.of(new Dependency("com.foo:middle", VersionSelector.parse("=1.0"))));

        assertThat(result.modules()).containsKey("com.foo:leaf:jar:");
        assertThat(result.modules().get("com.foo:leaf:jar:").version()).isEqualTo("1.5");
    }

    @Test
    void platform_active_unmapped_bare_transitive_mediates_by_default(@TempDir Path tempDir) throws Exception {
        // JK-1241: platform map has an unrelated pin (project still "has a BOM"). middle →
        // leaf@1.0 bare; metadata offers 2.0. Default mediates highest-wins → leaf=2.0
        // (Maven/Gradle parity); [resolve] unmapped = "strict" restores the exact fill.
        serveMetadata("/com/foo/middle/maven-metadata.xml", "com.foo", "middle", List.of("1.0"));
        serveMetadata("/com/foo/leaf/maven-metadata.xml", "com.foo", "leaf", List.of("1.0", "1.5", "2.0"));
        servePom("com.foo", "middle", "1.0", """
                <project>
                  <groupId>com.foo</groupId>
                  <artifactId>middle</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>leaf</artifactId><version>1.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        servePom("com.foo", "leaf", "1.0", emptyPom("com.foo", "leaf", "1.0"));
        servePom("com.foo", "leaf", "1.5", emptyPom("com.foo", "leaf", "1.5"));
        servePom("com.foo", "leaf", "2.0", emptyPom("com.foo", "leaf", "2.0"));

        RepoGroup repos = repoGroup(tempDir);
        Map<String, String> bom = Map.of("com.foo:unrelated", "0.1");

        Resolution mediated = new PubGrubResolver(repos, bom)
                .resolve(List.of(new Dependency("com.foo:middle", VersionSelector.parse("=1.0"))));
        assertThat(mediated.modules().get("com.foo:leaf:jar:").version()).isEqualTo("2.0");

        Resolution strict = new PubGrubResolver(
                        repos,
                        bom,
                        Map.of(),
                        KmpRedirects.NONE,
                        cc.jumpkick.model.PlatformPolicy.ENFORCED,
                        cc.jumpkick.model.UnmappedPolicy.STRICT)
                .resolve(List.of(new Dependency("com.foo:middle", VersionSelector.parse("=1.0"))));
        assertThat(strict.modules().get("com.foo:leaf:jar:").version()).isEqualTo("1.0");
    }

    @Test
    void bom_managed_pin_is_enforced_on_transitive_edges(@TempDir Path tempDir) throws Exception {
        // When a GA is in the platform map, the pin is enforced on POM edges (not lifted by a
        // higher bare version on middle→leaf).
        serveMetadata("/com/foo/middle/maven-metadata.xml", "com.foo", "middle", List.of("1.0"));
        serveMetadata("/com/foo/leaf/maven-metadata.xml", "com.foo", "leaf", List.of("1.0", "1.5", "2.0"));
        servePom("com.foo", "middle", "1.0", """
                <project>
                  <groupId>com.foo</groupId>
                  <artifactId>middle</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>leaf</artifactId><version>1.5</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        servePom("com.foo", "leaf", "1.0", emptyPom("com.foo", "leaf", "1.0"));
        servePom("com.foo", "leaf", "1.5", emptyPom("com.foo", "leaf", "1.5"));
        servePom("com.foo", "leaf", "2.0", emptyPom("com.foo", "leaf", "2.0"));

        RepoGroup repos = repoGroup(tempDir);
        Map<String, String> bom = Map.of("com.foo:leaf", "1.0");
        PubGrubResolver resolver = new PubGrubResolver(repos, bom);

        Resolution result = resolver.resolve(List.of(new Dependency("com.foo:middle", VersionSelector.parse("=1.0"))));

        assertThat(result.modules().get("com.foo:leaf:jar:").version()).isEqualTo("1.0");
    }

    @Test
    void exact_root_dep_hard_pins_even_with_bom_prefer(@TempDir Path tempDir) throws Exception {
        // BOM soft-prefers 1.5; root exact-pins 1.0. LockOrchestrator removes the BOM prefer for
        // exact roots, but even if the prefer remains, Exact forces 1.0. Here we pass bom to the
        // resolver directly with an exact root on the same coord — Exact beats prefer.
        serveMetadata("/com/foo/leaf/maven-metadata.xml", "com.foo", "leaf", List.of("1.0", "1.5", "2.0"));
        servePom("com.foo", "leaf", "1.0", emptyPom("com.foo", "leaf", "1.0"));
        servePom("com.foo", "leaf", "1.5", emptyPom("com.foo", "leaf", "1.5"));
        servePom("com.foo", "leaf", "2.0", emptyPom("com.foo", "leaf", "2.0"));

        RepoGroup repos = repoGroup(tempDir);
        Map<String, String> bom = Map.of("com.foo:leaf", "1.5");
        PubGrubResolver resolver = new PubGrubResolver(repos, bom);

        Resolution result = resolver.resolve(List.of(new Dependency("com.foo:leaf", VersionSelector.parse("=1.0"))));

        assertThat(result.modules().get("com.foo:leaf:jar:").version()).isEqualTo("1.0");
    }

    @Test
    void coord_not_in_any_bom_uses_normal_resolution(@TempDir Path tempDir) throws Exception {
        // A direct dep with a caret selector should pick the highest
        // satisfying version. With no BOM constraint on `com.foo:other`,
        // resolution proceeds as usual.
        serveMetadata("/com/foo/other/maven-metadata.xml", "com.foo", "other", List.of("1.0", "1.5", "2.0"));
        servePom("com.foo", "other", "1.5", emptyPom("com.foo", "other", "1.5"));

        RepoGroup repos = repoGroup(tempDir);
        // BOM constrains a *different* coord — should not interfere.
        Map<String, String> bom = Map.of("com.foo:unrelated", "0.1");
        PubGrubResolver resolver = new PubGrubResolver(repos, bom);

        Resolution result =
                resolver.resolve(List.of(new Dependency("com.foo:other", VersionSelector.parseFloating("1.5"))));
        assertThat(result.modules().get("com.foo:other:jar:").version()).isEqualTo("1.5");
    }

    private RepoGroup repoGroup(Path tempDir) {
        Cas cas = new Cas(tempDir.resolve("cache"));
        return RepoGroup.of(new MavenRepo("local", base, new Http(), cas));
    }

    @SuppressWarnings("unused")
    private MavenPackageSource newSource(Path tempDir) {
        Cas cas = new Cas(tempDir.resolve("cache"));
        MavenRepo repo = new MavenRepo("local", base, new Http(), cas);
        return new MavenPackageSource(repo, new EffectivePomBuilder(repo));
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
                .append("</groupId>")
                .append("<artifactId>")
                .append(artifact)
                .append("</artifactId>")
                .append("<versioning><versions>");
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
