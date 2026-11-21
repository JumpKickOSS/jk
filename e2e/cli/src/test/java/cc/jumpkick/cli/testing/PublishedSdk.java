// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.testing;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * The plugin SDK this checkout builds, published the way a release publishes it: {@code jk-host},
 * then {@code jk-plugin-sdk}, each a standalone project shaped like its workspace module and
 * {@code jk publish}ed into one {@code file://} Maven repository. A plugin project that pins the
 * SDK at a version no release has shipped yet resolves it there, the repository standing in for
 * jumpkick.build.
 */
public final class PublishedSdk {

    private PublishedSdk() {}

    /**
     * Publishes the SDK and its one dependency at {@code version} into {@code dir/sdk-repo}, building
     * them in {@code dir}, and returns the repository's URI.
     */
    public static URI publish(Path dir, String version) throws IOException {
        Path root = RepoRoot.find(PublishedSdk.class);
        Path repo = Files.createDirectories(dir.resolve("sdk-repo"));
        URI repoUrl = repo.toUri();
        publish(
                dir.resolve("host"),
                "jk-host",
                "The JDK-only floor every plugin worker shares",
                "[dependencies]\njspecify = \"1.0.1\"\n",
                root.resolve("shared/host"),
                version,
                repoUrl);
        publish(
                dir.resolve("sdk"),
                "jk-plugin-sdk",
                "Plugin SPI",
                "[dependencies]\njk-host = \"cc.jumpkick:jk-host:" + version + "\"\n",
                root.resolve("shared/plugin-sdk"),
                version,
                repoUrl);
        return repoUrl;
    }

    /**
     * A standalone project shaped like the workspace module — same coordinate, description,
     * dependencies and sources — built and published with {@code jk publish}, which builds first.
     * {@code cc.jumpkick} resolves from {@code repoUrl}, where the SDK's own dependency lands first.
     */
    private static void publish(
            Path project, String name, String description, String deps, Path module, String version, URI repoUrl)
            throws IOException {
        copyTree(module.resolve("src/main"), project.resolve("src/main"));
        Files.writeString(project.resolve("jk.toml"), """
                group       = "cc.jumpkick"
                name        = "%s"
                version     = "%s"
                description = "%s"
                java        = 17
                %s
                [repositories.jumpkick]
                url    = "%s"
                groups = ["cc.jumpkick", "cc.jumpkick.*"]
                """.formatted(name, version, description, deps, repoUrl));
        assertThat(run(
                        "publish",
                        "-C",
                        project.toString(),
                        "--repo-url",
                        repoUrl.toString(),
                        "--skip-tests",
                        "--allow-snapshot"))
                .as("jk publish %s %s", name, version)
                .isEqualTo(0);
    }

    /** Copies the tree under {@code from} to {@code to}. */
    public static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else Files.copy(p, target);
            }
        }
    }
}
