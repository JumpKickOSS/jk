// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Hashing;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The m2 row: a locked file in the Maven local repository that no longer matches its {@code .sha1}. */
class DoctorM2Test {

    private static final String POM = "org/junit/jupiter/junit-jupiter-engine/6.1.3/junit-jupiter-engine-6.1.3.pom";

    @Test
    void a_rewritten_local_repository_file_is_a_warning_naming_it(@TempDir Path m2) throws Exception {
        Path pom = m2.resolve(POM);
        Files.createDirectories(pom.getParent());
        Files.writeString(pom, "<project/>");
        Files.writeString(
                pom.resolveSibling(pom.getFileName() + ".sha1"),
                Hashing.hashHex("SHA-1", "<project/>".getBytes(StandardCharsets.UTF_8)));

        assertThat(DoctorCommand.checkM2(m2, List.of(POM)).status()).isEqualTo(DoctorCommand.Status.OK);

        Files.writeString(pom, "org.checkerframework:checker-qual:4.2.3\n");
        DoctorCommand.Check check = DoctorCommand.checkM2(m2, List.of(POM));

        assertThat(check.status()).isEqualTo(DoctorCommand.Status.WARN);
        assertThat(check.detail()).contains("1 file does not match its .sha1").contains(pom.toString());
    }
}
