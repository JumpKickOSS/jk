// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The runtimes read an env file's {@code NAME=VALUE} lines literally up to the line's end, where
 * they differ: what a line carries intact goes in the file, the rest is named on the command line.
 */
class ContainerEnvTest {

    @Test
    void a_line_carries_leading_spaces_hashes_equals_and_quotes_but_not_a_line_end_or_trailing_space() {
        assertThat(ContainerEnv.fitsFile("A", "")).isTrue();
        assertThat(ContainerEnv.fitsFile("A", " #x=y \"q\" 'q'")).isTrue();
        assertThat(ContainerEnv.fitsFile("A", "a\nb")).isFalse();
        assertThat(ContainerEnv.fitsFile("A", "a\r")).isFalse();
        assertThat(ContainerEnv.fitsFile("A", "a ")).isFalse();
        assertThat(ContainerEnv.fitsFile("A", "a ")).isFalse();
        assertThat(ContainerEnv.fitsFile("A", "x".repeat(70_000))).isFalse();
    }

    @Test
    void a_name_the_runtimes_would_misread_is_refused() {
        for (String name : new String[] {"", "#A", "A=B", "A B", "A\tB"}) {
            assertThatThrownBy(() -> ContainerEnv.checkName(name)).isInstanceOf(IOException.class);
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void the_file_holds_what_fits_and_is_the_owners_alone(@TempDir Path tmp) throws Exception {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("A", "1");
        env.put("PEM", "line\nline");
        env.put("B", " =#2");

        Path file = Objects.requireNonNull(ContainerEnv.write(tmp.resolve("aot-env"), env));

        assertThat(Files.readString(file)).isEqualTo("A=1\nB= =#2\n");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
                .isEqualTo("rw-------");
        assertThat(ContainerEnv.args(env, file))
                .containsExactly("--env-file", file.toAbsolutePath().toString(), "-e", "PEM");
        assertThat(ContainerEnv.write(tmp.resolve("other"), Map.of("PEM", "a\nb")))
                .as("no file when nothing fits one")
                .isNull();
    }
}
