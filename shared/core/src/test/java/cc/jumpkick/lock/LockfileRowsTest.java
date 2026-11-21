// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.Scope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The written order of rows that share a coordinate and version, a CRLF checkout, and which table a missing key names. */
class LockfileRowsTest {

    private static Lockfile.Artifact row(List<Scope> scopes, List<String> members) {
        return new Lockfile.Artifact(
                        "org.slf4j:slf4j-api:jar:",
                        "2.0.18",
                        "https://repo.maven.apache.org/maven2",
                        "sha256:" + "ab".repeat(32),
                        null,
                        scopes,
                        List.of(),
                        null,
                        null)
                .withMembers(members);
    }

    private static Lockfile lock(List<Lockfile.Artifact> artifacts) {
        return new Lockfile(Lockfile.CURRENT_VERSION, "jk test", Lockfile.RESOLUTION_ALGORITHM, artifacts);
    }

    @Test
    void rows_of_one_coordinate_and_version_write_in_one_order_whatever_the_solve_gave() {
        Lockfile.Artifact provided = row(List.of(Scope.PROVIDED), List.of("plugins/quarkus"));
        Lockfile.Artifact test = row(List.of(Scope.TEST), List.of("plugins/asyncapi"));
        String one = LockfileWriter.render(lock(List.of(provided, test)));
        String other = LockfileWriter.render(lock(List.of(test, provided)));
        assertThat(one).isEqualTo(other);
        assertThat(one.indexOf("plugins/quarkus")).isLessThan(one.indexOf("plugins/asyncapi"));
    }

    @Test
    void a_crlf_checkout_of_the_same_lock_is_left_untouched(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("jk-lock.toml");
        Lockfile lock = lock(List.of(row(List.of(Scope.MAIN), List.of())));
        LockfileWriter.write(lock, file);
        String crlf = Files.readString(file)
                .replaceFirst("(?m)^generated-by = .*$", "generated-by = \"jk 0.0.1\"")
                .replace("\n", "\r\n");
        Files.writeString(file, crlf);
        LockfileWriter.write(lock, file);
        assertThat(Files.readString(file)).isEqualTo(crlf);
    }

    @Test
    void a_missing_key_names_the_table_of_its_row() {
        String head = """
                version = 1
                generated-by = "jk test"
                resolution-algorithm = "pubgrub-v1"
                jk-min = "0.52.0"

                """;
        assertThatThrownBy(() -> LockfileReader.parse(head + """
                        [[plugin]]
                        coordinate = "cc.jumpkick:p"
                        """))
                .hasMessageContaining("[[plugin]] is missing required key `version`");
        assertThatThrownBy(() -> LockfileReader.parse(head + """
                        [[tool]]
                        version = "1.0"
                        """))
                .hasMessageContaining("[[tool]] is missing required key `coordinate`");
        assertThatThrownBy(() -> LockfileReader.parse(head + """
                        [[artifact]]
                        name = "a:b:jar:"
                        version = "1.0"
                        """))
                .hasMessageContaining("[[artifact]] is missing required key `source`");
    }
}
