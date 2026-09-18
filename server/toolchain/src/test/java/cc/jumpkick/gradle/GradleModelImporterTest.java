// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * The project model Gradle evaluates — every project, its configurations' declared dependencies,
 * its plugins and tasks — imports as a jk workspace: one manifest per module, sibling dependencies
 * as workspace edges, and what has no jk shape as a row naming it.
 */
class GradleModelImporterTest {

    /** A three-module build whose dependencies came through a version catalog, as Gradle reports them. */
    private static final String THREE_MODULES = """
            {"gradle":"9.5.1","rootName":"shop","settingsRepositories":["https://repo.maven.apache.org/maven2/"],
             "projects":[
              {"path":":","projectName":"shop","dir":"","group":"com.acme","version":"1.2.0","plugins":[],"pluginClasses":[],
               "pluginVersions":{},"bootBuildInfo":false,"repositories":[],"configurations":[],
               "tasks":[{"name":"release","type":"org.gradle.api.DefaultTask","group":"publishing"}]},
              {"path":":api","projectName":"api","dir":"api","group":"com.acme","version":"1.2.0",
               "plugins":["java","java-library"],"pluginClasses":[],"pluginVersions":{},
               "java":{"toolchain":21,"sourceCompatibility":"21","targetCompatibility":"21","release":17},"bootBuildInfo":false,
               "repositories":["https://repo.maven.apache.org/maven2/","https://maven.acme.example/releases/","file:/home/dev/.m2/repository/"],
               "sourceSets":[{"name":"main","java":["src/main/java"],"resources":["src/main/resources"],"kotlin":[]}],
               "configurations":[
                 {"name":"api","dependencies":[{"kind":"module","group":"com.google.guava","artifact":"guava","version":"33.4.8-jre","excludes":["com.google.code.findbugs:jsr305"]}],"constraints":[]},
                 {"name":"compileOnly","dependencies":[{"kind":"module","group":"org.projectlombok","artifact":"lombok","version":"1.18.42","excludes":[]}],"constraints":[]},
                 {"name":"annotationProcessor","dependencies":[{"kind":"module","group":"org.projectlombok","artifact":"lombok","version":"1.18.42","excludes":[]}],"constraints":[]},
                 {"name":"implementation","dependencies":[],"constraints":[{"module":"org.slf4j:slf4j-api","version":"2.0.17"}]},
                 {"name":"intTestImplementation","dependencies":[{"kind":"module","group":"org.testcontainers","artifact":"postgresql","version":"1.21.0","excludes":[]}],"constraints":[]}
               ],"tasks":[]},
              {"path":":core","projectName":"core","dir":"core","group":"com.acme","version":"1.2.0",
               "plugins":["java","org.jetbrains.kotlin.jvm"],"pluginClasses":["org.jetbrains.kotlin.gradle.plugin.KotlinPluginWrapper"],
               "pluginVersions":{"org.jetbrains.kotlin.jvm":"2.4.10"},"kotlinVersion":"2.4.10",
               "java":{"sourceCompatibility":"21","targetCompatibility":"21"},"bootBuildInfo":false,"repositories":[],
               "configurations":[
                 {"name":"implementation","dependencies":[{"kind":"project","projectPath":":api"},{"kind":"module","group":"org.junit","artifact":"junit-bom","version":"6.1.3","category":"platform","excludes":[]}],"constraints":[]},
                 {"name":"testImplementation","dependencies":[{"kind":"module","group":"org.junit.jupiter","artifact":"junit-jupiter","version":"","excludes":[]}],"constraints":[]},
                 {"name":"testRuntimeOnly","dependencies":[{"kind":"module","group":"org.junit.platform","artifact":"junit-platform-launcher","version":"","excludes":[]}],"constraints":[]}
               ],"tasks":[{"name":"generateVersionFile","type":"org.gradle.api.tasks.Copy","group":""}]},
              {"path":":app","projectName":"app","dir":"app","group":"com.acme","version":"1.2.0",
               "plugins":["java","application"],"pluginClasses":[],"pluginVersions":{},"java":{"sourceCompatibility":"21","targetCompatibility":"21"},
               "mainClass":"com.acme.App","bootBuildInfo":false,"repositories":[],
               "configurations":[
                 {"name":"implementation","dependencies":[{"kind":"project","projectPath":":core"},{"kind":"module","group":"info.picocli","artifact":"picocli","version":"4.7.7","excludes":[]}],"constraints":[]},
                 {"name":"runtimeOnly","dependencies":[{"kind":"module","group":"ch.qos.logback","artifact":"logback-classic","version":"1.5.18","excludes":[]}],"constraints":[]}
               ],"tasks":[]}
             ]}
            """;

    @Test
    void every_project_is_a_workspace_module_and_the_root_names_them_in_path_order() {
        GradleBuildImport.Result result = GradleModelImporter.importModel(THREE_MODULES, Path.of("/tmp/shop"));

        JkBuild root = result.root();
        assertThat(root.project().name()).isEqualTo("shop");
        assertThat(root.project().group()).isEqualTo("com.acme");
        assertThat(root.project().version()).isEqualTo("1.2.0");
        assertThat(Objects.requireNonNull(root.workspace()).modules()).containsExactly("api", "app", "core");
        assertThat(result.modules().keySet()).containsExactly("api", "app", "core");
        assertThat(result.report().hasErrors())
                .as(messages(result.report()).toString())
                .isFalse();
    }

    @Test
    void configurations_land_in_the_table_that_means_the_same_thing() {
        GradleBuildImport.Result result = GradleModelImporter.importModel(THREE_MODULES, Path.of("/tmp/shop"));
        JkBuild api = module(result, "api");

        Dependency guava = only(api, Scope.MAIN);
        assertThat(guava.module()).isEqualTo("com.google.guava:guava");
        assertThat(guava.version().raw()).isEqualTo("33.4.8-jre");
        assertThat(guava.exclusions()).containsExactly("com.google.code.findbugs:jsr305");
        assertThat(only(api, Scope.PROVIDED).module()).isEqualTo("org.projectlombok:lombok");
        assertThat(only(api, Scope.PROCESSOR).module()).isEqualTo("org.projectlombok:lombok");
        Dependency managed = only(api, Scope.MANAGED);
        assertThat(managed.module()).isEqualTo("org.slf4j:slf4j-api");
        assertThat(managed.version().raw()).isEqualTo("2.0.17");

        JkBuild app = module(result, "app");
        assertThat(only(app, Scope.RUNTIME).module()).isEqualTo("ch.qos.logback:logback-classic");
        assertThat(Objects.requireNonNull(app.application()).main()).isEqualTo("com.acme.App");
    }

    @Test
    void a_project_dependency_is_a_workspace_edge_on_the_sibling() {
        GradleBuildImport.Result result = GradleModelImporter.importModel(THREE_MODULES, Path.of("/tmp/shop"));

        List<Dependency> coreMain = module(result, "core").dependencies().of(Scope.MAIN);
        assertThat(coreMain).hasSize(1);
        assertThat(coreMain.getFirst().isWorkspace()).isTrue();
        assertThat(coreMain.getFirst().library()).isEqualTo("api");
        assertThat(only(module(result, "core"), Scope.PLATFORM).module()).isEqualTo("org.junit:junit-bom");
        assertThat(module(result, "core").dependencies().of(Scope.TEST))
                .extracting(Dependency::module)
                .containsExactly("org.junit.jupiter:junit-jupiter", "org.junit.platform:junit-platform-launcher");
        assertThat(module(result, "core").dependencies().of(Scope.TEST)).allMatch(Dependency::isPlatformManaged);

        List<Dependency> appMain = module(result, "app").dependencies().of(Scope.MAIN);
        assertThat(appMain).extracting(Dependency::library).containsExactly("core", "picocli");
        assertThat(appMain.getFirst().isWorkspace()).isTrue();
    }

    @Test
    void toolchain_release_and_kotlin_version_come_from_the_model() {
        GradleBuildImport.Result result = GradleModelImporter.importModel(THREE_MODULES, Path.of("/tmp/shop"));

        JkBuild api = module(result, "api");
        assertThat(api.project().jdkMajor()).isEqualTo(21);
        assertThat(api.project().java()).isEqualTo(17);
        JkBuild core = module(result, "core");
        assertThat(Objects.requireNonNull(core.project().kotlin()).raw()).isEqualTo("2.4.10");
        assertThat(core.project().java()).isZero();
    }

    @Test
    void custom_tasks_and_configurations_are_rows_naming_them_not_errors() {
        GradleBuildImport.Result result = GradleModelImporter.importModel(THREE_MODULES, Path.of("/tmp/shop"));
        List<String> rows = messages(result.report());

        assertThat(rows).anySatisfy(m -> assertThat(m).contains("`release`").contains("task"));
        assertThat(rows).anySatisfy(m -> assertThat(m).contains("[core]").contains("`generateVersionFile`"));
        assertThat(rows).anySatisfy(m -> assertThat(m)
                .contains("[api]")
                .contains("`intTestImplementation`")
                .contains("org.testcontainers:postgresql"));
        assertThat(result.report().hasErrors()).isFalse();
    }

    @Test
    void a_repository_other_than_central_is_hoisted_onto_the_root() {
        GradleBuildImport.Result result = GradleModelImporter.importModel(THREE_MODULES, Path.of("/tmp/shop"));

        assertThat(result.root().repositories())
                .extracting(r -> r.url().toString())
                .containsExactly("https://maven.acme.example/releases/");
        assertThat(module(result, "api").repositories()).isEmpty();
        assertThat(messages(result.report())).anySatisfy(m -> assertThat(m).contains("mavenLocal()"));
    }

    @Test
    void a_single_project_build_imports_as_one_manifest() {
        String single = """
                {"gradle":"9.5.1","rootName":"widget","settingsRepositories":[],"projects":[
                  {"path":":","projectName":"widget","dir":"","group":"com.acme","version":"0.1.0","plugins":["java"],
                   "pluginClasses":[],"pluginVersions":{},"java":{"sourceCompatibility":"25","targetCompatibility":"25"},"bootBuildInfo":false,"repositories":[],
                   "configurations":[{"name":"implementation","dependencies":[{"kind":"module","group":"com.google.guava","artifact":"guava","version":"33.4.8-jre","excludes":[]}],"constraints":[]}],
                   "tasks":[]}]}
                """;

        GradleBuildImport.Result result = GradleModelImporter.importModel(single, Path.of("/tmp/widget"));

        assertThat(result.modules()).isEmpty();
        assertThat(result.root().isWorkspaceRoot()).isFalse();
        assertThat(result.root().project().name()).isEqualTo("widget");
        assertThat(only(result.root(), Scope.MAIN).module()).isEqualTo("com.google.guava:guava");
    }

    @Test
    void a_boot_library_module_takes_the_boot_bom_as_its_platform_and_an_unknown_plugin_is_a_row() {
        String boot = """
                {"gradle":"9.3.1","rootName":"gs-multi-module","settingsRepositories":[],"projects":[
                  {"path":":","projectName":"gs-multi-module","dir":"","group":"","version":"unspecified","plugins":[],"pluginClasses":[],"pluginVersions":{},"bootBuildInfo":false,"repositories":[],"configurations":[],"tasks":[]},
                  {"path":":library","projectName":"library","dir":"library","group":"com.example","version":"0.0.1-SNAPSHOT",
                   "plugins":["java","io.spring.dependency-management"],"pluginClasses":["io.spring.gradle.dependencymanagement.DependencyManagementPlugin","com.acme.gradle.HousePlugin"],
                   "pluginVersions":{"io.spring.dependency-management":"1.1.7"},"java":{"sourceCompatibility":"17","targetCompatibility":"17"},"bootBuildInfo":false,"repositories":[],
                   "configurations":[{"name":"implementation","dependencies":[{"kind":"module","group":"org.springframework.boot","artifact":"spring-boot","version":"","excludes":[]}],"constraints":[]}],
                   "managedVersions":{"org.springframework.boot:spring-boot":"3.5.11"},
                   "springBootBom":"org.springframework.boot:spring-boot-dependencies:3.5.11","tasks":[]},
                  {"path":":application","projectName":"application","dir":"application","group":"com.example","version":"0.0.1-SNAPSHOT",
                   "plugins":["java","org.springframework.boot","io.spring.dependency-management"],"pluginClasses":["org.springframework.boot.gradle.plugin.SpringBootPlugin"],
                   "pluginVersions":{"org.springframework.boot":"3.5.11","io.spring.dependency-management":"1.1.7"},"java":{"sourceCompatibility":"17","targetCompatibility":"17"},"bootBuildInfo":true,"repositories":[],
                   "configurations":[{"name":"implementation","dependencies":[{"kind":"module","group":"org.springframework.boot","artifact":"spring-boot-starter-web","version":"","excludes":[]},{"kind":"project","projectPath":":library"}],"constraints":[]}],
                   "managedVersions":{"org.springframework.boot:spring-boot-starter-web":"3.5.11"},
                   "springBootBom":"org.springframework.boot:spring-boot-dependencies:3.5.11","tasks":[]}]}
                """;

        GradleBuildImport.Result result = GradleModelImporter.importModel(boot, Path.of("/tmp/gs"));

        JkBuild library = module(result, "library");
        Dependency bom = only(library, Scope.PLATFORM);
        assertThat(bom.module()).isEqualTo("org.springframework.boot:spring-boot-dependencies");
        assertThat(bom.version().raw()).isEqualTo("3.5.11");
        assertThat(only(library, Scope.MAIN).isPlatformManaged()).isTrue();

        JkBuild application = module(result, "application");
        assertThat(application.pluginConfig("spring-boot").orElseThrow().string("version"))
                .isEqualTo("3.5.11");
        assertThat(application.dependencies().of(Scope.PLATFORM))
                .as("the Boot table brings the BOM")
                .isEmpty();
        assertThat(application.build().buildInfo()).isNotNull();
        assertThat(result.root().project().name()).isEqualTo("gs-multi-module");
        assertThat(messages(result.report()))
                .anySatisfy(m -> assertThat(m).contains("[library]").contains("com.acme.gradle.HousePlugin"));
    }

    @Test
    void two_modules_sharing_a_name_are_named_by_their_paths_and_a_row_says_so() {
        String shared = """
                {"gradle":"9.5.1","rootName":"retrofit-root","settingsRepositories":[],"projects":[
                  {"path":":","projectName":"retrofit-root","dir":"","group":"com.squareup.retrofit2","version":"3.0.0","plugins":[],"pluginClasses":[],"pluginVersions":{},"bootBuildInfo":false,"repositories":[],"configurations":[],"tasks":[]},
                  {"path":":retrofit-adapters:guava","projectName":"guava","dir":"retrofit-adapters/guava","group":"com.squareup.retrofit2","version":"3.0.0","plugins":["java"],"pluginClasses":[],"pluginVersions":{},"bootBuildInfo":false,"repositories":[],"configurations":[],"tasks":[]},
                  {"path":":retrofit-converters:guava","projectName":"guava","dir":"retrofit-converters/guava","group":"com.squareup.retrofit2","version":"3.0.0","plugins":["java"],"pluginClasses":[],"pluginVersions":{},"bootBuildInfo":false,"repositories":[],
                   "configurations":[{"name":"implementation","dependencies":[{"kind":"project","projectPath":":retrofit-adapters:guava"}],"constraints":[]}],"tasks":[]},
                  {"path":":retrofit-bom","projectName":"retrofit-bom","dir":"retrofit-bom","group":"com.squareup.retrofit2","version":"3.0.0","plugins":["java-platform"],"pluginClasses":[],"pluginVersions":{},"bootBuildInfo":false,"repositories":[],"configurations":[],"tasks":[]}]}
                """;

        GradleBuildImport.Result result = GradleModelImporter.importModel(shared, Path.of("/tmp/retrofit"));

        assertThat(result.modules().keySet()).containsExactly("retrofit-adapters/guava", "retrofit-converters/guava");
        assertThat(module(result, "retrofit-adapters/guava").project().name()).isEqualTo("retrofit-adapters-guava");
        assertThat(module(result, "retrofit-converters/guava").project().name()).isEqualTo("retrofit-converters-guava");
        Dependency edge = only(module(result, "retrofit-converters/guava"), Scope.MAIN);
        assertThat(edge.isWorkspace()).isTrue();
        assertThat(edge.library()).isEqualTo("retrofit-adapters-guava");
        assertThat(messages(result.report()))
                .anySatisfy(m -> assertThat(m).contains("`guava`").contains("retrofit-adapters/guava"))
                .anySatisfy(m -> assertThat(m).contains("retrofit-bom").contains("java-platform"));
    }

    private static JkBuild module(GradleBuildImport.Result result, String path) {
        return Objects.requireNonNull(result.modules().get(path), path);
    }

    private static Dependency only(JkBuild build, Scope scope) {
        List<Dependency> deps = build.dependencies().of(scope);
        assertThat(deps).as(scope + " of " + build.project().name()).hasSize(1);
        return deps.getFirst();
    }

    private static List<String> messages(ImportReport report) {
        return report.issues().stream().map(ImportReport.Issue::message).toList();
    }
}
