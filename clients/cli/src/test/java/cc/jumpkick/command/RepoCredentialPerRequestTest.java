// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.MockMavenServer;
import cc.jumpkick.testing.SysProps;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A {@code JK_REPO_<ID>_TOKEN} exported in the terminal that runs {@code jk} reaches the resolve
 * that terminal asked for — even though the resolve runs in a resident engine started from another
 * shell. The engine's own environment is that first shell's, so read there the token was whatever
 * it had, and the refusal warning told the user to set a variable that could not arrive.
 */
@Tag("integration")
@SysProps.TempRoots("jk.m2.local")
class RepoCredentialPerRequestTest {

    /** The client reads {@code JK_REPO_*} through the {@code jk.env.*} seam, which these override. */
    private static final String TOKEN = "jk.env.JK_REPO_PRIVATE_TOKEN";

    private static final String HOST = "jk.env.JK_REPO_PRIVATE_HOST";

    /** The private repository lives under its own prefix, so a path names exactly one remote. */
    private static final String PRIVATE = "/private";

    /**
     * The engine's artifact store lives in its home, not in {@code --cache-dir}, and keeps every
     * POM it has seen across runs — a name no earlier run can have stored makes both resolves reach
     * the wire, which is where the header is.
     */
    private final String artifact = "lib-" + Long.toUnsignedString(System.nanoTime(), 36);

    @RegisterExtension
    final MockMavenServer maven = new MockMavenServer();

    @AfterEach
    void clearSeam() {
        System.clearProperty(TOKEN);
        System.clearProperty(HOST);
    }

    @Test
    void two_resolves_with_different_repo_tokens_against_one_engine_each_send_their_own(@TempDir Path tempDir)
            throws Exception {
        DefaultTestDepsFixture.seed(maven.served());
        servePrivate("1.0");
        servePrivate("1.1");
        Path cache = tempDir.resolve("cache");
        // The binding that lets a name-keyed credential travel to this origin — also per request.
        System.setProperty(HOST, maven.base().getHost() + ":" + maven.base().getPort());

        manifest(tempDir, "1.0");
        System.setProperty(TOKEN, "alpha");
        assertThat(run("lock", "-C", tempDir.toString(), "--cache-dir", cache.toString()))
                .isZero();
        assertThat(authorizationSentFor(pom("1.0"))).isEqualTo("Bearer alpha");

        // The same resident engine, a second shell: its own token, not the one the daemon saw first.
        manifest(tempDir, "1.1");
        System.setProperty(TOKEN, "beta");
        assertThat(run("lock", "-C", tempDir.toString(), "--cache-dir", cache.toString()))
                .isZero();
        assertThat(authorizationSentFor(pom("1.1"))).isEqualTo("Bearer beta");
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
