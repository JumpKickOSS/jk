// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.MockMavenServer;
import cc.jumpkick.host.OwnerOnlyFiles;
import cc.jumpkick.testing.SysProps;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code JK_REPO_<ID>_TOKEN_FILE} authenticates a resolve, and a rotated file is read again by the
 * next one while the same engine stays resident: no {@code jk engine stop} between the two.
 */
@Tag("integration")
@SysProps.TempRoots("jk.m2.local")
class RepoCredentialFileTest {

    /** The client reads {@code JK_REPO_*} through the {@code jk.env.*} seam, which these override. */
    private static final String TOKEN_FILE = "jk.env.JK_REPO_PRIVATE_TOKEN_FILE";

    private static final String HOST = "jk.env.JK_REPO_PRIVATE_HOST";

    /** The private repository lives under its own prefix, so a path names exactly one remote. */
    private static final String PRIVATE = "/private";

    /** A name no earlier run can have stored, so every resolve reaches the wire, where the header is. */
    private final String artifact = "lib-" + Long.toUnsignedString(System.nanoTime(), 36);

    @RegisterExtension
    final MockMavenServer maven = new MockMavenServer();

    @Test
    void a_token_file_authenticates_and_a_rotated_one_is_read_by_the_next_lock(@TempDir Path tempDir) throws Exception {
        DefaultTestDepsFixture.seed(maven.served());
        servePrivate("1.0");
        servePrivate("1.1");
        Path cache = tempDir.resolve("cache");
        Path token = tempDir.resolve("private-token");
        Files.writeString(token, "first-token\n");
        OwnerOnlyFiles.setOwnerOnly(token, "rw-------");
        System.setProperty(HOST, maven.base().getHost() + ":" + maven.base().getPort());
        System.setProperty(TOKEN_FILE, token.toString());

        manifest(tempDir, "1.0");
        assertThat(run("lock", "-F", "-C", tempDir.toString(), "--cache-dir", cache.toString()))
                .isZero();
        assertThat(authorizationSentFor(pom("1.0"))).isEqualTo("Bearer first-token");

        Files.writeString(token, "rotated-token\n");
        Files.setLastModifiedTime(token, FileTime.from(Instant.now().plusSeconds(5)));
        manifest(tempDir, "1.1");
        assertThat(run("lock", "-F", "-C", tempDir.toString(), "--cache-dir", cache.toString()))
                .isZero();
        assertThat(authorizationSentFor(pom("1.1"))).isEqualTo("Bearer rotated-token");
    }

    private String authorizationSentFor(String path) {
        return maven.headersFor(path)
                .orElseThrow(() ->
                        new AssertionError("the resolve never fetched " + path + "; it fetched " + maven.requested()))
                .getOrDefault("Authorization", List.of("<anonymous>"))
                .get(0);
    }

    private String pom(String version) {
        return PRIVATE + MockMavenServer.mavenPath("com.foo", artifact, version, "pom");
    }

    private void servePrivate(String version) {
        maven.served()
                .put(
                        pom(version),
                        MockMavenServer.pom("com.foo", artifact, version).getBytes(StandardCharsets.UTF_8));
        maven.served()
                .put(
                        PRIVATE + MockMavenServer.mavenPath("com.foo", artifact, version, "jar"),
                        (artifact + "-" + version).getBytes(StandardCharsets.UTF_8));
        maven.served()
                .put(
                        PRIVATE + "/com/foo/" + artifact + "/maven-metadata.xml",
                        ("<metadata><groupId>com.foo</groupId><artifactId>" + artifact
                                        + "</artifactId><versioning><versions>"
                                        + "<version>1.0</version><version>1.1</version></versions></versioning></metadata>")
                                .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A manifest whose only private dependency is {@code com.foo:<artifact>:<version>} from {@code
     * private}, pinned exactly: a bare version floats to the newest the metadata lists, and both
     * phases' versions are listed.
     */
    private void manifest(Path dir, String version) throws IOException {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.example"
                name = "probe"
                version = "0.1.0"
                java = 25

                [repositories.central]
                url = "%s"

                [repositories.private]
                url = "%s"
                groups = ["com.foo"]

                [dependencies]
                lib = { group = "com.foo", name = "%s", version = "=%s" }
                """.formatted(
                        maven.baseUrl(), maven.baseUrl() + "private/", artifact, version));
    }
}
