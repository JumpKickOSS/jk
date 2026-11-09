// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.winhost.HostRow;
import java.util.List;
import org.junit.jupiter.api.Test;

class HttpHostApiTest {

    @Test
    void onlyWarningsBecomeNotes() {
        HttpHostApi api = new HttpHostApi(() -> List.of(
                new HostRow("arch", "arch", HostRow.Status.OK, "AMD64, nothing emulated", null),
                new HostRow(
                        "defender",
                        "defender",
                        HostRow.Status.WARN,
                        "slower: Defender scans C:\\Users\\ada\\.jk",
                        "jk doctor --fix")));
        assertThat(api.json())
                .isEqualTo(
                        "{\"notes\":[{\"id\":\"defender\",\"detail\":\"slower: Defender scans C:\\\\Users\\\\ada\\\\.jk\","
                                + "\"fix\":\"jk doctor --fix\"}]}");
    }

    @Test
    void noRowsNoNotes() {
        assertThat(new HttpHostApi(List::of).json()).isEqualTo("{\"notes\":[]}");
    }
}
