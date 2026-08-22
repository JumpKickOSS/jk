// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.engine.plugin.PluginJarNotFoundException;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.repo.RepoArtifactStore;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Worker-jar location: system-property override → repos/local/ → repos/central/ → clear error.
 *
 * <p>Tests use a temp cache root so the developer's real ~/.cache/jk is never touched. The
 * coordinate-based lookup resolves {@code cc.jumpkick:jk-kotlin-compiler:<version>} from the named
 * repo stores; the worker jar content is irrelevant to location, only its presence at the expected
 * m2 path (with .sha256 sidecar) matters.
 */
class KotlinPluginSetupTest {

    private static final String VERSION = JkVersion.VERSION;
    private static final String M2_PATH =
            "cc/jumpkick/jk-kotlin-compiler/" + VERSION + "/jk-kotlin-compiler-" + VERSION + ".jar";

    @Test
    void locates_worker_in_repos_local(@TempDir Path dir) throws IOException {
        Cas cas = new Cas(dir);
        // Populate repos/local/ with a stand-in worker jar + sidecar.
        RepoArtifactStore local = new RepoArtifactStore(dir, "local");
        Path artifact = dir.resolve("repos/local").resolve(M2_PATH);
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, "stand-in worker jar");
        local.writeMemo(M2_PATH, artifact, cc.jumpkick.util.Hashing.sha256Hex(artifact));

        withoutOverride(() -> assertThat(PluginJar.KOTLIN_COMPILER.locate(cas)).isEqualTo(artifact));
    }

    @Test
    void throws_with_clear_hint_when_absent(@TempDir Path dir) {
        Cas cas = new Cas(dir);
        // Empty cas + no -D jar property + dead official URL ⇒ no soft network success.
        withoutOverride(() -> assertThatThrownBy(() -> PluginJar.KOTLIN_COMPILER.locate(cas))
                .isInstanceOf(PluginJarNotFoundException.class));
    }

    /**
     * The engine test JVM sets the worker-jar override so the KSP/Room/Hilt gates can fork real
     * kotlinc (see kernel/engine/build.gradle.kts); these two tests exercise the repos lookup
     * BELOW the override, so it must be absent for their duration. Official-repo fetch is also
     * disabled (dead loopback URL) so an empty temp cache cannot soft-succeed over the network.
     */
    private static void withoutOverride(Runnable body) {
        String prev = System.getProperty(KotlinPluginSetup.WORKER_JAR_PROPERTY);
        String prevRepo = System.getProperty(PluginJar.OFFICIAL_REPO_URL_PROPERTY);
        System.clearProperty(KotlinPluginSetup.WORKER_JAR_PROPERTY);
        // A live local 404 — fetchOfficial returns null, locate throws NotFound. A dead
        // loopback URL has the same outcome but waits out Http's full retry backoff (~3.3s).
        HttpServer notFound;
        try {
            notFound = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        notFound.createContext("/", ex -> {
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        notFound.start();
        System.setProperty(
                PluginJar.OFFICIAL_REPO_URL_PROPERTY,
                "http://127.0.0.1:" + notFound.getAddress().getPort() + "/");
        try {
            body.run();
        } finally {
            notFound.stop(0);
            if (prev != null) System.setProperty(KotlinPluginSetup.WORKER_JAR_PROPERTY, prev);
            else System.clearProperty(KotlinPluginSetup.WORKER_JAR_PROPERTY);
            if (prevRepo != null) System.setProperty(PluginJar.OFFICIAL_REPO_URL_PROPERTY, prevRepo);
            else System.clearProperty(PluginJar.OFFICIAL_REPO_URL_PROPERTY);
        }
    }

    @Test
    void system_property_overrides_repos_lookup(@TempDir Path dir) throws IOException {
        Path jar = Files.writeString(dir.resolve("override.jar"), "x");
        Cas emptyCas = new Cas(dir.resolve("cas"));
        String prev = System.getProperty(KotlinPluginSetup.WORKER_JAR_PROPERTY);
        System.setProperty(KotlinPluginSetup.WORKER_JAR_PROPERTY, jar.toString());
        try {
            assertThat(PluginJar.KOTLIN_COMPILER.locate(emptyCas)).isEqualTo(jar);
        } finally {
            if (prev == null) System.clearProperty(KotlinPluginSetup.WORKER_JAR_PROPERTY);
            else System.setProperty(KotlinPluginSetup.WORKER_JAR_PROPERTY, prev);
        }
    }
}
