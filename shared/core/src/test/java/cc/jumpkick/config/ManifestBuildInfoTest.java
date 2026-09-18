// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.BuildBlock;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code [build-info]} — the git build-info resources a jar carries. An empty table is the battery
 * at its defaults; {@code file} moves the git properties file; {@code time} picks the commit time
 * or the wall clock.
 */
class ManifestBuildInfoTest {

    @TempDir
    Path tmp;

    private JkBuild parse(String toml) throws Exception {
        Path f = tmp.resolve("jk.toml");
        Files.writeString(f, "name = \"m\"\ngroup = \"g\"\nversion = \"1.0\"\n" + toml);
        return JkBuildParser.parse(f);
    }

    @Test
    void absent_means_nothing_is_written() throws Exception {
        assertThat(parse("").build().buildInfo()).isNull();
    }

    @Test
    void an_empty_table_is_the_battery_at_its_defaults() throws Exception {
        BuildBlock.BuildInfo info =
                Objects.requireNonNull(parse("[build-info]\n").build().buildInfo());
        assertThat(info).isEqualTo(BuildBlock.BuildInfo.DEFAULT);
        assertThat(info.file()).isEqualTo("git.properties");
        assertThat(info.buildTime()).isFalse();
    }

    @Test
    void file_and_time_are_read() throws Exception {
        BuildBlock.BuildInfo info =
                Objects.requireNonNull(parse("[build-info]\nfile = \"apollo-git.properties\"\ntime = \"build\"\n")
                        .build()
                        .buildInfo());
        assertThat(info.file()).isEqualTo("apollo-git.properties");
        assertThat(info.buildTime()).isTrue();
    }

    @Test
    void an_unknown_key_is_a_parse_error() {
        assertThatThrownBy(() -> parse("[build-info]\nfilename = \"x\"\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[build-info] unknown key `filename`")
                .hasMessageContaining("file, time");
    }

    @Test
    void time_accepts_only_commit_or_build() {
        assertThatThrownBy(() -> parse("[build-info]\ntime = \"now\"\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("time must be \"commit\"");
    }

    @Test
    void the_file_stays_inside_the_jar() {
        assertThatThrownBy(() -> parse("[build-info]\nfile = \"../git.properties\"\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("relative resource path");
        assertThatThrownBy(() -> parse("build-info = true\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("must be a table");
    }
}
