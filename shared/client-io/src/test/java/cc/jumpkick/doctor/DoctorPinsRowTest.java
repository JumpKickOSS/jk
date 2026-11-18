// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.doctor;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.winhost.HostRow;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code jk doctor} names JumpKick's own pins behind the running jk, and is quiet when there are none. */
class DoctorPinsRowTest {

    @Test
    void a_pin_behind_the_running_jk_warns_naming_it_and_the_fix(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("jk.toml"), """
                group = "com.example"
                name = "plug"
                version = "0.1.0"

                [dependencies]
                jk-plugin-sdk = "cc.jumpkick:jk-plugin-sdk:0.50.2"
                """);

        HostRow behind = DoctorChecks.pins(project, "0.51.1");
        assertThat(behind.id()).isEqualTo("pins");
        assertThat(behind.status()).isEqualTo(HostRow.Status.WARN);
        assertThat(behind.detail()).contains("jk-plugin-sdk 0.50.2 (jk.toml)", "run jk update");

        HostRow current = DoctorChecks.pins(project, "0.50.2");
        assertThat(current.status()).isEqualTo(HostRow.Status.OK);
        assertThat(current.detail()).isEqualTo("1 JumpKick pin at this jk's release or newer");

        HostRow snapshot = DoctorChecks.pins(project, "0.51.2-SNAPSHOT");
        assertThat(snapshot.status()).as("a checkout's jk nags about nothing").isEqualTo(HostRow.Status.OK);
        assertThat(snapshot.detail())
                .isEqualTo("1 JumpKick pin; not compared: this jk (0.51.2-SNAPSHOT) is not a release")
                .doesNotContain("jk update");
    }
}
