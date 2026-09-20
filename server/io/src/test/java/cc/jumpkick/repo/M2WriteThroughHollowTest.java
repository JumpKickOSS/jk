// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Maven local repository is shared by every sandbox and seeds later stores. A fixture's hollow
 * stand-in, downloaded under a real coordinate, stays in the repository's own store and is never
 * promoted there; a real body is.
 */
class M2WriteThroughHollowTest {

    private static final String HOLLOW = "org/junit/support/testng-engine/1.1.0/testng-engine-1.1.0.jar";
    private static final String REAL = "com/example/widget/1.0/widget-1.0.jar";

    @RegisterExtension
    final LoopbackHttp repo = new LoopbackHttp();

    @AfterEach
    void reset() {
        System.clearProperty("jk.m2.local");
        SessionContext.reset();
    }

    @Test
    void a_hollow_download_is_not_promoted_to_the_local_repository(@TempDir Path tmp) throws Exception {
        Path m2 = tmp.resolve("m2");
        System.setProperty("jk.m2.local", m2.toString());
        repo.served().put("/" + HOLLOW, MavenStub.EMPTY_JAR);
        repo.served().put("/" + REAL, "the genuine artifact bytes".getBytes(StandardCharsets.UTF_8));
        MavenRepo mavenRepo = new MavenRepo("test", repo.base(), new Http(), new Cas(tmp.resolve("store")));

        mavenRepo.fetchArtifact(Coordinate.of("org.junit.support", "testng-engine", "1.1.0"));
        mavenRepo.fetchArtifact(Coordinate.of("com.example", "widget", "1.0"));

        assertThat(m2.resolve(HOLLOW))
                .as("an empty archive is nobody's artifact")
                .doesNotExist();
        assertThat(m2.resolve(REAL)).as("a real body is promoted as before").isRegularFile();
        assertThat(Files.size(m2.resolve(REAL))).isEqualTo(26);
    }
}
