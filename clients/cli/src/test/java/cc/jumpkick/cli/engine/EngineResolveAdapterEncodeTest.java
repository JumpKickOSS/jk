// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.PluginTuning;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.wire.protocol.LockRequest;
import cc.jumpkick.wire.protocol.ProtoSession;
import cc.jumpkick.wire.protocol.UpdateRequest;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The resolve requests ride the same session envelope the build requests do. The engine is a
 * daemon: a {@code JK_REPO_<ID>_TOKEN} exported in the shell that runs {@code jk lock} reaches
 * the resolve only if the request carries it, and a request encoded without the envelope reads
 * as an anonymous caller however the shell was set up.
 */
class EngineResolveAdapterEncodeTest {

    private static final Map<String, String> CALLER_ENV =
            Map.of("JK_REPO_PRIVATE_TOKEN", "alpha", "JK_REPO_PRIVATE_HOST", "repo.example:8443");

    private static Session caller() {
        return Session.defaults()
                .withVariant("ci", CALLER_ENV)
                .withJvm(new PluginTuning(null, null, null, List.of("-Djk.probe=first")));
    }

    private static EngineRequests.LockRequest lock() {
        return new EngineRequests.LockRequest(
                Path.of("/proj"), Path.of("/cache"), List.of(), false, false, null, false, false, false);
    }

    @Test
    void the_lock_request_carries_the_callers_repo_credentials_and_jvm_tuning() throws Exception {
        String line = SessionContext.where(caller(), () -> EngineResolveAdapter.lockRequestLine(lock()));

        assertThat(ProtoSession.clientEnvOf(line)).isEqualTo(CALLER_ENV);
        assertThat(ProtoSession.variantOf(line)).isEqualTo("ci");
        assertThat(ProtoSession.jvmTuning(line).extraArgs()).containsExactly("-Djk.probe=first");
        // The body the engine decodes is untouched by the envelope.
        assertThat(LockRequest.decode(line).dir()).isEqualTo(Path.of("/proj").toString());
    }

    @Test
    void the_update_request_carries_the_same_envelope() throws Exception {
        var req = new EngineRequests.UpdateRequest(
                Path.of("/proj"),
                Path.of("/cache"),
                List.of(),
                false,
                null,
                false,
                false,
                false,
                null,
                List.of(),
                false);
        String line = SessionContext.where(caller(), () -> EngineResolveAdapter.updateRequestLine(req, false, null));

        assertThat(ProtoSession.clientEnvOf(line)).isEqualTo(CALLER_ENV);
        assertThat(UpdateRequest.decode(line).dir()).isEqualTo(Path.of("/proj").toString());
    }

    @Test
    void a_caller_with_nothing_to_carry_sends_the_bare_body() throws Exception {
        String line = SessionContext.where(Session.defaults(), () -> EngineResolveAdapter.lockRequestLine(lock()));

        assertThat(ProtoSession.clientEnvOf(line)).isEmpty();
        assertThat(line).doesNotContain("\"env\"").doesNotContain("jvmArgs");
    }
}
