// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.generate;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Classpaths;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The argument vocabulary: each variable an absolute path, {@code ${inputs}} alone splicing. */
class ArgumentsTest {

    private static final Path MODULE = Path.of("/work/svc").toAbsolutePath();
    private static final Path OUT = Path.of("/scratch/generated/api").toAbsolutePath();
    private static final Path A = MODULE.resolve("api/a.yaml");
    private static final Path B = MODULE.resolve("api/b.yaml");
    private static final Arguments.Scope SCOPE = new Arguments.Scope(List.of(A, B), OUT, MODULE);

    @Test
    void in_is_the_first_input_and_out_and_module_dir_are_absolute() {
        assertThat(Arguments.expand(List.of("-i", "${in}", "-o", "${out}", "--root=${module.dir}"), SCOPE))
                .containsExactly("-i", A.toString(), "-o", OUT.toString(), "--root=" + MODULE);
    }

    @Test
    void inputs_alone_splices_one_argument_per_input() {
        assertThat(Arguments.expand(List.of("compile", "${inputs}", "-d"), SCOPE))
                .containsExactly("compile", A.toString(), B.toString(), "-d");
    }

    @Test
    void inputs_embedded_joins_with_the_path_separator() {
        assertThat(Arguments.expand(List.of("--files=${inputs}"), SCOPE))
                .containsExactly("--files=" + Classpaths.join(List.of(A, B)));
    }

    @Test
    void a_tools_own_placeholder_passes_through() {
        assertThat(Arguments.expand(List.of("--template=${name}.java", "${in}"), SCOPE))
                .containsExactly("--template=${name}.java", A.toString());
    }
}
