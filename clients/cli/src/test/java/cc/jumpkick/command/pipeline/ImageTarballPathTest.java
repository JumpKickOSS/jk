// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.WorkingDirectory;
import cc.jumpkick.model.command.Invocation;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The engine writes {@code --tarball}, so a relative one is resolved here, where the user typed it. */
class ImageTarballPathTest {

    @Test
    void a_relative_tarball_is_absolute_against_the_invocation_directory() {
        Invocation in =
                Invocation.builder().addValue("tarball", "target/ns.tar").build();

        assertThat(ImageVerb.tarballPath(in))
                .isEqualTo(WorkingDirectory.current().resolve("target/ns.tar").toString());
        assertThat(Path.of(ImageVerb.tarballPath(in))).isAbsolute();
        assertThat(ImageVerb.tarballPath(Invocation.builder().build())).isNull();
    }
}
