// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.plugin.testing.FakeBuildIo;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link TaskExec#outputDir} creates a directory strictly below the step's scratch and nowhere else. */
class OutputDirTest {

    @Test
    void a_relative_path_below_the_scratch_is_created_there(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = new FakeBuildIo(tmp, "demo");

        assertThat(io.outputDir("gen")).isDirectory().isEqualTo(io.scratch().resolve("gen"));
        assertThat(io.outputDir("lint/checkstyle")).isDirectory();
    }

    @Test
    void a_path_outside_the_scratch_or_naming_it_is_refused_and_nothing_is_created(@TempDir Path tmp) throws Exception {
        FakeBuildIo io = new FakeBuildIo(tmp, "demo");
        Path outside = tmp.resolve("outside");

        for (String rel : List.of("..", "../outside", "gen/../../outside", outside.toString(), ".", "")) {
            assertThatThrownBy(() -> io.outputDir(rel))
                    .as(rel)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("output dir \"" + rel + "\" is not under the step's output root");
        }
        assertThat(outside).doesNotExist();
    }
}
