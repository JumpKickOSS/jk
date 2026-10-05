// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.mvn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.compat.JkBuildRenderer;
import cc.jumpkick.compat.ProjectImport;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.gradle.GradleBuildImport;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.EnvDecl;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.NodeTable;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.TestFailureMode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** frontend-maven-plugin imported as a node build: side by side in its module, or a generated node module. */
class PomFrontendImportTest {

    private final FrontendCollector frontends = new FrontendCollector();

    private static final String PLUGIN_HEAD = """
              <plugin>
                <groupId>com.github.eirslett</groupId>
                <artifactId>frontend-maven-plugin</artifactId>
                <version>2.0.2</version>
            """;

    @Test
    void a_war_module_s_frontend_is_a_side_by_side_build_writing_into_the_war(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("project"));
        Path front = Files.createDirectories(project.resolve("src/main/frontend"));
        write(front.resolve("package.json"), "{ \"name\": \"ui\", \"packageManager\": \"npm@11.6.0\" }");
        write(front.resolve("package-lock.json"), "{}");
        write(front.resolve("vite.config.js"), "export default { build: { outDir: \"../webapp/app\" } };\n");
        write(project.resolve("pom.xml"), pom("shop", "war", "", PLUGIN_HEAD + """
                    <configuration>
                      <workingDirectory>src/main/frontend</workingDirectory>
                      <npmRegistryURL>https://nexus.corp/npm/</npmRegistryURL>
                      <serverId>nexus</serverId>
                      <npmInheritsProxyConfigFromMaven>false</npmInheritsProxyConfigFromMaven>
                      <yarnVersion>v1.22.22</yarnVersion>
                      <testFailureIgnore>true</testFailureIgnore>
                      <environmentVariables><API_URL>/api</API_URL></environmentVariables>
                    </configuration>
                    <executions>
                      <execution><id>install node</id><goals><goal>install-node-and-npm</goal></goals>
                        <configuration><nodeVersion>v24.21.0</nodeVersion></configuration></execution>
                      <execution><id>npm ci</id><goals><goal>npm</goal></goals>
                        <configuration><arguments>ci</arguments></configuration></execution>
                      <execution><id>npm build</id><goals><goal>npm</goal></goals><phase>generate-resources</phase>
                        <configuration><arguments>run build</arguments></configuration></execution>
                      <execution><id>lint</id><goals><goal>npm</goal></goals><phase>test</phase>
                        <configuration><arguments>run lint</arguments></configuration></execution>
                      <execution><id>Licenses</id><goals><goal>npx</goal></goals><phase>prepare-package</phase>
                        <configuration><arguments>license-checker --summary</arguments></configuration></execution>
                      <execution><id>karma</id><goals><goal>karma</goal></goals></execution>
                    </executions>
                  </plugin>
                """));

        PomImporter.WorkspaceImportResult result = importing(tmp).importWorkspace(project.resolve("pom.xml"));
        JkBuild build = result.root();
        NodeTable node = build.node();

        assertThat(build.project().nodeSpec().requiredVersion()).isEqualTo("24.21.0");
        assertThat(node.dir()).isEqualTo("src/main/frontend");
        assertThat(node.out()).isEqualTo("dist");
        assertThat(node.webappRoot()).isEqualTo("app");
        assertThat(node.classpathRoot()).isNull();
        assertThat(node.build()).as("the build script is the default").isNull();
        assertThat(node.install()).as("npm ci is the frozen install jk runs").isNull();
        assertThat(node.steps())
                .extracting(NodeTable.Step::name, s -> s.command().kind(), NodeTable.Step::before, NodeTable.Step::tier)
                .containsExactly(
                        tuple("lint", NodeTable.Command.Kind.RUN, NodeTable.Before.TEST, NodeTable.Tier.TEST),
                        tuple("licenses", NodeTable.Command.Kind.NPX, NodeTable.Before.PACKAGE, NodeTable.Tier.BUILD));
        assertThat(build.build().testFailures()).isEqualTo(TestFailureMode.REPORT);
        assertThat(build.build().env().vars()).containsExactly(new EnvDecl.Set("API_URL", "/api"));
        assertThat(messages(result.report()))
                .anyMatch(m -> m.contains("[node] registry = \"https://nexus.corp/npm/\"") && m.contains("config.toml"))
                .anyMatch(m -> m.contains("<serverId>nexus</serverId>"))
                .anyMatch(m -> m.contains("npmInheritsProxyConfigFromMaven"))
                .anyMatch(m -> m.contains("goal `karma`") && m.contains("not imported"))
                .noneMatch(m -> m.contains("yarnVersion"));
        assertThat(frontends.files().rewrites())
                .containsEntry(front.resolve("vite.config.js"), "export default { build: { outDir: \"dist\" } };\n");

        String rendered = JkBuildRenderer.render(build);
        JkBuild reparsed = JkBuildParser.parse(rendered);
        assertThat(reparsed.node()).isEqualTo(node);
        assertThat(reparsed.project().nodeSpec()).isEqualTo(build.project().nodeSpec());
        assertThat(reparsed.build().env()).isEqualTo(build.build().env());
    }

    @Test
    void a_frontend_at_the_aggregator_writing_into_the_war_becomes_a_generated_node_module(@TempDir Path tmp)
            throws Exception {
        Path root = jenkinsShaped(tmp);

        PomImporter.WorkspaceImportResult result = importing(tmp).importWorkspace(root.resolve("pom.xml"));

        JkBuild web = Objects.requireNonNull(result.modules().get("web"), "web module");
        assertThat(web.project().name()).isEqualTo("web");
        assertThat(web.project().nodeSpec().requiredVersion()).isEqualTo("24.21.0");
        assertThat(web.node().out()).isEqualTo("dist");
        assertThat(web.node().webappRoot()).isEqualTo("jsbundles");
        assertThat(web.node().install())
                .as("corepack yarn install is yarn's frozen install")
                .isNull();
        assertThat(web.node().build()).isNull();
        assertThat(web.node().test()).isNull();
        assertThat(result.root().workspaceOpt().orElseThrow().modules()).contains("war", "web");
        JkBuild war = Objects.requireNonNull(result.modules().get("war"), "war module");
        assertThat(war.dependencies().byScope().get(Scope.MAIN))
                .extracting(Dependency::module)
                .anyMatch(m -> Dependency.isWorkspaceRef(m) && m.endsWith("web"));
        assertThat(frontends.files().moves())
                .extracting(m -> root.relativize(m.from()).toString(), m -> root.relativize(m.to())
                        .toString())
                .contains(
                        tuple("package.json", "web/package.json"),
                        tuple("yarn.lock", "web/yarn.lock"),
                        tuple(".yarnrc.yml", "web/.yarnrc.yml"),
                        tuple("webpack.config.js", "web/webpack.config.js"),
                        tuple("eslint.config.cjs", "web/eslint.config.cjs"),
                        tuple("src/main/js", "web/src/main/js"),
                        tuple("src/main/scss", "web/src/main/scss"),
                        tuple("src/test/js", "web/src/test/js"),
                        tuple("vitest.config.mjs", "web/vitest.config.mjs"))
                .noneMatch(t -> t.toList().get(0).toString().startsWith("target"))
                .noneMatch(t -> t.toList().get(0).toString().startsWith("war"));
        String config = frontends.files().rewrites().get(root.resolve("web/webpack.config.js"));
        assertThat(config).contains("path.join(__dirname, \"dist\")").doesNotContain("war/src/main/webapp");
    }

    @Test
    void a_dry_run_moves_nothing_and_the_real_import_moves_and_writes(@TempDir Path tmp) throws Exception {
        Path root = jenkinsShaped(tmp);
        PomImporter poms = TestImporters.offline(tmp);

        ProjectImport.Outcome dry = run(poms, root, true);
        assertThat(dry.exit()).isZero();
        assertThat(root.resolve("package.json")).exists();
        assertThat(root.resolve("web")).doesNotExist();
        assertThat(root.resolve("jk.toml")).doesNotExist();

        ProjectImport.Outcome real = run(poms, root, false);
        assertThat(real.exit()).isZero();
        assertThat(root.resolve("package.json")).doesNotExist();
        assertThat(root.resolve("web/package.json")).exists();
        assertThat(root.resolve("web/src/main/js/app.js")).exists();
        assertThat(root.resolve("web/src/test/js/app.test.js")).exists();
        assertThat(root.resolve("src"))
                .as("a source tree the move emptied is gone")
                .doesNotExist();
        assertThat(root.resolve("target/eslint-warnings.xml")).exists();
        assertThat(Files.readString(root.resolve("web/webpack.config.js"))).contains("\"dist\"");
        JkBuild web = JkBuildParser.parse(root.resolve("web/jk.toml"));
        assertThat(web.node().webappRoot()).isEqualTo("jsbundles");
        assertThat(Files.readString(root.resolve("war/jk.toml"))).contains("web");
    }

    @Test
    void a_yarn_one_project_is_refused_with_the_migration(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("project"));
        Path front = Files.createDirectories(project.resolve("src/main/node"));
        write(front.resolve("package.json"), "{ \"name\": \"ui\" }");
        write(front.resolve("yarn.lock"), "# yarn lockfile v1\n");
        write(project.resolve("pom.xml"), pom("shop", "jar", "", PLUGIN_HEAD + """
                    <configuration><workingDirectory>src/main/node</workingDirectory></configuration>
                    <executions><execution><id>y</id><goals><goal>yarn</goal></goals></execution></executions>
                  </plugin>
                """));

        PomImporter.WorkspaceImportResult result = importing(tmp).importWorkspace(project.resolve("pom.xml"));

        assertThat(result.root().node()).isEqualTo(NodeTable.EMPTY);
        assertThat(messages(result.report())).anyMatch(m -> m.contains("Yarn 1") && m.contains("yarn set version"));
    }

    @Test
    void a_skip_property_set_true_skips_the_node_build(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("project"));
        Path front = Files.createDirectories(project.resolve("src/main/node"));
        write(front.resolve("package.json"), "{ \"name\": \"ui\" }");
        write(front.resolve("package-lock.json"), "{}");
        write(
                project.resolve("pom.xml"),
                pom("shop", "jar", "<properties><skip.npm>true</skip.npm></properties>", PLUGIN_HEAD + """
                    <configuration><workingDirectory>src/main/node</workingDirectory></configuration>
                    <executions>
                      <execution><id>b</id><goals><goal>npm</goal></goals><configuration><arguments>run build</arguments>
                        </configuration></execution>
                    </executions>
                  </plugin>
                """));

        PomImporter.WorkspaceImportResult result = importing(tmp).importWorkspace(project.resolve("pom.xml"));

        assertThat(result.root().node().skip()).isTrue();
        assertThat(result.root().node().dir())
                .as("src/main/node is the default")
                .isNull();
        assertThat(JkBuildRenderer.render(result.root())).contains("skip = true");
    }

    /** A Jenkins-shaped reactor: the front end at the pom root, webpack writing into the war module. */
    private static Path jenkinsShaped(Path tmp) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("jenkins"));
        write(
                root.resolve("package.json"),
                "{ \"name\": \"jenkins-ui\", \"packageManager\": \"yarn@4.18.0\","
                        + " \"scripts\": { \"build\": \"webpack\", \"test\": \"vitest\","
                        + " \"lint\": \"stylelint src/main/scss -o target/eslint-warnings.xml\" } }");
        write(root.resolve("yarn.lock"), "__metadata:\n  version: 8\n");
        write(root.resolve(".yarnrc.yml"), "nodeLinker: node-modules\n");
        write(root.resolve("eslint.config.cjs"), "module.exports = [];\n");
        write(root.resolve("webpack.config.js"), """
                const path = require("path");
                module.exports = {
                  entry: { app: [path.join(__dirname, "src/main/js/app.js")] },
                  output: {
                    path: path.join(__dirname, "war/src/main/webapp/jsbundles"),
                    filename: "[name].js",
                  },
                };
                """);
        write(root.resolve("src/main/js/app.js"), "console.log('jenkins');\n");
        write(root.resolve("war/src/main/webapp/index.html"), "<html/>");
        write(
                root.resolve("vitest.config.mjs"),
                "export default { test: { include: [\"src/test/js/**/*.test.js\"] } };\n");
        write(root.resolve("src/test/js/app.test.js"), "test('x', () => {});\n");
        write(root.resolve("src/main/scss/app.scss"), "body {}\n");
        write(root.resolve("target/eslint-warnings.xml"), "<x/>");
        write(root.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.example</groupId>
                  <artifactId>jenkins-parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules><module>war</module></modules>
                  <build><plugins>
                """ + PLUGIN_HEAD + """
                    <inherited>false</inherited>
                    <executions>
                      <execution><id>install node and corepack</id><goals><goal>install-node-and-corepack</goal></goals>
                        <phase>initialize</phase><configuration><nodeVersion>v24.21.0</nodeVersion></configuration>
                      </execution>
                      <execution><id>yarn install</id><goals><goal>corepack</goal></goals><phase>initialize</phase>
                        <configuration><arguments>yarn install</arguments></configuration></execution>
                      <execution><id>yarn build</id><goals><goal>corepack</goal></goals><phase>generate-sources</phase>
                        <configuration><arguments>yarn build</arguments></configuration></execution>
                      <execution><id>yarn test</id><goals><goal>corepack</goal></goals><phase>test</phase>
                        <configuration><arguments>yarn test</arguments></configuration></execution>
                    </executions>
                  </plugin>
                  </plugins></build>
                </project>
                """);
        write(root.resolve("war/pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>org.example</groupId><artifactId>jenkins-parent</artifactId><version>1.0</version></parent>
                  <artifactId>jenkins-war</artifactId>
                  <packaging>war</packaging>
                  <build><finalName>jenkins</finalName></build>
                </project>
                """);
        return root;
    }

    @Test
    void in_place_a_war_module_s_frontend_builds_where_it_stands_and_rewrites_nothing(@TempDir Path tmp)
            throws Exception {
        Path project = Files.createDirectories(tmp.resolve("project"));
        Path front = Files.createDirectories(project.resolve("src/main/frontend"));
        write(front.resolve("package.json"), "{ \"name\": \"ui\", \"packageManager\": \"npm@11.6.0\" }");
        write(front.resolve("package-lock.json"), "{}");
        write(front.resolve("vite.config.js"), "export default { build: { outDir: \"../webapp/app\" } };\n");
        write(project.resolve("pom.xml"), pom("shop", "war", "", PLUGIN_HEAD + """
                    <configuration><workingDirectory>src/main/frontend</workingDirectory></configuration>
                    <executions>
                      <execution><id>install node</id><goals><goal>install-node-and-npm</goal></goals>
                        <configuration><nodeVersion>v24.21.0</nodeVersion></configuration></execution>
                      <execution><id>npm build</id><goals><goal>npm</goal></goals><phase>generate-resources</phase>
                        <configuration><arguments>run build</arguments></configuration></execution>
                    </executions>
                  </plugin>
                """));
        FrontendCollector inPlace = FrontendCollector.inPlace();

        JkBuild build = TestImporters.offline(tmp)
                .frontends(inPlace)
                .importWorkspace(project.resolve("pom.xml"))
                .root();

        NodeTable node = build.node();
        assertThat(node.dir()).isEqualTo("src/main/frontend");
        assertThat(node.out())
                .as("the bundler's own output, read where it writes")
                .isEqualTo("../webapp/app");
        assertThat(node.webappRoot()).isEqualTo("app");
        assertThat(inPlace.files()).isEqualTo(FrontendFiles.NONE);
        assertThat(JkBuildParser.parse(JkBuildRenderer.render(build)).node()).isEqualTo(node);
    }

    @Test
    void in_place_a_frontend_at_the_aggregator_is_built_by_the_war_from_the_root(@TempDir Path tmp) throws Exception {
        Path root = jenkinsShaped(tmp);
        FrontendCollector inPlace = FrontendCollector.inPlace();

        PomImporter.WorkspaceImportResult result =
                TestImporters.offline(tmp).frontends(inPlace).importWorkspace(root.resolve("pom.xml"));

        assertThat(result.modules()).doesNotContainKey("web");
        assertThat(result.root().workspaceOpt().orElseThrow().modules()).doesNotContain("web");
        JkBuild war = Objects.requireNonNull(result.modules().get("war"), "war module");
        NodeTable node = war.node();
        assertThat(war.project().nodeSpec().requiredVersion()).isEqualTo("24.21.0");
        assertThat(node.dir()).isEqualTo("..");
        assertThat(node.out()).isEqualTo("war/src/main/webapp/jsbundles");
        assertThat(node.webappRoot()).isEqualTo("jsbundles");
        assertThat(node.inputs())
                .contains(
                        "package.json",
                        "yarn.lock",
                        ".yarnrc.yml",
                        "webpack.config.js",
                        "src/main/js",
                        "src/main/scss",
                        "src/test/js")
                .noneMatch(in -> in.startsWith("war") || in.startsWith("target") || in.startsWith("core"));
        assertThat(inPlace.files()).as("nothing moves, nothing is rewritten").isEqualTo(FrontendFiles.NONE);
        assertThat(messages(result.report()))
                .anyMatch(m -> m.contains("`war` builds the front end in") && m.contains("where it stands"));
        assertThat(JkBuildParser.parse(JkBuildRenderer.render(war)).node()).isEqualTo(node);
    }

    private PomImporter importing(Path tmp) throws IOException {
        return TestImporters.offline(tmp).frontends(frontends);
    }

    private static ProjectImport.Outcome run(PomImporter poms, Path root, boolean dryRun) {
        return ProjectImport.run(
                poms,
                GradleBuildImport.scannerOnly(),
                root.resolve("pom.xml"),
                root.resolve("jk.toml"),
                root,
                null,
                false,
                dryRun,
                null,
                note -> {},
                ProjectImport.PinRaise.NONE);
    }

    private static String pom(String artifact, String packaging, String extra, String plugins) {
        return """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.ex</groupId>
                  <artifactId>%s</artifactId>
                  <version>1.0.0</version>
                  <packaging>%s</packaging>
                  %s
                  <build><plugins>
                %s
                  </plugins></build>
                </project>
                """.formatted(artifact, packaging, extra, plugins);
    }

    private static List<String> messages(ImportReport report) {
        return report.issues().stream().map(ImportReport.Issue::message).toList();
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }
}
