// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a suite of jk's own may not touch: the real home its sandbox was made under (less the
 * sandboxes and the shared pool), the checkout's git directories, a {@code git} pointed at the
 * checkout; and how a failure names them.
 */
class HermeticReadsTest {

    @Test
    void the_real_home_is_the_one_holding_the_outermost_test_homes(@TempDir Path tmp) {
        Path home = tmp.resolve(".jk");
        Path sandbox = home.resolve("test-homes/0a1b2c/home");
        assertThat(HermeticReads.realHome(sandbox.toString())).isEqualTo(home);
        assertThat(HermeticReads.realHome(
                        sandbox.resolve("test-homes/3d4e5f/home").toString()))
                .as("a fixture's sandbox nests inside the suite's")
                .isEqualTo(home);
        Path spaced = tmp.resolve("jk home");
        assertThat(HermeticReads.realHome(spaced.resolve("test-homes/9f/home").toString()))
                .isEqualTo(spaced);
        assertThat(HermeticReads.realHome(home.toString()))
                .as("no sandbox: nothing to fence")
                .isNull();
        assertThat(HermeticReads.realHome(null)).isNull();
    }

    @Test
    void the_real_home_is_fenced_and_its_sandboxes_and_pool_are_not(@TempDir Path tmp) {
        Path home = tmp.resolve("jk home");
        HermeticReads.Fence fence = Objects.requireNonNull(HermeticReads.fence(
                HermeticReadsTest.class, home.resolve("test-homes/0a1b2c/home").toString()));

        assertThat(fence.denies(home.resolve("state/engine/0e14fb085585546e.endpoint")))
                .isTrue();
        assertThat(fence.denies(home.resolve("store/repos/google/x.jk"))).isTrue();
        assertThat(fence.deniesRead(home.resolve("store/repos/google/x.jk")))
                .as("read on purpose until JK-S589D, never written")
                .isFalse();
        assertThat(fence.deniesRead(home.resolve("state/engine/0e14fb085585546e.endpoint")))
                .isTrue();
        assertThat(fence.denies(home.resolve("test-homes/0a1b2c/home/store/x.jar")))
                .isFalse();
        assertThat(fence.denies(home.resolve("test-store/repos/central/x.jar"))).isFalse();
        assertThat(fence.denies(tmp.resolve("elsewhere/x"))).isFalse();
        Path otherAgent = Path.of(System.getProperty("user.home"), "other-agent", "home");
        assertThat(fence.denies(otherAgent.resolve(".jk/state/engine/093eae34f78ecfba.endpoint")))
                .as("another home's engine")
                .isTrue();
        assertThat(fence.denies(home.resolve("test-homes/0a1b2c/tmp/t1/home/state/engine/k.endpoint")))
                .as("an engine the test started in its own slot")
                .isFalse();
    }

    @Test
    void outside_a_sandbox_nothing_is_fenced(@TempDir Path tmp) {
        assertThat(HermeticReads.fence(
                        HermeticReadsTest.class, tmp.resolve(".jk").toString()))
                .isNull();
    }

    @Test
    void a_linked_worktree_fences_its_own_git_dir_and_the_common_one(@TempDir Path tmp) throws Exception {
        Path common = Files.createDirectories(tmp.resolve("main/.git"));
        Path own = Files.createDirectories(common.resolve("worktrees/wt"));
        Files.writeString(own.resolve("commondir"), "../..\n");
        Path tree = Files.createDirectories(tmp.resolve("wt"));
        Files.writeString(tree.resolve(".git"), "gitdir: " + own + "\n");

        assertThat(HermeticReads.gitDirs(tree)).containsExactly(own, common);
        assertThat(HermeticReads.gitDirs(tmp.resolve("main"))).containsExactly(common);
        assertThat(HermeticReads.gitDirs(tmp)).isEmpty();
    }

    @Test
    void a_git_aimed_at_the_checkout_is_fenced_and_one_aimed_elsewhere_is_not(@TempDir Path tmp) {
        Path checkout = tmp.resolve("jk");
        HermeticReads.Fence fence = new HermeticReads.Fence(List.of(), List.of(), List.of(), List.of(), checkout);

        assertThat(fence.deniesGit(checkout.resolve("server/engine").toString(), "git rev-parse HEAD"))
                .isTrue();
        assertThat(fence.deniesGit(tmp.toString(), "/usr/bin/git -C " + checkout + " status"))
                .isTrue();
        assertThat(fence.deniesGit(checkout.toString(), "git -C " + tmp.resolve("fixture") + " status"))
                .as("-C moves it out of the checkout")
                .isFalse();
        assertThat(fence.deniesGit(checkout.toString(), "ls -la")).isFalse();
        assertThat(HermeticReads.isGit("C:\\Program Files\\Git\\cmd\\git.exe log"))
                .isTrue();
    }

    /** A keychain tool started from a test JVM is caught wherever it runs (JK-PB8M1). */
    @Test
    void a_keychain_tool_is_an_offence_and_other_commands_are_not() {
        assertThat(HermeticReads.isKeychainTool("secret-tool lookup service jk account repo/central"))
                .isTrue();
        assertThat(HermeticReads.isKeychainTool("/usr/bin/security find-generic-password -s jk -a repo/x -w"))
                .isTrue();
        assertThat(HermeticReads.isKeychainTool("busctl --user get-property org.freedesktop.secrets"
                        + " /org/freedesktop/secrets/aliases/default org.freedesktop.Secret.Collection Locked"))
                .isTrue();
        assertThat(HermeticReads.isKeychainTool("gdbus call --session --dest org.freedesktop.secrets"))
                .isTrue();
        assertThat(HermeticReads.isKeychainTool("busctl --user status")).isFalse();
        assertThat(HermeticReads.isKeychainTool("git -C /w status")).isFalse();
        assertThat(HermeticReads.isKeychainTool("/opt/secret-tools/bin/java -version"))
                .isFalse();
        assertThat(HermeticReads.isKeychainTool("")).isFalse();
    }

    /** A refusal the sandbox kept fails the class that made it, even when the code swallowed it. */
    @Test
    void a_kept_keychain_refusal_names_the_class_and_what_it_asked() {
        assertThat(HermeticReads.keychain("a.BTest", List.of())).isNull();
        String message = HermeticReads.keychain(
                "a.BTest",
                List.of(
                        "asked the real Secret Service while JK_KEYCHAIN_SANDBOX is set",
                        "asked the real Secret Service while JK_KEYCHAIN_SANDBOX is set"));
        assertThat(message)
                .startsWith("a.BTest reached for the user's OS keychain")
                .contains("JK_KEYCHAIN_SANDBOX")
                .containsOnlyOnce("\n  asked the real Secret Service");
    }

    @Test
    void the_failure_names_each_path_with_the_frames_that_reached_it() {
        String message = HermeticReads.message(
                "a.BTest", Map.of("read /h/.jk/state/engine/k.endpoint", "EngineFleet.list:180 <- BTest.lists:12"));

        assertThat(message)
                .startsWith("a.BTest reached outside its sandbox")
                .contains("\n  read /h/.jk/state/engine/k.endpoint\n      at EngineFleet.list:180 <- BTest.lists:12");
    }

    /**
     * Only the three watched types are judged, and a field the event does not carry reads as absent:
     * the JVM-wide stream also sees the suite recording's socket events, which have no path, and a
     * read of a missing field threw on the JFR thread, whose stack landed in a test's stderr.
     */
    @Test
    void an_event_without_a_path_or_of_another_type_is_not_an_offence_and_never_throws(@TempDir Path tmp) {
        Path home = tmp.resolve("jk home");
        HermeticReads.Fence fence = Objects.requireNonNull(HermeticReads.fence(
                HermeticReadsTest.class, home.resolve("test-homes/0a1b2c/home").toString()));
        String endpoint = home.resolve("state/engine/k.endpoint").toString();

        assertThat(HermeticReads.judged(fence, "jdk.FileRead", name -> null)).isNull();
        assertThat(HermeticReads.judged(fence, "jdk.SocketRead", name -> "path".equals(name) ? endpoint : null))
                .as("not a watched type")
                .isNull();
        assertThat(HermeticReads.judged(fence, "jdk.FileRead", name -> "path".equals(name) ? endpoint : null))
                .isEqualTo("read " + Path.of(endpoint).toAbsolutePath().normalize());
        assertThat(HermeticReads.judged(fence, "jdk.FileWrite", name -> {
                    throw new IllegalArgumentException("Attempt to get unknown field \"" + name + "\"");
                }))
                .as("a failure is the guard's own report, not an exception")
                .startsWith("guard error on a jdk.FileWrite event")
                .contains("unknown field \"path\"");
    }
}
