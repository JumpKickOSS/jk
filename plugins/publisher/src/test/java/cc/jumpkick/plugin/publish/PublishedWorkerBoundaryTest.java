// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.publish;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.DomXml;
import cc.jumpkick.model.JkVersion;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

/** The publisher's shipped worker POM stays on shared, plugin-safe modules. */
class PublishedWorkerBoundaryTest {

    @Test
    void worker_pom_contains_no_server_implementation_module() throws Exception {
        String repoProperty = System.getProperty("jk.worker.repo");
        Assumptions.assumeTrue(
                repoProperty != null, "Gradle stages the worker POM; the self-host test tier has no staging task");
        Path repo = Path.of(repoProperty);
        String artifact = "jk-publisher";
        Path pom = repo.resolve("cc/jumpkick")
                .resolve(artifact)
                .resolve(JkVersion.VERSION)
                .resolve(artifact + "-" + JkVersion.VERSION + ".pom");

        Element project = DomXml.parse(pom).getDocumentElement();
        List<String> firstParty =
                DomXml.childElements(DomXml.childElement(project, "dependencies"), "dependency").stream()
                        .filter(dep -> "cc.jumpkick".equals(DomXml.childText(dep, "groupId")))
                        .map(dep -> DomXml.childText(dep, "artifactId"))
                        .toList();

        assertThat(firstParty)
                .contains("jk-plugin-sdk", "jk-core", "jk-client-io")
                .doesNotContain("jk-io", "jk-engine", "jk-resolver", "jk-toolchain");
    }
}
