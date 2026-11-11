// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.InstallDestination;
import cc.jumpkick.repo.M2Dirs;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Maven destination {@code jk install} reports is the one the install writes to. */
class InstallRepositoryRootsTest {

    @TempDir
    Path tmp;

    @Test
    void with_no_m2_dir_the_configured_local_repository_is_reported() {
        Path configured = tmp.resolve("configured-repo");
        System.setProperty(M2Dirs.LOCAL_PROPERTY, configured.toString());

        assertThat(InstallCommand.repositoryRoots(null)).containsEntry(InstallDestination.M2, configured);
    }

    @Test
    void an_m2_dir_names_its_repository() {
        assertThat(InstallCommand.repositoryRoots(tmp.resolve("m2")))
                .containsEntry(InstallDestination.M2, tmp.resolve("m2/repository"));
    }
}
