// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The native image of the client jk installs as its own binary is linked with its checkout's commit
 * only for a release build (no {@code -SNAPSHOT}, clean checkout), so a snapshot's image keys on no
 * commit and is not relinked when the commit moves; any other image, and a client built outside a
 * git checkout (a source tarball), is linked with nothing extra.
 */
class NativeCommitStampTest {

    private static JkBuild client() {
        return client("0.13.7");
    }

    private static JkBuild client(String version) {
        return JkBuild.builder(new Project("cc.jumpkick", "jk-cli", version, 25))
                .install(new JkBuild.Install(null, "jk"))
                .build();
    }

    @Test
    void a_release_client_image_carries_the_commit_and_a_snapshot_or_dirty_one_does_not(@TempDir Path tmp)
            throws Exception {
        Path repo = tmp.resolve("repo");
        Path module = Files.createDirectories(repo.resolve("cli"));
        RevCommit head = commit(repo);

        assertThat(PackagingKeys.nativeCommitArgs(client(), module))
                .containsExactly("-D" + BuildIdentity.COMMIT_PROPERTY + "=" + head.getName());
        assertThat(PackagingKeys.nativeCommitArgs(client("0.13.7-SNAPSHOT"), module))
                .as("a snapshot's image keys on no commit")
                .isEmpty();

        Files.writeString(repo.resolve("one.txt"), "edited");
        assertThat(PackagingKeys.nativeCommitArgs(client(), module))
                .as("a dirty tree is no release")
                .isEmpty();
    }

    @Test
    void another_image_and_a_checkout_without_git_get_nothing(@TempDir Path tmp) throws Exception {
        Path repo = tmp.resolve("repo");
        Path module = Files.createDirectories(repo.resolve("app"));
        commit(repo);
        JkBuild app =
                JkBuild.builder(new Project("com.example", "app", "1.0.0", 25)).build();
        assertThat(PackagingKeys.nativeCommitArgs(app, module)).isEmpty();

        Path tarball = Files.createDirectories(tmp.resolve("tarball/cli"));
        assertThat(PackagingKeys.nativeCommitArgs(client(), tarball)).isEmpty();
    }

    private static RevCommit commit(Path repo) throws Exception {
        Files.createDirectories(repo);
        try (Git git =
                Git.init().setDirectory(repo.toFile()).setInitialBranch("main").call()) {
            Files.writeString(repo.resolve("one.txt"), "one");
            git.add().addFilepattern(".").call();
            PersonIdent ident = new PersonIdent("t", "t@e", Instant.parse("2026-06-01T13:47:52Z"), ZoneOffset.UTC);
            return git.commit()
                    .setMessage("one")
                    .setAuthor(ident)
                    .setCommitter(ident)
                    .call();
        }
    }
}
