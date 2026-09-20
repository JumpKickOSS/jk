// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The bytes the lifecycle factories produced before they became records, spelled out. The wire is
 * frozen pre-1.0: a record that reorders a field, changes a default or drops an omitted field's
 * threshold is a different line to every client that decodes it, and the round-trip contract
 * cannot see that.
 */
class ProtoLifecycleFrozenBytesTest {
    @Test
    void handshake_lines() {
        assertThat(ProtoLifecycle.auth("t0k")).isEqualTo("{\"type\":\"auth\",\"token\":\"t0k\"}");
        assertThat(ProtoLifecycle.hello("0.13.0"))
                .isEqualTo("{\"type\":\"hello\",\"version\":\"0.13.0\",\"proto\":1,\"purpose\":\"connect\"}");
        assertThat(ProtoLifecycle.hello("0.13.0", "probe"))
                .isEqualTo("{\"type\":\"hello\",\"version\":\"0.13.0\",\"proto\":1,\"purpose\":\"probe\"}");
        assertThat(ProtoLifecycle.helloAck("0.13.0", 4242, 1700000000000L, false, null))
                .isEqualTo(
                        "{\"type\":\"hello-ack\",\"version\":\"0.13.0\",\"pid\":4242,\"startedAt\":1700000000000,\"proto\":1,\"draining\":false,\"buildId\":\"\"}");
        assertThat(ProtoLifecycle.helloAck("0.13.0", 1, 2, true, "abc123"))
                .isEqualTo(
                        "{\"type\":\"hello-ack\",\"version\":\"0.13.0\",\"pid\":1,\"startedAt\":2,\"proto\":1,\"draining\":true,\"buildId\":\"abc123\"}");
        assertThat(ProtoLifecycle.ping()).isEqualTo("{\"type\":\"ping\"}");
        assertThat(ProtoLifecycle.pong()).isEqualTo("{\"type\":\"pong\"}");
        assertThat(ProtoLifecycle.statusRequest()).isEqualTo("{\"type\":\"status\"}");
    }

    @Test
    void calibrate_lines_omit_the_cold_start_when_unknown() {
        assertThat(ProtoLifecycle.calibrateRequest(true, 0))
                .isEqualTo(
                        "{\"type\":\"calibrate-request\",\"force\":true,\"allowNetwork\":true,\"trigger\":\"calibrate\"}");
        assertThat(ProtoLifecycle.calibrateRequest(false, 850, false))
                .isEqualTo(
                        "{\"type\":\"calibrate-request\",\"force\":false,\"allowNetwork\":false,\"engineColdStartMs\":850,\"trigger\":\"calibrate\"}");
        assertThat(ProtoLifecycle.calibrateAck(
                        true, 150.0, 320, 1200, 45, 30, 400, 900, 60, 700, 850, true, true, false, "ok"))
                .isEqualTo(
                        "{\"type\":\"calibrate-ack\",\"ok\":true,\"msPerWeight\":150.0,\"jvmForkMs\":320,\"javacMs\":1200,\"diskIoMs\":45,\"hashCpuMs\":30,\"junitForkMs\":400,\"junitRunMs\":900,\"junitPlatformMs\":60,\"resolveMs\":700,\"engineColdStartMs\":850,\"measured\":true,\"junitPlatformUsed\":true,\"resolveUsed\":false,\"summary\":\"ok\"}");
        assertThat(ProtoLifecycle.calibrateAck(false, 1.5, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, false, false, "x"))
                .contains("\"msPerWeight\":1.5,");
    }

    @Test
    void shutdown_drain_and_error_lines() {
        assertThat(ProtoLifecycle.shutdown()).isEqualTo("{\"type\":\"shutdown\",\"force\":false}");
        assertThat(ProtoLifecycle.shutdown(true)).isEqualTo("{\"type\":\"shutdown\",\"force\":true}");
        assertThat(ProtoLifecycle.bye()).isEqualTo("{\"type\":\"bye\",\"plans\":0,\"draining\":false}");
        assertThat(ProtoLifecycle.bye(3, true)).isEqualTo("{\"type\":\"bye\",\"plans\":3,\"draining\":true}");
        assertThat(ProtoLifecycle.drainStatus(77, 2, "0.13.0"))
                .isEqualTo("{\"type\":\"drain-status\",\"pid\":77,\"plans\":2,\"version\":\"0.13.0\"}");
        assertThat(ProtoLifecycle.drainDone(77)).isEqualTo("{\"type\":\"drain-done\",\"pid\":77}");
        assertThat(ProtoLifecycle.error("deadline", "too slow"))
                .isEqualTo("{\"type\":\"error\",\"code\":\"deadline\",\"message\":\"too slow\"}");
        assertThat(ProtoLifecycle.requestFailed("boom"))
                .isEqualTo("{\"type\":\"error\",\"code\":\"request-failed\",\"message\":\"boom\"}");
        assertThat(ProtoLifecycle.alreadyRunning(41, 9, "Build #41 is already running"))
                .isEqualTo(
                        "{\"type\":\"error\",\"code\":\"already-running\",\"message\":\"Build #41 is already running\",\"buildNumber\":41,\"jid\":9}");
        assertThat(ProtoLifecycle.heartbeat(15000)).isEqualTo("{\"type\":\"heartbeat\",\"elapsedMillis\":15000}");
    }

    @Test
    void job_lines_omit_what_is_not_known() {
        assertThat(ProtoLifecycle.jobStart(9, "build", "a/b", 41))
                .isEqualTo("{\"type\":\"job-start\",\"jid\":9,\"kind\":\"build\",\"dir\":\"a/b\",\"buildNumber\":41}");
        assertThat(ProtoLifecycle.jobStart(9, null, null, 0, null, -1))
                .isEqualTo("{\"type\":\"job-start\",\"jid\":9,\"kind\":\"\",\"dir\":\"\"}");
        assertThat(ProtoLifecycle.jobStart(9, "test", "a/b", 41, "/runs/41/details.jsonl", 2500))
                .isEqualTo(
                        "{\"type\":\"job-start\",\"jid\":9,\"kind\":\"test\",\"dir\":\"a/b\",\"buildNumber\":41,\"detailsPath\":\"/runs/41/details.jsonl\",\"etaMs\":2500}");
        assertThat(ProtoLifecycle.jobStart(9, "test", "a/b", 41, "  ", 0))
                .isEqualTo(
                        "{\"type\":\"job-start\",\"jid\":9,\"kind\":\"test\",\"dir\":\"a/b\",\"buildNumber\":41,\"etaMs\":0}");
        assertThat(ProtoLifecycle.jobFinish(9)).isEqualTo("{\"type\":\"job-finish\",\"jid\":9}");
        assertThat(ProtoLifecycle.cancelRequest(9)).isEqualTo("{\"type\":\"cancel-request\",\"jid\":9}");
        assertThat(ProtoLifecycle.cancelRequestForDir("a/b"))
                .isEqualTo("{\"type\":\"cancel-request\",\"dir\":\"a/b\"}");
        assertThat(ProtoLifecycle.cancelAck(9, true, null))
                .isEqualTo("{\"type\":\"cancel-ack\",\"jid\":9,\"cancelled\":true}");
        assertThat(ProtoLifecycle.cancelAck(9, false, "already finished"))
                .isEqualTo("{\"type\":\"cancel-ack\",\"jid\":9,\"cancelled\":false,\"note\":\"already finished\"}");
    }
}
