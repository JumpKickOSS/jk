// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Guest;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BandPortTest {

    @TempDir
    Path tmp;

    @Test
    void aGuestStartsFromItsIdentityAndKeepsWhatItBound() throws Exception {
        Path file = tmp.resolve("engine").resolve(BandPort.FILE);
        String id = EngineIdentity.idOf("linux", "BocaMiniPC", "/home/me/.jk");

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

    @Test
    void theIdentityNamesTheEngineAndIsStable() {
        String id = EngineIdentity.idOf("windows", "BocaMiniPC", "C:/Users/me/.jk");
        assertThat(id).hasSize(12).isEqualTo(EngineIdentity.idOf("windows", "BocaMiniPC", "C:/Users/me/.jk"));
        assertThat(id).isNotEqualTo(EngineIdentity.idOf("linux", "BocaMiniPC", "C:/Users/me/.jk"));

        EngineIdentity host = new EngineIdentity(id, "windows", Guest.NONE, "BocaMiniPC", "C:/Users/me/.jk", "1.0");
        assertThat(host.label()).isEqualTo("Windows on BocaMiniPC");
        assertThat(host.role()).isEqualTo("host");

        EngineIdentity wsl = new EngineIdentity(
                id, "linux", new Guest(Guest.Kind.WSL, "Ubuntu-26.10", true), "BocaMiniPC", "/home/me/.jk", "1.0");
        assertThat(wsl.label()).isEqualTo("WSL Ubuntu-26.10 on BocaMiniPC");
        assertThat(wsl.toMap(8933)).containsEntry("role", "guest").containsEntry("port", 8933);
    }
}
