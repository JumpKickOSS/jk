// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The rules {@link R8Step} generates for R8 to read. */
class R8StepTest {

    /** The rules parser ends a bare file name at whitespace, so a path with a space is quoted. */
    @Test
    void the_retrace_output_paths_are_quoted(@TempDir Path tmp) {
        Path mapping = tmp.resolve("My Projects/app/mapping");

        assertThat(R8Step.retraceRules(mapping).lines())
                .containsExactly(
                        "-printseeds \"" + mapping.resolve("seeds.txt").toAbsolutePath() + "\"",
                        "-printusage \"" + mapping.resolve("usage.txt").toAbsolutePath() + "\"");
    }
}
