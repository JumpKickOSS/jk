// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Hashing;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class M2IntegrityTest {

    private static final String POM = "org/junit/jupiter/junit-jupiter-engine/6.1.3/junit-jupiter-engine-6.1.3.pom";

    @Test
    void a_file_rewritten_after_maven_verified_it_is_named(@TempDir Path m2) throws Exception {
        Path pom = write(m2, POM, "<project/>");
        Files.writeString(
                pom.resolveSibling(pom.getFileName() + ".sha1"),
                Hashing.hashHex("SHA-1", "<project/>".getBytes(StandardCharsets.UTF_8)));
        assertThat(M2Integrity.mismatched(m2, List.of(POM))).isEmpty();

        Files.writeString(pom, "org.checkerframework:checker-qual:4.2.3\n");

        assertThat(M2Integrity.mismatched(m2, List.of(POM))).containsExactly(pom);
    }

    @Test
    void a_file_without_a_well_formed_sha1_or_absent_is_not_judged(@TempDir Path m2) throws Exception {
        Path pom = write(m2, POM, "<project/>");
        assertThat(M2Integrity.mismatched(m2, List.of(POM, "g/a/1/a-1.jar"))).isEmpty();

        Files.writeString(pom.resolveSibling(pom.getFileName() + ".sha1"), "<html>not found</html>");
        assertThat(M2Integrity.mismatched(m2, List.of(POM))).isEmpty();
    }

    @Test
    void a_path_that_climbs_out_of_the_repository_is_skipped(@TempDir Path m2) {
        assertThat(M2Integrity.mismatched(m2, List.of("../../etc/passwd"))).isEmpty();
    }

    private static Path write(Path root, String rel, String body) throws Exception {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, body);
    }
}
