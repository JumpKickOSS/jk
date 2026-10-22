// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.ResolvedSecrets;
import cc.jumpkick.config.SecretRedactor;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.wire.runtime.TailArgs;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one credential the engine does not resolve for itself. {@code RepoCredentialResolver} files
 * every value it resolves, so every repository credential is masked in engine output. A publish is
 * the exception: the CLI resolves its credential and ships it on the request line, so decoding it
 * there is the last place a live credential could enter the engine unannounced, and a publish is
 * exactly the goal whose worker quotes a {@code 401} body back at the user.
 */
class PublishVerbCredentialRedactionTest {

    /** Shaped like the bearer token a CI job would export; it is not one. */
    private static final String TOKEN = "publish-socket-only-bearer-token";

    private static final String PASSWORD = "publish-socket-only-basic-password";

    /** As the publisher worker reports a rejected upload: the header it sent, verbatim. */
    private static String worker401(String secret) {
        return "worker failed: PUT https://nexus.example.com/repo/ -> 401 (sent Authorization: Bearer " + secret + ")";
    }

    @BeforeEach
    @AfterEach
    void forgetResolvedCredentials() {
        ResolvedSecrets.clear();
    }

    @Test
    void a_socket_shipped_bearer_token_is_masked_in_the_requests_workspace(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("project"));
        assertThat(mask(dir, worker401(TOKEN)))
                .as("precondition: the token is not masked before the request is decoded")
                .contains(TOKEN);

        WorkspaceBuildVerb.fileShippedCredential(dir, publish(new RepoCredential.Bearer(TOKEN)));

        assertThat(mask(dir, worker401(TOKEN))).doesNotContain(TOKEN).contains(SecretRedactor.MASK);
    }

    /** Basic's password is the other half a publish can ship; the username stays legible. */
    @Test
    void a_socket_shipped_basic_password_is_masked_and_the_username_is_not(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("project"));
        WorkspaceBuildVerb.fileShippedCredential(dir, publish(new RepoCredential.Basic("alice", PASSWORD)));

        assertThat(mask(dir, "worker failed: 401 for alice:" + PASSWORD))
                .doesNotContain(PASSWORD)
                .contains("alice")
                .contains(SecretRedactor.MASK);
    }

    /**
     * The engine is resident and publishes for many projects. A credential filed by one request
     * must not be masked out of another project's output — masking is per workspace.
     */
    @Test
    void the_filed_credential_does_not_escape_the_requests_workspace(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("project"));
        Path other = Files.createDirectories(tmp.resolve("other"));
        WorkspaceBuildVerb.fileShippedCredential(dir, publish(new RepoCredential.Bearer(TOKEN)));

        assertThat(mask(other, worker401(TOKEN))).isEqualTo(worker401(TOKEN));
    }

    private static String mask(Path dir, String error) {
        return new InertVerbHost()
                .redactErrors(dir.toString(), List.of(error))
                .getFirst()
                .text();
    }

    private static TailArgs.Publish publish(RepoCredential credential) {
        return new TailArgs.Publish(
                URI.create("https://nexus.example.com/repo/"),
                null,
                null,
                null,
                false,
                true,
                null,
                null,
                false,
                false,
                false,
                credential,
                false,
                null);
    }
}
