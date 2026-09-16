// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.compat.JkBuildRenderer;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A dependency's {@code <classifier>} and {@code <type>} reach the manifest: a classified artifact
 * is its own entry with {@code classifier = "…"}, a {@code pom} is a platform, a {@code test-jar}
 * is the tests kind, and a type jk cannot spell is a Tier-3 row instead of a wrong jar.
 */
class PomClassifierImportTest {

    private static final String POM = """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>org.demo</groupId>
              <artifactId>game</artifactId>
              <version>1.0.0</version>
              <dependencies>
                <dependency>
                  <groupId>org.lwjgl</groupId>
                  <artifactId>lwjgl</artifactId>
                  <version>3.3.6</version>
                </dependency>
                <dependency>
                  <groupId>org.lwjgl</groupId>
                  <artifactId>lwjgl</artifactId>
                  <version>3.3.6</version>
                  <classifier>natives-linux</classifier>
                  <scope>runtime</scope>
                </dependency>
                <dependency>
                  <groupId>jakarta.ejb</groupId>
                  <artifactId>beans</artifactId>
                  <version>2.0</version>
                  <type>ejb-client</type>
                </dependency>
                <dependency>
                  <groupId>org.demo</groupId>
                  <artifactId>deps</artifactId>
                  <version>1.0.0</version>
                  <type>pom</type>
                </dependency>
                <dependency>
                  <groupId>androidx.core</groupId>
                  <artifactId>core-ktx</artifactId>
                  <version>1.16.0</version>
                  <type>aar</type>
                </dependency>
                <dependency>
                  <groupId>com.acme</groupId>
                  <artifactId>helpers</artifactId>
                  <version>1.2.3</version>
                  <type>test-jar</type>
                  <scope>test</scope>
                </dependency>
              </dependencies>
            </project>
            """;

    @Test
    void classifier_and_type_land_in_the_manifest(@TempDir Path tempDir) throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, POM);
        JkBuild build = result.jkBuild();
        List<String> messages = TestImporters.messages(result);

        Dependency lwjgl = only(build.dependencies().of(Scope.MAIN), "lwjgl");
        assertThat(lwjgl.classifier()).isNull();
        assertThat(lwjgl.packageKey()).isEqualTo("org.lwjgl:lwjgl:jar:");

        Dependency natives = only(build.dependencies().of(Scope.RUNTIME), "lwjgl-natives-linux");
        assertThat(natives.module()).isEqualTo("org.lwjgl:lwjgl");
        assertThat(natives.classifier()).isEqualTo("natives-linux");
        assertThat(natives.packageKey()).isEqualTo("org.lwjgl:lwjgl:jar:natives-linux");
        assertThat(messages).noneMatch(m -> m.contains("<classifier>"));

        Dependency client = only(build.dependencies().of(Scope.MAIN), "beans-client");
        assertThat(client.classifier()).as("the type implies the classifier").isEqualTo("client");

        assertThat(build.dependencies().of(Scope.PLATFORM))
                .extracting(Dependency::module)
                .containsExactly("org.demo:deps");
        assertThat(messages).anyMatch(m -> m.startsWith("`<type>pom</type>` on org.demo:deps"));

        assertThat(build.dependencies().byScope().values())
                .allSatisfy(deps -> assertThat(deps).noneMatch(d -> d.module().equals("androidx.core:core-ktx")));
        assertThat(result.report().issues())
                .anyMatch(i -> i.severity() == ImportReport.Severity.ERROR
                        && i.message().startsWith("`<type>aar</type>` on androidx.core:core-ktx"));

        assertThat(build.dependencies().of(Scope.TEST))
                .anyMatch(d -> d.isTestsKind() && d.packageKey().equals("com.acme:helpers:test-jar:tests"));
    }

    @Test
    void the_rendered_manifest_reads_back_with_the_classifier(@TempDir Path tempDir) throws Exception {
        String toml =
                JkBuildRenderer.render(TestImporters.importXml(tempDir, POM).jkBuild());
        assertThat(toml)
                .contains("lwjgl-natives-linux = { group = \"org.lwjgl\", name = \"lwjgl\", version = \"3.3.6\","
                        + " classifier = \"natives-linux\" }");
        Dependency natives = only(JkBuildParser.parse(toml).dependencies().of(Scope.RUNTIME), "lwjgl-natives-linux");
        assertThat(natives.classifier()).isEqualTo("natives-linux");
    }

    private static Dependency only(List<Dependency> deps, String library) {
        List<Dependency> hits =
                deps.stream().filter(d -> d.library().equals(library)).toList();
        assertThat(hits).as("one entry named " + library).hasSize(1);
        return hits.getFirst();
    }
}
