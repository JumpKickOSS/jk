// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.testing.RepoRoot;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The POM {@code jk install} writes for jk's own image worker manages its whole runtime closure
 * from this tree's lock, so the worker it launches can open an HTTP connection to a registry.
 * Built from another member's lock rows it lost commons-logging, and every {@code jk image} on an
 * installed jk died in the worker.
 */
class ImageWorkerInstallPomTest {

    @Test
    void the_installed_image_worker_pom_carries_the_http_client_closure() throws Exception {
        Path root = RepoRoot.find(ImageWorkerInstallPomTest.class);
        Path module = root.resolve("plugins/image-builder");
        JkBuild project = JkBuildParser.parse(module.resolve("jk.toml"));
        BuildLayout layout = BuildLayout.of(root, module, project);

        String pom = new String(InstallPlans.renderedPomBytes(project, layout), StandardCharsets.UTF_8);

        assertThat(pom)
                .contains("<artifactId>httpclient</artifactId>")
                .as("httpclient logs through commons-logging; the worker cannot open a connection without it")
                .contains("<artifactId>commons-logging</artifactId>");
    }
}
