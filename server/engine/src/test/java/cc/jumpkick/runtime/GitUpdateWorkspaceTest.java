// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.runtime.base.TestStoreSeed;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk update -g} in a workspace re-pins git rows in the one root lock, resolved from the
 * whole workspace: no member's closure is written over it.
 */
@Tag("integration")
class GitUpdateWorkspaceTest {

    /** A local git repo holding a no-dependency library {@code com.acme:name}, tagged v1.0.0; its URL. */
    private static String library(Path repo, String name) throws Exception {
        Files.createDirectories(repo.resolve("src/main/java/acme"));
        Files.writeString(repo.resolve("jk.toml"), """
                group   = "com.acme"
                name    = "%s"
                version = "1.0.0"
                java    = 25
                """.formatted(name));
        Files.writeString(
                repo.resolve(
                        "src/main/java/acme/" + Character.toUpperCase(name.charAt(0)) + name.substring(1) + ".java"),
                "package acme; public class " + Character.toUpperCase(name.charAt(0)) + name.substring(1) + " {}\n");
        try (Git git = Git.init().setDirectory(repo.toFile()).call()) {
            git.add().addFilepattern(".").call();
            var commit = git.commit()
                    .setMessage("v1")
                    .setAuthor("t", "t@e")
                    .setCommitter("t", "t@e")
                    .call();
            git.tag().setName("v1.0.0").setObjectId(commit).setAnnotated(false).call();
        }
        return repo.toUri().toString();
    }

    private static void member(Path dir, String name, String dep, String url) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("jk.toml"), """
                name = "%s"
                version = "0.1.0"

                [dependencies]
                %s = { git = "%s", tag = "v1.0.0" }
                """.formatted(name, dep, url));
    }

    @Test
    void a_git_update_from_the_root_keeps_every_members_rows(@TempDir Path tmp) throws Exception {
        String widgets = library(tmp.resolve("widgets"), "widgets");
        String gadgets = library(tmp.resolve("gadgets"), "gadgets");
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group = "com.example"
                name = "ws"
                version = "0.1.0"
                java = 25

                [workspace]
                modules = ["a", "b"]
                """);
        member(ws.resolve("a"), "a", "widgets", widgets);
        member(ws.resolve("b"), "b", "gadgets", gadgets);
        Path cache = tmp.resolve("cache");
        TestStoreSeed.seed(cache);

        LockPlans.GitUpdateOutcome all = LockPlans.updateGitOnly(
                ws, JkBuildParser.parse(ws.resolve("jk.toml")), cache, null, List.of(), true, null);
        assertThat(all.error()).isNull();
        assertThat(modules(ws)).contains("com.acme:widgets", "com.acme:gadgets");

        LockPlans.GitUpdateOutcome one = LockPlans.updateGitOnly(
                ws, JkBuildParser.parse(ws.resolve("jk.toml")), cache, null, List.of(), true, "widgets");
        assertThat(one.error()).isNull();
        assertThat(modules(ws))
                .as("the row another member declares is not dropped by a member's update")
                .contains("com.acme:widgets", "com.acme:gadgets");
    }

    private static List<String> modules(Path ws) throws Exception {
        Lockfile lock = LockfileReader.read(LockPaths.lockFile(ws));
        return lock.artifacts().stream()
                .map(a -> a.name().split(":")[0] + ":" + a.name().split(":")[1])
                .toList();
    }
}
