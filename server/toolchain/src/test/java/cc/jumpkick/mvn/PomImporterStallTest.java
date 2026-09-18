// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * An import's parent and BOM reads run under the resolve stall window: a repository that accepts
 * the request and never answers stops the import, which names the coordinate it was reading and
 * the URL it waited on instead of sitting silent.
 */
class PomImporterStallTest {

    private HttpServer server;
    private URI base;
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @AfterEach
    void stop() {
        release.countDown();
        server.stop(0);
    }

    @Test
    @Timeout(30)
    void a_parent_read_that_never_answers_stops_the_import_naming_the_url(@TempDir Path tempDir) throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("project"));
        Path pom = project.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.acme.never</groupId>
                    <artifactId>never-parent</artifactId>
                    <version>1.0</version>
                    <relativePath/>
                  </parent>
                  <artifactId>stalled</artifactId>
                </project>
                """, StandardCharsets.UTF_8);
        PomImporter importer = TestImporters.over(tempDir, base).stallWindowMs(300);

        assertThatThrownBy(() -> importer.importFrom(pom))
                .isInstanceOf(IOException.class)
                .hasMessageStartingWith("Resolution budget exceeded: no POM read advanced for 0 s while reading the"
                        + " parent com.acme.never:never-parent:1.0")
                .hasMessageContaining("waiting on " + base + "com/acme/never/never-parent/1.0/never-parent-1.0.pom (")
                .hasMessageContaining("JK_RESOLVE_TIMEOUT_MS");
        assertThat(Thread.currentThread().isInterrupted())
                .as("the watch's interrupt does not outlive the import")
                .isFalse();
    }
}
