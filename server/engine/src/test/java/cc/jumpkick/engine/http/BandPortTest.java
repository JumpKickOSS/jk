// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Guest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BandPortTest {

    @TempDir
    Path tmp;

    @Test
    void aGuestStartsFromItsIdentityAndKeepsWhatItBound() throws Exception {
        Path file = tmp.resolve("engine").resolve(BandPort.FILE);
        String id = EngineIdentity.idOf("linux", UBUNTU_26_04, "Workstation", "/home/me/.jk");

        int first = BandPort.preferred(file, id);
        assertThat(first).isBetween(BandPort.FIRST, BandPort.LAST).isEqualTo(BandPort.preferred(file, id));

        BandPort.remember(file, 8957);
        assertThat(BandPort.preferred(file, id)).isEqualTo(8957);

        Files.writeString(file, "8910");
        assertThat(BandPort.preferred(file, id)).as("never the host's port").isEqualTo(first);
    }

    @Test
    void theHuntWrapsWithinTheBand() {
        assertThat(BandPort.next(8911)).isEqualTo(8912);
        assertThat(BandPort.next(BandPort.LAST)).isEqualTo(BandPort.FIRST);
    }

    /** Two Ubuntu installs on one Windows host as they read live: same name, hostname and home. */
    private static final Guest UBUNTU_26_04 = new Guest(
            Guest.Kind.WSL,
            "Ubuntu",
            true,
            "machine-id=8be22fc4f5024a67ab9f16b3f31480b6 disk=naa.60022480e0952abfdb4c9819122a93b6");

    private static final Guest UBUNTU_26_10 = new Guest(
            Guest.Kind.WSL,
            "Ubuntu",
            true,
            "machine-id=36d266172165422fbc09ec805d15162e disk=naa.60022480e2c91a8ae50d02212d6ec705");

    @Test
    void twoDistrosOnOneWindowsHostGetTwoIdsAndTwoFirstPorts() {
        String a = EngineIdentity.idOf("linux", UBUNTU_26_04, "Desk", "/home/bsant/.jk");
        String b = EngineIdentity.idOf("linux", UBUNTU_26_10, "Desk", "/home/bsant/.jk");

        assertThat(a).isNotEqualTo(b);
        assertThat(BandPort.preferred(tmp.resolve("a"), a)).isNotEqualTo(BandPort.preferred(tmp.resolve("b"), b));
        assertThat(EngineIdentity.idOf("linux", UBUNTU_26_04, "Desk", "/home/bsant/.jk"))
                .as("the same distro after a restart")
                .isEqualTo(a);
    }

    @Test
    void everyEngineOnOneMachineHasItsOwnId() {
        Guest container = new Guest(Guest.Kind.CONTAINER, "docker", false, "4f2c" + "0".repeat(60));
        Guest otherContainer = new Guest(Guest.Kind.CONTAINER, "docker", false, "9a1b" + "0".repeat(60));
        List<String> ids = List.of(
                EngineIdentity.idOf("windows", Guest.NONE, "Desk", "C:\\Users\\bsant\\.jk"),
                EngineIdentity.idOf("windows", Guest.NONE, "Desk", "D:\\jk-home"),
                EngineIdentity.idOf("linux", UBUNTU_26_04, "Desk", "/home/bsant/.jk"),
                EngineIdentity.idOf("linux", UBUNTU_26_10, "Desk", "/home/bsant/.jk"),
                EngineIdentity.idOf("linux", container, "Desk", "/root/.jk"),
                EngineIdentity.idOf("linux", otherContainer, "Desk", "/root/.jk"),
                EngineIdentity.idOf("linux", Guest.NONE, "Desk", "/root/.jk"));

        assertThat(ids).doesNotHaveDuplicates().allMatch(id -> id.matches("[0-9a-f]{12}"));
    }

    @Test
    void theIdentityNamesTheEngineAndIsStable() {
        String id = EngineIdentity.idOf("windows", Guest.NONE, "Workstation", "C:/Users/me/.jk");
        assertThat(id)
                .hasSize(12)
                .isEqualTo(EngineIdentity.idOf("windows", Guest.NONE, "Workstation", "C:/Users/me/.jk"));
        assertThat(id).isNotEqualTo(EngineIdentity.idOf("linux", Guest.NONE, "Workstation", "C:/Users/me/.jk"));

        EngineIdentity host = new EngineIdentity(id, "windows", Guest.NONE, "Workstation", "C:/Users/me/.jk", "1.0");
        assertThat(host.label()).isEqualTo("Windows on Workstation");
        assertThat(host.role()).isEqualTo("host");

        EngineIdentity wsl = new EngineIdentity(
                id,
                "linux",
                new Guest(Guest.Kind.WSL, "Ubuntu-26.10", true, "machine-id=36d2"),
                "Workstation",
                "/home/me/.jk",
                "1.0");
        assertThat(wsl.label()).isEqualTo("WSL Ubuntu-26.10 on Workstation");
        assertThat(wsl.toMap(8933)).containsEntry("role", "guest").containsEntry("port", 8933);
    }
}
