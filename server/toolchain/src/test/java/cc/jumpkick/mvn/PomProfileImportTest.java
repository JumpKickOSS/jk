// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.compat.JkBuildRenderer;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Profile;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.model.Scope;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Inactive Maven profiles land by payload: dependencies as a feature of optional deps, compiler
 * settings as a jk profile, repositories in the top-level list, plugins as a checklist row, and
 * per-platform dependencies as a variants proposal — one row per payload, each naming the profile.
 */
class PomProfileImportTest {

    @Test
    void each_payload_kind_lands_in_its_own_place(@TempDir Path tempDir) throws Exception {
        Path pom = tempDir.resolve("pom.xml");
        Files.writeString(pom, TestImporters.fixture("profiles", "payloads-pom.xml"), StandardCharsets.UTF_8);
        PomImporter.Result result = TestImporters.offline(tempDir).importFrom(pom);
        JkBuild build = result.jkBuild();
        List<String> messages = result.report().issues().stream()
                .map(ImportReport.Issue::message)
                .toList();

        // docker: dependencies → [features.docker], optional deps in their scopes, not in default.
        assertThat(build.features().byName()).containsOnlyKeys("docker");
        assertThat(requireNonNull(build.features().byName().get("docker")).deps())
                .containsExactly("docker-java", "testcontainers");
        assertThat(build.features().defaults()).isEmpty();
        assertThat(build.dependencies().of(Scope.MAIN))
                .extracting(Dependency::module, Dependency::optional)
                .containsExactly(
                        tuple("org.slf4j:slf4j-api", false), tuple("com.github.docker-java:docker-java", true));
        assertThat(build.dependencies().of(Scope.TEST))
                .as("the profile's own dependencyManagement supplies the version")
                .extracting(d -> d.module() + "=" + d.version().raw(), Dependency::optional)
                .containsExactly(tuple("org.testcontainers:testcontainers=1.20.4", true));
        assertThat(messages)
                .anyMatch(m -> m.startsWith("Maven profile `docker`: 2 dependencies → `[features.docker]`"));

        // jdk21-preview: compiler level + compilerArgs → javac, argLine → jvm-args.
        Profile preview = requireNonNull(build.profiles().byName().get("jdk21-preview"));
        assertThat(preview.javacArgs()).containsExactly("--release", "21", "--enable-preview");
        assertThat(preview.jvmArgs()).containsExactly("--enable-preview");
        assertThat(messages)
                .anyMatch(m -> m.startsWith("Maven profile `jdk21-preview` (activation property=preview")
                        && m.contains("→ `[profiles.jdk21-preview]` `javac` and `jvm-args`"));

        // internal: repositories merge into the top-level list.
        assertThat(build.repositories()).extracting(RepositorySpec::name).containsExactly("internal");
        assertThat(messages)
                .anyMatch(m -> m.startsWith("Maven profile `internal`: `<repositories>` internal → merged"));

        // release: plugins stay a checklist.
        assertThat(messages).anyMatch(m -> m.startsWith("Maven profile `release`: plugins=[maven-gpg-plugin]"));

        // jdk8-native: per-platform deps are a variants proposal, and nothing is written for them.
        assertThat(messages)
                .anyMatch(m -> m.startsWith("Maven profile `jdk8-native` (activation jdk=[1.8,9)): per-platform"
                        + " dependencies io.netty:netty-transport-native-epoll:linux-x86_64 → declare a `[variants]`"));
        assertThat(build.dependencies().of(Scope.MAIN))
                .noneMatch(d -> d.module().startsWith("io.netty"));
        assertThat(build.features().byName()).doesNotContainKey("jdk8-native");

        String rendered = JkBuildRenderer.render(build);
        assertThat(rendered)
                .contains("[features.docker]\ndeps = [\"docker-java\", \"testcontainers\"]")
                .contains(
                        "[profiles.jdk21-preview]\njavac = [\"--release\", \"21\", \"--enable-preview\"]\njvm-args = [\"--enable-preview\"]")
                .contains("optional = true");
        JkBuild reparsed = JkBuildParser.parse(rendered);
        assertThat(requireNonNull(reparsed.features().byName().get("docker")).deps())
                .containsExactly("docker-java", "testcontainers");
        assertThat(requireNonNull(reparsed.profiles().byName().get("jdk21-preview"))
                        .javacArgs())
                .containsExactly("--release", "21", "--enable-preview");
        assertThat(reparsed.dependencies().of(Scope.MAIN))
                .filteredOn(Dependency::optional)
                .hasSize(1);
        assertThat(reparsed.repositories()).extracting(RepositorySpec::name).containsExactly("internal");
    }

    /**
     * A dependency an inactive profile declares without a version takes the one the POM's effective
     * {@code dependencyManagement} supplies, a parent's table or an imported BOM included, since
     * that is what governs it once the profile is active; {@code unresolved} is never written when
     * the chain has the version.
     */
    @Test
    void an_inactive_profiles_dependency_takes_the_version_the_poms_management_supplies(@TempDir Path tempDir)
            throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>app</artifactId>
                  <version>0.1.0</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>com.h2database</groupId>
                        <artifactId>h2</artifactId>
                        <version>2.3.232</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                  <profiles>
                    <profile>
                      <id>product</id>
                      <activation><property><name>product</name></property></activation>
                      <dependencies>
                        <dependency>
                          <groupId>com.h2database</groupId>
                          <artifactId>h2</artifactId>
                          <scope>test</scope>
                        </dependency>
                      </dependencies>
                    </profile>
                  </profiles>
                </project>
                """);

        JkBuild build = result.jkBuild();
        assertThat(build.dependencies().of(Scope.TEST))
                .extracting(d -> d.module() + "=" + d.version().raw(), Dependency::optional)
                .containsExactly(tuple("com.h2database:h2=2.3.232", true));
        assertThat(JkBuildRenderer.render(build)).doesNotContain("unresolved");
        assertThat(TestImporters.messages(result)).noneMatch(m -> m.contains("`=unresolved`"));
    }

    /**
     * A profile that re-declares a dependency the POM already carries, without a version, is Maven's
     * merge of the two rows — the version stays the declared one — so the profile's optional copy
     * takes that version instead of {@code unresolved}, and the lock has something to resolve.
     */
    @Test
    void a_profiles_versionless_copy_of_a_declared_dependency_takes_the_declared_version(@TempDir Path tempDir)
            throws Exception {
        PomImporter.Result result = TestImporters.importXml(tempDir, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>app</artifactId>
                  <version>0.1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-cloud-connectors</artifactId>
                      <version>2.2.13.RELEASE</version>
                    </dependency>
                  </dependencies>
                  <profiles>
                    <profile>
                      <id>cloudfoundry</id>
                      <dependencies>
                        <dependency>
                          <groupId>org.springframework.boot</groupId>
                          <artifactId>spring-boot-starter-cloud-connectors</artifactId>
                        </dependency>
                      </dependencies>
                    </profile>
                  </profiles>
                </project>
                """);

        JkBuild build = result.jkBuild();
        assertThat(build.dependencies().of(Scope.MAIN))
                .extracting(d -> d.module() + "=" + d.version().raw(), Dependency::optional)
                .containsExactly(
                        tuple("org.springframework.boot:spring-boot-starter-cloud-connectors=2.2.13.RELEASE", false),
                        tuple("org.springframework.boot:spring-boot-starter-cloud-connectors=2.2.13.RELEASE", true));
        assertThat(JkBuildRenderer.render(build)).doesNotContain("unresolved");
    }
}
