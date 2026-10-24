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
 * The sandbox local repository is its store's pooled {@code repos/central} tree; the sandbox product
 * home stays one per module.
 *
 * <p>Central's jars live in the local repository, and the pool is what keeps a dependency fetched
 * once for every sandbox, so the two are one tree. A product home is state, locks, learned rates and
 * a calibration, which concurrent suites must not share.
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
        return requireNonNull(TestEnv.forModuleUnseeded(project, moduleDir, BuildLayout.of(moduleDir, project))
                .extras()
                .get("JK_M2_LOCAL"));
    }

    private static String homeOf(Path moduleDir) throws Exception {
        JkBuild project = JkBuildParser.parse(moduleDir.resolve("jk.toml"));
        return requireNonNull(TestEnv.forModuleUnseeded(project, moduleDir, BuildLayout.of(moduleDir, project))
                .extras()
                .get("JK_HOME"));
    }

    @Test
    void every_member_of_a_workspace_reads_the_pooled_central_tree_through_its_own_store(@TempDir Path tmp)
            throws Exception {
        Path root = workspace(tmp, "a", "b");
        Path a = member(root, "a");
        Path b = member(root, "b");

        assertThat(Path.of(m2Of(a))).isEqualTo(TestHomes.pathFor(a).resolve("store/repos/central"));
        assertThat(Path.of(m2Of(b))).isEqualTo(TestHomes.pathFor(b).resolve("store/repos/central"));
        assertThat(m2Of(a))
                .as("outside the checkout, like every sandbox path")
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
}
