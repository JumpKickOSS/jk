// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The commit an install of jk's own client records beside it: the checkout's HEAD, as the engine
 * jar's stamp names it whatever is edited on top, and nothing outside a git checkout.
 */
class InstallCheckoutCommitTest {

    @Test
    void head_is_recorded_edited_or_not_and_no_checkout_records_nothing(@TempDir Path tmp) throws Exception {
        Path repo = Files.createDirectories(tmp.resolve("repo"));
        ReleaseCommand.Git git = new ReleaseCommand.Git(repo);
        assertThat(git.run("init", "-q", "-b", "main")).isNull();
        Files.writeString(repo.resolve("a.txt"), "a");
        assertThat(git.run("add", "a.txt")).isNull();
        assertThat(git.run("-c", "user.name=t", "-c", "user.email=t@e", "commit", "-q", "-m", "one"))
                .isNull();
        String head = git.output("rev-parse", "HEAD");

        assertThat(InstallCommand.checkoutCommit(repo)).isEqualTo(head);
        Files.writeString(repo.resolve("a.txt"), "edited");
        assertThat(InstallCommand.checkoutCommit(repo))
                .as("an edit on top names the same commit the engine jar is stamped with")
                .isEqualTo(head);

        assertThat(InstallCommand.checkoutCommit(Files.createDirectories(tmp.resolve("tarball"))))
                .isNull();
    }
}
