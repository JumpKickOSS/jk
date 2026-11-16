// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.http.FrontDoorRole.Reach;
import cc.jumpkick.engine.http.GuestPairing;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** How a WSL guest reads the check it ran from Windows, and where it stands among the guests. */
class FrontDoorDutyTest {

    @TempDir
    Path guests;

    @Test
    void only_an_identity_echoing_the_nonce_is_this_guest_and_only_a_missing_answer_is_unknown() {
        String ok =
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"id\":\"caf4beecf11d\",\"nonce\":\"n1\"}";
        assertThat(FrontDoorDuty.reach(ok, "n1")).isEqualTo(Reach.REACHABLE);
        assertThat(FrontDoorDuty.reach("#< CLIXML\r\n" + ok + "\r\n<Objs Version=\"1.1\"></Objs>", "n1"))
                .as("PowerShell's progress records around the answer")
                .isEqualTo(Reach.REACHABLE);
        assertThat(FrontDoorDuty.reach(ok, "n2"))
                .as("another guest, or this one before")
                .isEqualTo(Reach.UNREACHABLE);
        assertThat(FrontDoorDuty.reach("HTTP/1.1 200 OK\r\n\r\n{\"id\":\"90cec5c68a24\"}", "n1"))
                .as("the host, or another engine")
                .isEqualTo(Reach.UNREACHABLE);
        assertThat(FrontDoorDuty.reach("HTTP/1.0 404 Not Found\r\n\r\n<html>", "n1"))
                .as("not an engine")
                .isEqualTo(Reach.UNREACHABLE);
        assertThat(FrontDoorDuty.reach("REFUSED\r\n", "n1")).isEqualTo(Reach.UNREACHABLE);
        assertThat(FrontDoorDuty.reach("TIMEOUT", "n1"))
                .as("a loopback connect that never completes")
                .isEqualTo(Reach.UNREACHABLE);
        assertThat(FrontDoorDuty.reach("ERROR", "n1")).isEqualTo(Reach.UNKNOWN);
        assertThat(FrontDoorDuty.reach(null, "n1"))
                .as("no interop or PowerShell")
                .isEqualTo(Reach.UNKNOWN);
    }

    @Test
    void the_probe_uses_only_what_every_windows_has() {
        String script = FrontDoorDuty.probeScript(8910);
        assertThat(script).contains("Net.Sockets.TcpClient", "'127.0.0.1', 8910", "GET /api/identity");
        assertThat(script).doesNotContain("curl", "Invoke-WebRequest");
    }

    @Test
    void a_guest_is_outranked_by_each_paired_guest_with_a_lower_id() throws Exception {
        GuestPairing.write(guests, new GuestPairing.Pair("111111111111", 8911, "a"));
        GuestPairing.write(guests, new GuestPairing.Pair("555555555555", 8912, "b"));
        GuestPairing.write(guests, new GuestPairing.Pair("999999999999", 8913, "c"));
        assertThat(FrontDoorDuty.outranking(guests, "111111111111")).isZero();
        assertThat(FrontDoorDuty.outranking(guests, "555555555555")).isEqualTo(1);
        assertThat(FrontDoorDuty.outranking(guests, "999999999999")).isEqualTo(2);
    }
}
