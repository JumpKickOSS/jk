// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.Scope;
import cc.jumpkick.resolver.Versions;
import java.util.List;
import org.junit.jupiter.api.Test;

class BomExporterTest {

    @Test
    void renders_dependency_management_for_main_scope() {
        JkBuild project = JkBuild.of(new Project("com.example", "demo", "1.2.3", 25));
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
        String xml = BomExporter.render(project, lock, BomExporter.MAIN_SCOPES, Versions::compare);
        assertThat(xml).contains("<artifactId>demo-bom</artifactId>");
        assertThat(xml).contains("<groupId>com.example</groupId>");
        assertThat(xml).contains("<version>1.2.3</version>");
        assertThat(xml).contains("<artifactId>widget</artifactId>");
        assertThat(xml).contains("<version>1.0</version>");
        assertThat(xml).doesNotContain("testonly");
    }

    @Test
    void test_scope_includes_test_artifacts() {
        JkBuild project = JkBuild.of(new Project("com.example", "demo", "1.0.0", 25));
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
        String xml = BomExporter.render(project, lock, BomExporter.TEST_SCOPES, Versions::compare);
        assertThat(xml).contains("junit-jupiter");
        assertThat(xml).contains("5.10.0");
    }

    /**
     * Two test-scope rows of one module at different versions collapse to the higher one, and
     * "higher" is a version order, not a string order: {@code 1.10.0} outranks {@code 1.9.0}.
     */
    @Test
    void a_dual_keeps_the_higher_version_by_version_order() {
        JkBuild project = JkBuild.of(new Project("com.example", "demo", "1.2.3", 25));
        Lockfile lock = new Lockfile(
                1,
                "jk test",
                Lockfile.RESOLUTION_ALGORITHM,
                List.of(
                        new Lockfile.Artifact(
                                "com.foo:widget:jar:",
                                "1.10.0",
                                "central+",
                                "sha256:aa",
                                "widget-1.10.0.jar",
                                List.of(Scope.TEST),
                                List.of()),
                        new Lockfile.Artifact(
                                "com.foo:widget:jar:",
                                "1.9.0",
                                "central+",
                                "sha256:bb",
                                "widget-1.9.0.jar",
                                List.of(Scope.TEST),
                                List.of())));
        String xml = BomExporter.render(project, lock, BomExporter.TEST_SCOPES, Versions::compare);
        assertThat(xml).contains("<version>1.10.0</version>");
        assertThat(xml).doesNotContain("<version>1.9.0</version>");
    }
}
