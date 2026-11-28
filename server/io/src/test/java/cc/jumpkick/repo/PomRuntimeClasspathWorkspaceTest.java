// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.model.Coordinate;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A workspace worker's launch classpath takes each library its workspace packages from the member's jar. */
class PomRuntimeClasspathWorkspaceTest {

    @BeforeEach
    @AfterEach
    void clearCaches() {
        EffectivePomBuilder.clearProcessCache();
        RepoGroup.clearProcessFetchCache();
        PomRuntimeClasspath.clearResolveCacheForTests();
    }

    @Test
    void a_member_jar_written_after_the_walk_replaces_the_store_copy(@TempDir Path tmp) throws Exception {
        Path host = tmp.resolve("host-store");
        Path sandbox = Files.createDirectories(tmp.resolve("sandbox-home"));
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group = "org.example"
                name = "ws"
                version = "1.0"

                [workspace]
                modules = ["lib", "gen"]
                """);
        for (String member : List.of("lib", "gen")) {
            Files.createDirectories(ws.resolve(member).resolve("target/classes"));
            Files.writeString(ws.resolve(member).resolve("jk.toml"), """
                    group = "org.example"
                    name = "%s"
                    version = "1.0"
                    """.formatted(member));
        }
        Path workerJar = ws.resolve("gen/target/gen-1.0.jar");
        Files.writeString(workerJar, "worker");
        Files.writeString(ws.resolve("gen/target/gen-1.0.pom"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.example</groupId><artifactId>gen</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency><groupId>org.example</groupId><artifactId>lib</artifactId><version>1.0</version></dependency>
                  </dependencies>
                </project>
                """);
        String libPom = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.example</groupId><artifactId>lib</artifactId><version>1.0</version>
                </project>
                """;
        Coordinate lib = Coordinate.of("org.example", "lib", "1.0");
        shelve(host, MavenLayout.artifactPath(lib), "shelf-lib");
        shelve(host, MavenLayout.pomPath(lib), libPom);

        String prevHost = System.getProperty(PomRuntimeClasspath.HOST_STORE_PROPERTY);
        String prevHome = System.getProperty("jk.env.JK_HOME");
        try {
            System.setProperty("jk.env.JK_HOME", sandbox.toString());
            System.setProperty(
                    PomRuntimeClasspath.HOST_STORE_PROPERTY,
                    host.toAbsolutePath().toString());
            Path memberJar =
                    ws.resolve("lib/target/lib-1.0.jar").toAbsolutePath().normalize();
            List<Path> before = PomRuntimeClasspath.resolve(workerJar);
            assertThat(before).hasSize(2).doesNotContain(memberJar);

            Files.writeString(memberJar, "member-lib");
            Files.writeString(ws.resolve("lib/target/lib-1.0.pom"), libPom);

            assertThat(PomRuntimeClasspath.resolve(workerJar))
                    .containsExactly(workerJar.toAbsolutePath().normalize(), memberJar);
        } finally {
            restoreProp("jk.env.JK_HOME", prevHome);
            restoreProp(PomRuntimeClasspath.HOST_STORE_PROPERTY, prevHost);
        }
    }

    private static void shelve(Path store, String rel, String content) throws Exception {
        Path f = store.resolve("repos").resolve(RepoArtifactResolver.JK_LOCAL).resolve(rel);
        Files.createDirectories(f.getParent());
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        Files.write(f, bytes);
        RepoArtifactStore.forStoreId(store, RepoArtifactResolver.JK_LOCAL).writeMemo(rel, f, Hashing.sha256Hex(bytes));
    }

    private static void restoreProp(String key, String prev) {
        if (prev == null) System.clearProperty(key);
        else System.setProperty(key, prev);
    }
}
