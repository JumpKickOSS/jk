// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.command.Invocation;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The engine writes {@code --tarball}, so a relative one is resolved here, where the user typed it. */
class ImageTarballPathTest {

    @Test
    void a_relative_tarball_is_absolute_against_the_invocation_directory() {
        Invocation in =
                Invocation.builder().addValue("tarball", "target/ns.tar").build();

        assertThat(ImageCommand.tarballPath(in))
                .isEqualTo(Path.of("")
                        .toAbsolutePath()
                        .resolve("target/ns.tar")
                        .normalize()
                        .toString());
        assertThat(Path.of(ImageCommand.tarballPath(in))).isAbsolute();
        assertThat(ImageCommand.tarballPath(Invocation.builder().build())).isNull();
    }
}
