// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.micronaut;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A Micronaut AOT classpath with an entry missing fails rather than running with less. */
class MicronautAotClasspathTest {

    @Test
    void a_missing_classpath_entry_fails_instead_of_shortening_the_classpath(@TempDir Path tmp) throws Exception {
        Path present = Files.writeString(tmp.resolve("present.jar"), "x");
        Path absent = tmp.resolve("absent.jar");

        assertThatThrownBy(() -> MicronautPlugin.joinCp(List.of(present, absent)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("absent.jar");
    }

    @Test
    void entries_join_with_the_platform_separator(@TempDir Path tmp) throws Exception {
        Path a = Files.writeString(tmp.resolve("a.jar"), "x");
        Path b = Files.writeString(tmp.resolve("b.jar"), "x");

        assertThat(MicronautPlugin.joinCp(List.of(a, b)))
                .isEqualTo(a.toAbsolutePath().normalize()
                        + System.getProperty("path.separator", ":")
                        + b.toAbsolutePath().normalize());
    }
}
