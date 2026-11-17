// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.compat.BuildTool;
import cc.jumpkick.discovery.MiseProbe;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.Processes;
import cc.jumpkick.testing.FakePrograms;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NodeDiscoveryTest {

    /** A Node home with its launcher, as each manager lays it out. */
    private static Path node(Path home) throws IOException {
        Path launcher = BuildTool.NODE.launcher(home);
        Files.createDirectories(launcher.getParent());
        Files.writeString(launcher, "");
        return home;
    }

    private static NodeDiscovery discovery(Map<String, String> env, Path home, List<Path> cellars) {
        return new NodeDiscovery(env::get, home, cellars);
    }

    @Test
    void every_manager_s_installs_are_found_by_their_directory_names(@TempDir Path home) throws IOException {
        node(home.resolve(".nvm/versions/node/v24.20.0"));
        node(home.resolve("data/fnm/node-versions/v22.22.0/installation"));
        node(home.resolve(".volta/tools/image/node/24.1.0"));
        node(home.resolve("data/mise/installs/node/26.1.0"));
        node(home.resolve(".asdf/installs/nodejs/20.19.0"));
        node(home.resolve("cellar/node@22/22.21.1"));
        node(home.resolve("cellar/node/26.0.0"));
        Files.createDirectories(home.resolve(".nvm/versions/node/v24.0.0")); // no launcher: not an install
        Files.createDirectories(home.resolve("cellar/nodenv/1.0.0"));

        Map<String, String> env =
                Map.of(MiseProbe.DATA_HOME_ENV, home.resolve("data").toString());
        List<DiscoveredNode> found =
                discovery(env, home, List.of(home.resolve("cellar"))).discover();

        assertThat(found)
                .extracting(DiscoveredNode::source, DiscoveredNode::version)
                .containsExactlyInAnyOrder(
                        tuple("nvm", "24.20.0"),
                        tuple("fnm", "22.22.0"),
                        tuple("volta", "24.1.0"),
                        tuple("mise", "26.1.0"),
                        tuple("asdf", "20.19.0"),
                        tuple("brew", "22.21.1"),
                        tuple("brew", "26.0.0"));
    }

    @Test
    void the_managers_own_directory_variables_win(@TempDir Path home) throws IOException {
        node(home.resolve("custom-nvm/versions/node/v24.21.0"));
        node(home.resolve("custom-fnm/node-versions/v24.20.0/installation"));
        Map<String, String> env = new HashMap<>();
        env.put("NVM_DIR", home.resolve("custom-nvm").toString());
        env.put("FNM_DIR", home.resolve("custom-fnm").toString());

        assertThat(discovery(env, home, List.of()).discover())
                .extracting(DiscoveredNode::version)
                .containsExactlyInAnyOrder("24.21.0", "24.20.0");
    }

    @Test
    void a_suggestion_takes_the_newest_install_of_its_major_and_a_pin_only_its_own(@TempDir Path home)
            throws IOException {
        node(home.resolve(".nvm/versions/node/v24.20.0"));
        node(home.resolve(".nvm/versions/node/v24.3.0"));
        node(home.resolve(".nvm/versions/node/v22.22.0"));
        NodeDiscovery d = discovery(Map.of(), home, List.of());

        assertThat(d.find(NodeSpec.parse("24"), List.of()))
                .get()
                .extracting(DiscoveredNode::version)
                .isEqualTo("24.20.0");
        assertThat(d.find(NodeSpec.parse("=24.0.0"), List.of())).isEmpty();
        assertThat(d.find(NodeSpec.parse("=24.3.0"), List.of()))
                .get()
                .extracting(DiscoveredNode::version)
                .isEqualTo("24.3.0");
    }

    @Test
    void the_node_on_path_answers_its_own_version(@TempDir Path home) throws IOException {
        Path bin = Files.createDirectories(home.resolve("usr/bin"));
        Path node = FakePrograms.executable(
                bin.resolve(BuildTool.NODE.binaryName()), FakePrograms.Script.printing("v22.1.0"));

        assertFoundOnPath(home, bin, node);
    }

    @Test
    void a_node_on_path_answering_after_the_old_two_seconds_is_still_found(@TempDir Path home) throws IOException {
        // A first start on a busy Windows machine takes seconds: an answer at three, past the two the
        // probe once allowed, is kept. The probe is a stand-in, so no test waits on a real slow start.
        Path bin = Files.createDirectories(home.resolve("usr/bin"));
        Path node = FakePrograms.executable(
                bin.resolve(BuildTool.NODE.binaryName()), FakePrograms.Script.printing("v0.0.0"));
        List<Duration> deadlines = new ArrayList<>();

        List<DiscoveredNode> found = new NodeDiscovery(
                        Map.of("PATH", bin.toString())::get,
                        home,
                        List.of(),
                        answeringAfter(Duration.ofSeconds(3), node, deadlines))
                .discover();

        assertThat(deadlines).containsExactly(NodeDiscovery.VERSION_PROBE);
        assertThat(found)
                .singleElement()
                .extracting(DiscoveredNode::source, DiscoveredNode::version)
                .containsExactly("system", "22.1.0");
    }

    @Test
    void a_node_on_path_with_no_answer_by_the_deadline_is_left_out(@TempDir Path home) throws IOException {
        Path bin = Files.createDirectories(home.resolve("usr/bin"));
        Path node = FakePrograms.executable(
                bin.resolve(BuildTool.NODE.binaryName()), FakePrograms.Script.printing("v0.0.0"));
        List<Duration> deadlines = new ArrayList<>();

        List<DiscoveredNode> found = new NodeDiscovery(
                        Map.of("PATH", bin.toString())::get,
                        home,
                        List.of(),
                        answeringAfter(NodeDiscovery.VERSION_PROBE.plusMillis(1), node, deadlines))
                .discover();

        assertThat(deadlines).containsExactly(NodeDiscovery.VERSION_PROBE);
        assertThat(found).isEmpty();
    }

    /**
     * A {@code node} that answers {@code --version} with {@code v22.1.0} after {@code delay} on a
     * virtual clock: what {@link Processes#outputIfOk} returns given a deadline, without waiting.
     */
    private static NodeDiscovery.VersionProbe answeringAfter(Duration delay, Path node, List<Duration> deadlines) {
        return (command, deadline) -> {
            assertThat(command).containsExactly(node.toString(), "--version");
            deadlines.add(deadline);
            return delay.compareTo(deadline) <= 0 ? "v22.1.0\n" : null;
        };
    }

    private static void assertFoundOnPath(Path home, Path bin, Path node) {
        List<DiscoveredNode> found =
                discovery(Map.of("PATH", bin.toString()), home, List.of()).discover();

        assertThat(found).as(() -> answer(node)).singleElement().satisfies(d -> {
            assertThat(d.source()).isEqualTo("system");
            assertThat(d.version()).isEqualTo("22.1.0");
            assertThat(d.home()).isEqualTo(Os.isWindows() ? bin : home.resolve("usr"));
        });
    }

    /** What {@code node --version} does given a minute: why discovery, given far less, left it out. */
    private static String answer(Path node) {
        long start = System.nanoTime();
        try {
            Processes.Captured c = Processes.capture(List.of(node.toString(), "--version"), Duration.ofMinutes(1));
            return "%s --version (runnable: %s) answered in %d ms, exit ok: %s, output: [%s]; discovery waits %s"
                    .formatted(
                            node,
                            PathUtil.isRunnable(node),
                            (System.nanoTime() - start) / 1_000_000,
                            c.ok(),
                            c.output().strip(),
                            NodeDiscovery.VERSION_PROBE);
        } catch (IOException e) {
            return node + " --version could not start: " + e;
        }
    }
}
