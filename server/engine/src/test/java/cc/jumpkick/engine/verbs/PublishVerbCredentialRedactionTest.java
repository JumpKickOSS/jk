// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Redacted;
import cc.jumpkick.config.ResolvedSecrets;
import cc.jumpkick.config.SecretRedactor;
import cc.jumpkick.config.Session;
import cc.jumpkick.engine.api.InFlightBuilds;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.testing.SysProps;
import cc.jumpkick.wire.protocol.PublishRequest;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one credential the engine does not resolve for itself.
 *
 * <p>{@code RepoCredentialResolver} files every value it resolves with {@link ResolvedSecrets}, so
 * every {@code JK_REPO_<ID>_TOKEN} / keychain / {@code settings.xml} credential is masked in engine
 * output. A publish is the exception: credentials are read client-side by design, so the value
 * arrives already resolved on the request line and the resolver never sees it. Decoding it back off
 * that line is therefore the last place a live credential can enter the engine unannounced — and a
 * publish is exactly the verb whose worker quotes a {@code 401} body back at the user.
 */
@ExtendWith(SysProps.class)
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

    /** A project the plan can be built for, without a network round trip for the worker jar. */
    private static Path project(Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("project"));
        Files.writeString(dir.resolve("jk.toml"), """
                group = "demo"
                name = "demo"
                version = "1.0.0"
                """);
        Path worker = Files.createFile(tmp.resolve("jk-publisher.jar"));
        // Restored by @ExtendWith(SysProps.class) on the class even if later setup throws.
        System.setProperty(PluginJar.PUBLISHER.jarProperty(), worker.toString());
        return dir;
    }

    private static String bearerRequest(Path dir, Path cache, String token) {
        return new PublishRequest(
                        dir.toString(),
                        cache.toString(),
                        "https://nexus.example.com/repo/",
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
                        "bearer",
                        null,
                        null,
                        token,
                        false,
                        false)
                .encode();
    }

    @Test
    void a_socket_shipped_bearer_token_is_masked_in_the_worker_error_path(@TempDir Path tmp) throws Exception {
        Path dir = project(tmp);
        CapturingHost host = new CapturingHost(worker401(TOKEN));

        // Nothing has told the redactor about this value yet: the shape the defect had.
        assertThat(host.redactErrors(dir.toString(), List.of(worker401(TOKEN)))
                        .getFirst()
                        .text()
                        .contains(TOKEN))
                .as("precondition: the token is not masked before the request is decoded")
                .isTrue();

        new PublishVerb(host).run(bearerRequest(dir, tmp.resolve("cache"), TOKEN), Session.CancelToken.NONE, null);

        assertThat(host.masked)
                .as("the verb never reached the worker error path")
                .isNotEmpty();
        for (String row : host.masked) {
            assertThat(row.contains(TOKEN))
                    .as("a socket-shipped bearer token reached the wire unmasked")
                    .isFalse();
            assertThat(row).contains(SecretRedactor.MASK);
        }
    }

    /** Basic's password is the other half a publish can ship; the username stays legible. */
    @Test
    void a_socket_shipped_basic_password_is_masked_and_the_username_is_not(@TempDir Path tmp) throws Exception {
        Path dir = project(tmp);
        CapturingHost host = new CapturingHost("worker failed: 401 for alice:" + PASSWORD);
        String request = new PublishRequest(
                        dir.toString(),
                        tmp.resolve("cache").toString(),
                        "https://nexus.example.com/repo/",
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
                        "basic",
                        "alice",
                        PASSWORD,
                        null,
                        false,
                        false)
                .encode();

        new PublishVerb(host).run(request, Session.CancelToken.NONE, null);

        assertThat(host.masked)
                .as("the verb never reached the worker error path")
                .isNotEmpty();
        assertThat(host.masked.getFirst().contains(PASSWORD))
                .as("a socket-shipped basic password reached the wire unmasked")
                .isFalse();
        assertThat(host.masked.getFirst()).contains("alice").contains(SecretRedactor.MASK);
    }

    /**
     * The engine is resident and publishes for many projects. A credential filed by one request
     * must not be masked out of another project's output — masking is per workspace, and a value
     * one project never held is a value the other project may legitimately print.
     */
    @Test
    void the_filed_credential_does_not_escape_the_requests_workspace(@TempDir Path tmp) throws Exception {
        Path dir = project(tmp);
        Path other = Files.createDirectories(tmp.resolve("other"));
        CapturingHost host = new CapturingHost(worker401(TOKEN));

        new PublishVerb(host).run(bearerRequest(dir, tmp.resolve("cache"), TOKEN), Session.CancelToken.NONE, null);

        assertThat(host.masked).isNotEmpty();
        assertThat(host.redactErrors(other.toString(), List.of(worker401(TOKEN)))
                        .getFirst()
                        .text())
                .isEqualTo(worker401(TOKEN));
    }

    /**
     * Stands in for the engine at the one seam this test is about: {@code redactErrors} is
     * inherited, not stubbed, so the masking under assertion is the engine's own.
     */
    private static final class CapturingHost implements VerbHost {

        private final String workerError;
        private final List<String> masked = new ArrayList<>();

        private CapturingHost(String workerError) {
            this.workerError = workerError;
        }

        /**
         * The plan is never run: the publisher forks a worker and this test has no repository.
         * What matters is that the verb got this far, because that is past the decode, and that the
         * error text a failing worker would produce is masked for the request's directory.
         */
        @Override
        public JobOutcome streamSinglePlan(
                BuildPlan plan,
                Session session,
                @Nullable BufferedWriter writer,
                Function<BuildPlanResult, String> finishEncoder) {
            for (Redacted row : redactErrors(session.workingDir().toString(), List.of(workerError))) {
                masked.add(row.text());
            }
            return JobOutcome.failed(Exit.FAILURE);
        }

        @Override
        public long eventRequestId() {
            return -1;
        }

        @Override
        public void putProgressRoot(long rid, String dir) {}

        @Override
        public WorkspaceBuildListener workspaceListener(@Nullable BufferedWriter w, String dir) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BuildPlanListener planListener(String dir, @Nullable BufferedWriter w, BuildPlan plan) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BuildPlanListener planListener(
                String dir, @Nullable BufferedWriter w, @Nullable Function<BuildPlanResult, String> enc) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void releaseExclusiveSlot() {}

        @Override
        public boolean effectiveCancelled(long rid, boolean tokenCancelled) {
            return false;
        }

        @Override
        public void accTests(long rid, @Nullable TestSummary tests) {}

        @Override
        public void finishProgress(long rid) {}

        @Override
        public void emitWorkspaceProgress(long rid, @Nullable BufferedWriter w, boolean force) {}

        @Override
        public void flushTimeline(long rid, @Nullable BufferedWriter w) {}

        @Override
        public void send(@Nullable BufferedWriter w, String line) {}

        @Override
        public void sendQuiet(@Nullable BufferedWriter w, String line) {}

        @Override
        public @Nullable String redactEnv(@Nullable String dir, @Nullable String text) {
            return text;
        }

        @Override
        public String requestFailedLine(@Nullable String dir, Throwable e) {
            return String.valueOf(e);
        }

        @Override
        public void publishRequestError(long rid, @Nullable String dir, String message) {}

        @Override
        public void maybeEnqueuePrune(Path cache) {}

        @Override
        public InFlightBuilds inFlightBuilds() {
            throw new UnsupportedOperationException();
        }
    }
}
