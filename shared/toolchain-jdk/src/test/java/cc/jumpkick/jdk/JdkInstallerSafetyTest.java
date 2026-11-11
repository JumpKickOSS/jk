// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.http.Http;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** What an install must never do to the tree around it: rename a live JDK, plant an escaping link. */
class JdkInstallerSafetyTest {

    private HttpServer server;
    private URI base;
    private final Map<String, byte[]> served = new HashMap<>();
    private Path isolatedState;

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
        isolatedState = Files.createTempDirectory("jk-state-");
        System.setProperty("jk.env.JK_STATE_DIR", isolatedState.toString());
        JdkInventory.resetShared();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        JdkInventory.resetShared();
        PathUtil.deleteRecursively(isolatedState);
    }

    @Test
    void an_automatic_install_leaves_an_install_holding_the_pointer_name_where_it_is(@TempDir Path tempDir)
            throws Exception {
        // temurin-21 is our own install sitting on the stable name; a build, a test JVM or an IDE
        // may be running from it, so the provisioning path must not rename it.
        Path jdksRoot = Files.createDirectories(tempDir.resolve("jdks"));
        Path held = heldInstall(jdksRoot);
        JdkCatalog.Entry entry = servedJdk();

        InstalledJdk installed = new JdkInstaller(new Http(), new JdkRegistry(jdksRoot)).install(entry);

        assertThat(installed.home()).isEqualTo(jdksRoot.resolve("temurin-21.0.5"));
        assertThat(Files.isDirectory(held, LinkOption.NOFOLLOW_LINKS)).isTrue();
        assertThat(held.resolve("release")).exists();
        assertThat(jdksRoot.resolve("temurin-21-1")).doesNotExist();
    }

    @Test
    void the_jdk_verb_moves_an_install_holding_the_pointer_name_aside(@TempDir Path tempDir) throws Exception {
        Path jdksRoot = Files.createDirectories(tempDir.resolve("jdks"));
        heldInstall(jdksRoot);
        JdkCatalog.Entry entry = servedJdk();

        JdkInstaller.forJdkVerb(new Http(), new JdkRegistry(jdksRoot)).install(entry);

        assertThat(jdksRoot.resolve("temurin-21-1").resolve("release")).exists();
        assertThat(jdksRoot.resolve("temurin-21").toRealPath())
                .isEqualTo(jdksRoot.resolve("temurin-21.0.5").toRealPath());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void a_link_replaced_after_another_was_judged_through_it_refuses_a_multi_root_install(@TempDir Path tempDir)
            throws Exception {
        // b -> d/l/../../.. is judged while d/l -> sub/deep and stays inside; d/l is then replaced
        // by d/l -> ., and b now climbs two levels above the tree. Two top-level entries, so the
        // staging directory itself becomes the install and nothing is lifted.
        byte[] archive = TestArchives.tarGzRaw(new String[][] {
            {"d/", null},
            {"d/sub/", null},
            {"d/sub/deep/", null},
            {"d/l", null, "sub/deep"},
            {"b", null, "d/l/../../.."},
            {"d/l", null, "."},
        });
        served.put("/jdk.tar.gz", archive);
        Path jdksRoot = tempDir.resolve("jdks");

        JdkInstaller installer = new JdkInstaller(new Http(), new JdkRegistry(jdksRoot));
        JdkCatalog.Entry entry =
                JdkInstallerTest.entry("linux", "x86_64", "", base.resolve("/jdk.tar.gz"), Hashing.sha256Hex(archive));
        assertThatThrownBy(() -> installer.install(entry))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("escapes the installed tree")
                .hasMessageContaining("b -> d/l/../../..");
        assertThat(jdksRoot.resolve("temurin-21.0.5")).doesNotExist();
    }

    private Path heldInstall(Path jdksRoot) throws IOException {
        Path held = jdksRoot.resolve("temurin-21");
        Files.createDirectories(held.resolve("bin"));
        Files.writeString(held.resolve("release"), "JAVA_VERSION=\"21\"\n");
        Files.writeString(JdkFingerprint.java(held), "#!/fake");
        Files.writeString(JdkFingerprint.javac(held), "#!/fake");
        JdkOwnership.mark(held);
        return held;
    }

    private JdkCatalog.Entry servedJdk() throws IOException {
        byte[] archive = TestArchives.tarGz(
                "jdk-21.0.5", Map.of("bin/java", "#!/fake/java", "bin/javac", "#!/fake/java", "release", "x\n"));
        served.put("/jdk.tar.gz", archive);
        return JdkInstallerTest.entry("linux", "x86_64", "", base.resolve("/jdk.tar.gz"), Hashing.sha256Hex(archive));
    }
}
