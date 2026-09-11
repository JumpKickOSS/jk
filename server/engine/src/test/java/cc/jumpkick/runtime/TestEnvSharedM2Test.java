// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.runtime.base.TestEnv;
import cc.jumpkick.util.TestHomes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The sandbox local-m2 is one per workspace; the sandbox product home stays one per module.
 *
 * <p>An m2 is a content-addressed artifact cache with nothing module-specific in it, so a copy per
 * module fetches and stores the same dependency once per module. A product home is state, locks,
 * learned rates and a calibration, which concurrent suites must not share.
 *
 * <p>Both live outside the project under test ({@code TestHomes}); these assertions pin the sharing
 * rule at that location.
 */
class TestEnvSharedM2Test {

    private static void manifest(Path dir, String body) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("jk.toml"), body);
    }

    private static Path workspace(Path tmp, String... members) throws Exception {
        String list = Arrays.stream(members).map(m -> '"' + m + '"').collect(Collectors.joining(", "));
        manifest(tmp, """
                name = "ws"
                group = "ex"
                version = "1.0"

                [workspace]
                modules = [%s]
                """.formatted(list));
        return tmp;
    }

    private static Path member(Path root, String rel) throws Exception {
        Path dir = root.resolve(rel);
        manifest(dir, """
                name = "%s"
                group = "ex"
                version = "1.0"
                """.formatted(rel.replace('/', '-')));
        return dir;
    }

    private static String m2Of(Path moduleDir) throws Exception {
        JkBuild project = JkBuildParser.parse(moduleDir.resolve("jk.toml"));
        return requireNonNull(TestEnv.forModule(project, moduleDir, BuildLayout.of(moduleDir, project))
                .extras()
                .get("JK_M2_LOCAL"));
    }

    private static String homeOf(Path moduleDir) throws Exception {
        JkBuild project = JkBuildParser.parse(moduleDir.resolve("jk.toml"));
        return requireNonNull(TestEnv.forModule(project, moduleDir, BuildLayout.of(moduleDir, project))
                .extras()
                .get("JK_HOME"));
    }

    @Test
    void every_member_of_a_workspace_shares_one_local_m2(@TempDir Path tmp) throws Exception {
        Path root = workspace(tmp, "a", "b");
        Path a = member(root, "a");
        Path b = member(root, "b");

        assertThat(m2Of(a)).isEqualTo(m2Of(b));
        assertThat(Path.of(m2Of(a)))
                .as("in the workspace's own slot, not either member's")
                .isEqualTo(TestHomes.slotFor(root).resolve("test-m2"));
        assertThat(m2Of(a))
                .as("and outside the checkout, like every sandbox path now")
                .doesNotStartWith(root.toAbsolutePath().toString());
    }

    /** The product home is the opposite call: per module, because it holds state that races. */
    @Test
    void each_member_still_gets_its_own_product_home(@TempDir Path tmp) throws Exception {
        Path root = workspace(tmp, "a", "b");
        Path a = member(root, "a");
        Path b = member(root, "b");

        assertThat(homeOf(a)).isNotEqualTo(homeOf(b));
    }

    /** No workspace to share with: the cache is the module's own, exactly as before. */
    @Test
    void a_standalone_project_keeps_a_local_cache(@TempDir Path tmp) throws Exception {
        Path solo = member(tmp, "solo");

        assertThat(Path.of(m2Of(solo))).isEqualTo(TestHomes.slotFor(solo).resolve("test-m2"));
    }

    /** Proximity is not membership — an unlisted project nested under a workspace keeps its own. */
    @Test
    void a_project_nested_under_a_workspace_but_unlisted_keeps_its_own(@TempDir Path tmp) throws Exception {
        Path root = workspace(tmp, "a");
        member(root, "a");
        Path stranger = member(root, "target/fixture");

        assertThat(Path.of(m2Of(stranger)))
                .isEqualTo(TestHomes.slotFor(stranger).resolve("test-m2"));
    }
}
