// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.forge.ForgeGitCredentials;
import cc.jumpkick.model.GitRefSpec;
import cc.jumpkick.model.GitSource;
import cc.jumpkick.testing.Await;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The git command is a worker like any other fork: it sees the worker allow-list plus what jk adds
 * for it, never the engine's whole environment; a git that outlives its timeout dies with the
 * transport helpers it spawned; and the version probe returns inside its bound however the probed
 * command behaves. Each case drives a shell script standing in for {@code git}.
 */
@Tag("integration")
class GitCliExtensionTest {

    /** Names a POSIX shell exports on its own when it runs a script; not the engine's doing. */
    private static final Set<String> SHELL_OWN = Set.of("PWD", "OLDPWD", "SHLVL", "_");

    @TempDir
    Path dir;

    @Test
    void git_sees_the_worker_allow_list_and_what_jk_adds_for_it_but_not_the_rest_of_the_engines_environment()
            throws Exception {
        Path git = script("git", """
                #!/bin/sh
                env | sed 's/=.*//' | sort | while read -r name; do
                  printf '0000000000000000000000000000000000000000\\trefs/tags/%s\\n' "$name"
                done
                """);
        GitBackend backend = backend(git, 30);
        Map<String, String> engine = new LinkedHashMap<>();
        engine.put("PATH", System.getenv("PATH"));
        engine.put("HOME", dir.toString());
        engine.put("JK_REPO_EXAMPLE_TOKEN", "hunter2");
        engine.put("GIT_DIR", dir.resolve("elsewhere").toString());
        engine.put("GIT_CONFIG_GLOBAL", dir.resolve("gitconfig").toString());

        Set<String> allowed = new HashSet<>();
        Set<String> seen = WorkerEnv.withEngineEnvironment(engine, () -> {
            allowed.addAll(WorkerEnv.strict().environment().keySet());
            return new HashSet<>(backend.listRefs(source()).tags());
        });
        allowed.addAll(Set.of("GIT_TERMINAL_PROMPT", "GIT_PAGER", "LC_ALL", "SSH_AUTH_SOCK"));
        seen.removeAll(SHELL_OWN);

        assertThat(seen).contains("PATH", "HOME", "GIT_TERMINAL_PROMPT", "GIT_PAGER", "LC_ALL");
        assertThat(seen)
                .as("a credential and git's own location overrides stay with the engine")
                .doesNotContain("JK_REPO_EXAMPLE_TOKEN", "GIT_DIR", "GIT_CONFIG_GLOBAL");
        assertThat(seen)
                .as("nothing reaches git that the worker allow-list does not name")
                .isSubsetOf(allowed);
    }

    @Test
    void a_git_that_outlives_its_timeout_is_killed_together_with_the_children_it_spawned() throws Exception {
        Path pidFile = dir.resolve("sleeper.pid");
        Path git = script("git", "#!/bin/sh\nsleep 300 &\necho $! > '" + pidFile + "'\nwait\n");
        GitBackend backend = backend(git, 1);
        try {
            assertThatThrownBy(() -> backend.listRefs(source()))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("timed out");
            long sleeper = Long.parseLong(Files.readString(pidFile).strip());
            Await.until(
                    Duration.ofSeconds(5),
                    () -> ProcessHandle.of(sleeper).map(h -> !h.isAlive()).orElse(true),
                    () -> "the transport helper git left behind is still running as pid " + sleeper);
        } finally {
            killRecorded(pidFile);
        }
    }

    @Test
    void the_version_probe_returns_within_its_bound_when_git_never_ends() throws Exception {
        Path pidFile = dir.resolve("probe.pid");
        Path git = script("git", "#!/bin/sh\necho 'git version 2.40.1'\necho $$ > '" + pidFile + "'\nsleep 300\n");
        try {
            Optional<GitCliExtension.GitCli> probed = assertTimeoutPreemptively(
                    Duration.ofSeconds(10), () -> GitCliExtension.probe(git.toString(), 1_000));
            assertThat(probed).as("a git that never exits is not a usable git").isEmpty();
        } finally {
            killRecorded(pidFile);
        }
    }

    @Test
    void the_version_probe_reads_the_version_of_a_git_that_answers() throws Exception {
        Path git = script("git", "#!/bin/sh\necho 'git version 2.45.2.windows.1'\n");
        assertThat(GitCliExtension.probe(git.toString(), 5_000))
                .contains(new GitCliExtension.GitCli(git.toString(), 2, 45));
    }

    private GitBackend backend(Path git, long timeoutSec) {
        return new GitCliExtension(
                dir.resolve("git-root"),
                new ForgeGitCredentials(),
                new GitCliExtension.GitCli(git.toString(), 2, 40),
                timeoutSec,
                timeoutSec);
    }

    private static GitSource source() {
        String url = "https://example.invalid/org/repo.git";
        return GitSource.of(url, url, new GitRefSpec.Branch("main"));
    }

    private Path script(String name, String body) throws IOException {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "POSIX shell required");
        Path p = dir.resolve(name);
        Files.writeString(p, body);
        assertThat(p.toFile().setExecutable(true)).isTrue();
        return p;
    }

    private static void killRecorded(Path pidFile) throws IOException {
        if (!Files.isRegularFile(pidFile)) return;
        String pid = Files.readString(pidFile).strip();
        if (pid.isEmpty()) return;
        ProcessHandle.of(Long.parseLong(pid)).ifPresent(h -> {
            h.descendants().forEach(ProcessHandle::destroyForcibly);
            h.destroyForcibly();
        });
    }
}
