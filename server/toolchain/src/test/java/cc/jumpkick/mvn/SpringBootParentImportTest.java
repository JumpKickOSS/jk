// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compat.JkBuildRenderer;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.model.Scope;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The real {@code spring-boot-starter-parent} chain from Maven Central imports with every version resolved. */
@Tag("network")
class SpringBootParentImportTest {

    private static final String BOOT = "3.5.5";

    @Test
    void spring_boot_child_imports_with_every_version_resolved(@TempDir Path tempDir) throws Exception {
        Path pom = tempDir.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>%s</version>
                    <relativePath/>
                  </parent>
                  <groupId>com.ex</groupId>
                  <artifactId>demo</artifactId>
                  <version>0.0.1-SNAPSHOT</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-web</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-test</artifactId>
                      <scope>test</scope>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(BOOT));

        Cas cas = new Cas(tempDir.resolve("cache"));
        MavenRepo central = new MavenRepo(
                RepositorySpec.MAVEN_CENTRAL.name(),
                RepositorySpec.MAVEN_CENTRAL.url(),
                new Http(),
                cas,
                RepoCredential.ANONYMOUS,
                false);
        PomImporter.Result result = new PomImporter(RepoGroup.of(central), cas).importFrom(pom);
        JkBuild build = result.jkBuild();

        assertThat(JkBuildRenderer.render(build)).doesNotContain("${").doesNotContain("unresolved");
        assertThat(build.project().java()).isEqualTo(17);
        assertThat(build.build().javac().args())
                .as("the Boot parent's <parameters>true</parameters> is the flag the MVC tests bind by")
                .contains("-parameters");
        assertThat(build.dependencies().of(Scope.MAIN))
                .extracting(Dependency::module, Dependency::isPlatformManaged)
                .as("the starter's version stays the parent platform's")
                .containsExactly(tuple("org.springframework.boot:spring-boot-starter-web", true));
        assertThat(build.dependencies().of(Scope.TEST))
                .extracting(Dependency::module, Dependency::isPlatformManaged)
                .containsExactly(tuple("org.springframework.boot:spring-boot-starter-test", true));
        assertThat(JkBuildRenderer.render(build))
                .contains("spring-boot-starter-web = \"managed\"\n")
                .contains("spring-boot-starter-test = \"managed\"\n");
        assertThat(build.dependencies().of(Scope.PLATFORM))
                .extracting(Dependency::module)
                .containsExactly("org.springframework.boot:spring-boot-starter-parent");
        assertThat(result.report().hasErrors()).isFalse();
    }
}
