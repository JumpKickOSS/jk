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

    @Test
    void the_failure_names_each_path_with_the_frames_that_reached_it() {
        String message = HermeticReads.message(
                "a.BTest", Map.of("read /h/.jk/state/engine/k.endpoint", "EngineFleet.list:180 <- BTest.lists:12"));

        assertThat(message)
                .startsWith("a.BTest reached outside its sandbox")
                .contains("\n  read /h/.jk/state/engine/k.endpoint\n      at EngineFleet.list:180 <- BTest.lists:12");
    }
}
