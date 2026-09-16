// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a reactor's identities become in a workspace: two leaves sharing an artifactId across groups
 * are reported, Tier 3 when a member's edge would be ambiguous.
 */
class PomReactorIdentityImportTest {

    /** thingsboard's shape: {@code common/edqs} and {@code edqs}, with {@code application} depending on one of them. */
    @Test
    void two_leaves_sharing_an_artifact_id_with_a_dependent_are_a_tier_3_row_naming_both_paths(@TempDir Path root)
            throws Exception {
        write(root, "pom.xml", parent(List.of("common/edqs", "edqs", "application")));
        write(root, "common/edqs/pom.xml", leaf("edqs", "org.tb.common", ""));
        write(root, "edqs/pom.xml", leaf("edqs", null, ""));
        write(root, "application/pom.xml", leaf("application", null, """
                <dependencies>
                  <dependency>
                    <groupId>org.tb.common</groupId>
                    <artifactId>edqs</artifactId>
                    <version>4.4.0</version>
                  </dependency>
                </dependencies>
                """));

        PomImporter.WorkspaceImportResult result = TestImporters.offline(root).importWorkspace(root.resolve("pom.xml"));

        assertThat(requireNonNull(result.root().workspace()).modules())
                .containsExactly("common/edqs", "edqs", "application");
        assertThat(result.report().issues())
                .filteredOn(i -> i.severity() == ImportReport.Severity.ERROR)
                .extracting(ImportReport.Issue::message)
                .singleElement()
                .asString()
                .contains("`common/edqs` and `edqs` both carry the name `edqs` (org.tb.common:edqs, org.tb:edqs)")
                .contains("`application` depend on it")
                .contains("edqs.workspace = true");
        JkBuild application = requireNonNull(result.modules().get("application"));
        assertThat(application.dependencies().of(Scope.MAIN))
                .as("the edge is written by name, never resolved to one of the two silently")
                .singleElement()
                .satisfies(d -> assertThat(d.workspaceName()).isEqualTo("edqs"));
    }

    @Test
    void two_leaves_sharing_an_artifact_id_nobody_depends_on_are_a_tier_2_row(@TempDir Path root) throws Exception {
        write(root, "pom.xml", parent(List.of("common/edqs", "edqs")));
        write(root, "common/edqs/pom.xml", leaf("edqs", "org.tb.common", ""));
        write(root, "edqs/pom.xml", leaf("edqs", null, ""));

        PomImporter.WorkspaceImportResult result = TestImporters.offline(root).importWorkspace(root.resolve("pom.xml"));

        assertThat(result.report().hasErrors()).isFalse();
        assertThat(result.report().issues())
                .extracting(ImportReport.Issue::message)
                .anySatisfy(m -> assertThat(m)
                        .contains("`common/edqs` and `edqs` both carry the name `edqs`")
                        .contains("nothing is ambiguous"));
    }

    private static String parent(List<String> modules) {
        String list =
                modules.stream().map(m -> "    <module>" + m + "</module>\n").reduce("", String::concat);
        return """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.tb</groupId>
                  <artifactId>parent</artifactId>
                  <version>4.4.0</version>
                  <packaging>pom</packaging>
                  <modules>
                %s  </modules>
                </project>
                """.formatted(list);
    }

    /** A leaf under the root parent; {@code group} null inherits {@code org.tb}. */
    private static String leaf(String artifactId, @Nullable String group, String body) {
        String groupLine = group == null ? "" : "  <groupId>" + group + "</groupId>\n";
        return """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>org.tb</groupId>
                    <artifactId>parent</artifactId>
                    <version>4.4.0</version>
                  </parent>
                %s  <artifactId>%s</artifactId>
                %s</project>
                """.formatted(groupLine, artifactId, body);
    }

    private static void write(Path root, String path, String xml) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(requireNonNull(file.getParent()));
        Files.writeString(file, xml);
    }
}
