// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.generate;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Glob bases are what the engine fingerprints; expansion is what the tool reads. */
class InputsTest {

    @Test
    void the_base_is_the_path_before_the_first_glob_segment() {
        assertThat(Inputs.globBase("api/openapi.yaml")).isEqualTo("api/openapi.yaml");
        assertThat(Inputs.globBase("src/main/avro/**/*.avsc")).isEqualTo("src/main/avro");
        assertThat(Inputs.globBase("api/*.yaml")).isEqualTo("api");
        assertThat(Inputs.globBase("*.g4")).isEqualTo(".");
    }

    @Test
    void expansion_is_sorted_absolute_and_deduplicated(@TempDir Path module) throws Exception {
        Path b = write(module.resolve("api/b.yaml"));
        Path a = write(module.resolve("api/a.yaml"));
        Path nested = write(module.resolve("api/v2/c.yaml"));
        write(module.resolve("api/README.md"));

        assertThat(Inputs.expand(module, List.of("api/*.yaml", "api/a.yaml", "api/**/*.yaml")))
                .containsExactly(a, b, nested);
    }

    @Test
    void a_missing_plain_path_matches_nothing(@TempDir Path module) throws Exception {
        assertThat(Inputs.expand(module, List.of("api/none.yaml", "proto/*.proto")))
                .isEmpty();
    }

    private static Path write(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, "x").toAbsolutePath().normalize();
    }
}
