// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The cancels a client asks for, by jid, by dir and by disconnecting, against a live engine on a
 * real socket ({@link CancellationHarness}), and a finished job the end of its request does not
 * relabel cancelled.
 */
@Tag("integration")
class CancellationPrecedenceTest extends CancellationHarness {

    @Test
    void cancel_by_jid_over_a_second_connection_acks_at_once_and_the_job_finishes_cancelled_by_the_user()
            throws Exception {
        Iterator<String> sse = startEngine(JkEngineConfig.DEFAULTS);
        try (Client building = new Client(EnginePaths.activeSocket(paths()))) {
            long jid = startLock(building);
            assertThat(held.await(30, TimeUnit.SECONDS)).isTrue();

            // Connect only once the job is parked: the engine closes a connection that stays silent
            // past its first-line grace, and the wait above can outlast it on a loaded host.
            String ack;
            try (Client canceller = new Client(EnginePaths.activeSocket(paths()))) {
                ack = canceller.send(ProtoLifecycle.cancelRequest(jid));
            }
            // The ack arrives while the download is still parked: it does not wait for the job to settle.
            assertThat(release.getCount()).isEqualTo(1);
            assertThat(EngineProtocol.typeOf(ack)).isEqualTo(EngineProtocol.CANCEL_ACK);
            assertThat(Jsonl.longValue(ack, "jid", -1)).isEqualTo(jid);
            assertThat(Jsonl.bool(ack, "cancelled", false)).isTrue();
            assertThat(Jsonl.has(ack, "note")).isFalse();
            unpark();

            String terminal = readUntil(building, EngineProtocol.BUILDPLAN_FINISH);
            assertThat(Jsonl.bool(terminal, "cancelled", false)).isTrue();
            assertThat(Jsonl.str(terminal, "dir")).isEqualTo(project.toString());
            assertThat(EngineProtocol.typeOf(readUntil(building, EngineProtocol.JOB_FINISH)))
                    .as("job-finish still reaches a client blocking on it")
                    .isEqualTo(EngineProtocol.JOB_FINISH);
        }
        String finish = awaitSseData(sse, "request-finish");
        assertThat(Jsonl.bool(finish, "cancelled", false)).isTrue();
        assertThat(Jsonl.str(finish, "cancelReason")).isEqualTo(BY_USER);
    }

    @Test
    void cancel_by_unknown_jid_is_a_soft_miss_with_the_note() throws Exception {
        startEngine(JkEngineConfig.DEFAULTS);
        try (Client c = new Client(EnginePaths.activeSocket(paths()))) {
            String ack = c.send(ProtoLifecycle.cancelRequest(4242));
            assertThat(Jsonl.longValue(ack, "jid", -1)).isEqualTo(4242);
            assertThat(Jsonl.bool(ack, "cancelled", true)).isFalse();
            assertThat(Jsonl.str(ack, "note")).isEqualTo("unknown or already finished jid");
        }
    }

    @Test
    void cancel_by_dir_cancels_every_live_job_under_it_and_then_finds_none() throws Exception {
        startEngine(JkEngineConfig.DEFAULTS);
        try (Client building = new Client(EnginePaths.activeSocket(paths()))) {
            startLock(building);
            assertThat(held.await(30, TimeUnit.SECONDS)).isTrue();

            // Connected after the park, for the same first-line grace as above.
            try (Client canceller = new Client(EnginePaths.activeSocket(paths()))) {
                String ack = canceller.send(ProtoLifecycle.cancelRequestForDir(project.toString()));
                assertThat(Jsonl.longValue(ack, "jid", -1)).isZero();
                assertThat(Jsonl.bool(ack, "cancelled", false)).isTrue();
                assertThat(Jsonl.str(ack, "note")).isEqualTo("cancelled 1 job(s)");
                unpark();

                readUntil(building, EngineProtocol.JOB_FINISH);
                String none = canceller.send(ProtoLifecycle.cancelRequestForDir(project.toString()));
                assertThat(Jsonl.longValue(none, "jid", -1)).isZero();
                assertThat(Jsonl.bool(none, "cancelled", true)).isFalse();
                assertThat(Jsonl.str(none, "note")).isEqualTo("no running jobs for dir");
            }
        }
    }

    @Test
    void cancel_with_neither_jid_nor_dir_is_refused() throws Exception {
        startEngine(JkEngineConfig.DEFAULTS);
        try (Client c = new Client(EnginePaths.activeSocket(paths()))) {
            String ack = c.send(ProtoLifecycle.cancelRequestForDir(""));
            assertThat(Jsonl.longValue(ack, "jid", 0)).isEqualTo(-1);
            assertThat(Jsonl.bool(ack, "cancelled", true)).isFalse();
            assertThat(Jsonl.str(ack, "note")).isEqualTo("cancel-request requires jid or dir");
        }
    }

    @Test
    void a_client_that_disconnects_mid_job_is_cancelled_by_disconnect_not_by_the_user() throws Exception {
        Iterator<String> sse = startEngine(JkEngineConfig.DEFAULTS);
        try (Client building = new Client(EnginePaths.activeSocket(paths()))) {
            startLock(building);
            assertThat(held.await(30, TimeUnit.SECONDS)).isTrue();
        }
        String finish = awaitSseData(sse, "request-finish");
        assertThat(Jsonl.bool(finish, "cancelled", false)).isTrue();
        assertThat(Jsonl.str(finish, "cancelReason")).isEqualTo(BY_DISCONNECT);
    }

    @Test
    void a_job_that_finished_is_not_relabelled_cancelled_by_the_end_of_request_eof() throws Exception {
        holdLeaf = false;
        Iterator<String> sse = startEngine(JkEngineConfig.DEFAULTS);
        try (Client building = new Client(EnginePaths.activeSocket(paths()))) {
            startLock(building);
            String terminal = readUntil(building, EngineProtocol.BUILDPLAN_FINISH);
            assertThat(Jsonl.bool(terminal, "cancelled", false)).isFalse();
            readUntil(building, EngineProtocol.JOB_FINISH);
        }
        String finish = awaitSseData(sse, "request-finish");
        assertThat(Jsonl.bool(finish, "success", false)).isTrue();
        assertThat(Jsonl.bool(finish, "cancelled", true)).isFalse();
        assertThat(Jsonl.has(finish, "cancelReason")).isFalse();
    }
}
