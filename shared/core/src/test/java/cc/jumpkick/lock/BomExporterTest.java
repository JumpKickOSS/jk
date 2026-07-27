// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import java.util.List;
import org.junit.jupiter.api.Test;

class BomExporterTest {

    @Test
    void renders_dependency_management_for_main_scope() {
        JkBuild project = JkBuild.of(new JkBuild.Project("com.example", "demo", "1.2.3", 25));
        Lockfile lock = new Lockfile(
                1,
                "jk test",
                Lockfile.RESOLUTION_ALGORITHM,
                List.of(
                        new Lockfile.Artifact(
                                "com.foo:widget:jar:",
                                "1.0",
                                "central+",
                                "sha256:aa",
                                "widget-1.0.jar",
                                List.of(Scope.MAIN),
                                List.of()),
                        new Lockfile.Artifact(
                                "com.foo:testonly:jar:",
                                "2.0",
                                "central+",
                                "sha256:bb",
                                "testonly-2.0.jar",
                                List.of(Scope.TEST),
                                List.of())));
        String xml = BomExporter.render(project, lock, BomExporter.MAIN_SCOPES);
        assertThat(xml).contains("<artifactId>demo-bom</artifactId>");
        assertThat(xml).contains("<groupId>com.example</groupId>");
        assertThat(xml).contains("<version>1.2.3</version>");
        assertThat(xml).contains("<artifactId>widget</artifactId>");
        assertThat(xml).contains("<version>1.0</version>");
        assertThat(xml).doesNotContain("testonly");
    }

    @Test
    void test_scope_includes_test_artifacts() {
        JkBuild project = JkBuild.of(new JkBuild.Project("com.example", "demo", "1.0.0", 25));
        Lockfile lock = new Lockfile(
                1,
                "jk test",
                Lockfile.RESOLUTION_ALGORITHM,
                List.of(new Lockfile.Artifact(
                        "org.junit.jupiter:junit-jupiter:jar:",
                        "5.10.0",
                        "central+",
                        null,
                        null,
                        List.of(Scope.TEST),
                        List.of())));
        String xml = BomExporter.render(project, lock, BomExporter.TEST_SCOPES);
        assertThat(xml).contains("junit-jupiter");
        assertThat(xml).contains("5.10.0");
    }
}
