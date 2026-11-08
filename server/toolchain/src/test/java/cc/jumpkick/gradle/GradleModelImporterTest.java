// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.ImportReport;
import cc.jumpkick.kotlin.KotlinResolver;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.NullnessConfig;
import cc.jumpkick.model.Scope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The project model Gradle evaluates — every project, its configurations' declared dependencies,
 * its plugins and tasks — imports as a jk workspace: one manifest per module, sibling dependencies
 * as workspace edges, and what has no jk shape as a row naming it.
 */
class GradleModelImporterTest {

    /** A three-module build whose dependencies came through a version catalog, as Gradle reports them. */
    private static final String THREE_MODULES = """
            {"gradle":"9.5.1","rootName":"shop","settingsRepositories":[{"url":"https://repo.maven.apache.org/maven2/","name":"MavenRepo"}],
             "projects":[
              {"path":":","projectName":"shop","dir":"","group":"com.acme","version":"1.2.0","plugins":[],"pluginClasses":[],
               "pluginVersions":{},"bootBuildInfo":false,"repositories":[],"configurations":[],
               "tasks":[{"name":"release","type":"org.gradle.api.DefaultTask","group":"publishing"}]},
              {"path":":api","projectName":"api","dir":"api","group":"com.acme","version":"1.2.0",
               "plugins":["java","java-library"],"pluginClasses":[],"pluginVersions":{},
               "java":{"toolchain":21,"sourceCompatibility":"21","targetCompatibility":"21","release":17},"bootBuildInfo":false,
               "repositories":[{"url":"https://repo.maven.apache.org/maven2/","name":"MavenRepo"},{"url":"https://maven.acme.example/releases/","name":"maven"},{"url":"file:/home/dev/.m2/repository/","name":"MavenLocal"}],
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
        GradleBuildImport.Result result =
                GradleModelImporter.importModel(THREE_MODULES, Path.of("/tmp/shop"), RefreshVersions.NONE);

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
        GradleBuildImport.Result result =
                GradleModelImporter.importModel(THREE_MODULES, Path.of("/tmp/shop"), RefreshVersions.NONE);
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
        GradleBuildImport.Result result =
                GradleModelImporter.importModel(THREE_MODULES, Path.of("/tmp/shop"), RefreshVersions.NONE);

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
        GradleBuildImport.Result result =
                GradleModelImporter.importModel(THREE_MODULES, Path.of("/tmp/shop"), RefreshVersions.NONE);

        JkBuild api = module(result, "api");
        // A toolchain the host JDK compiles to with --release is a language level, not a JDK pin.
        assertThat(api.project().jdkMajor()).isZero();
        assertThat(result.root().project().jdkMajor()).isZero();
        assertThat(api.project().java()).isEqualTo(17);
        JkBuild core = module(result, "core");
        assertThat(Objects.requireNonNull(core.project().kotlin()).raw()).isEqualTo("2.4.10");
        assertThat(core.project().java()).isZero();
    }

    @Test
    void custom_tasks_and_configurations_are_rows_naming_them_not_errors() {
        GradleBuildImport.Result result =
                GradleModelImporter.importModel(THREE_MODULES, Path.of("/tmp/shop"), RefreshVersions.NONE);
        List<String> rows = messages(result.report());

        assertThat(rows)
                .anySatisfy(m -> assertThat(m).contains("task release (").contains("not translated"));
        assertThat(rows).anySatisfy(m -> assertThat(m).contains("core").contains("task generateVersionFile ("));
        assertThat(rows).anySatisfy(m -> assertThat(m)
                .contains("api")
                .contains("configuration intTestImplementation has no jk table")
                .contains("org.testcontainers:postgresql"));
        assertThat(result.report().hasErrors()).isFalse();
    }

    @Test
    void a_repository_other_than_central_is_hoisted_onto_the_root() {
        GradleBuildImport.Result result =
                GradleModelImporter.importModel(THREE_MODULES, Path.of("/tmp/shop"), RefreshVersions.NONE);

        assertThat(result.root().repositories())
                .extracting(r -> r.url().toString())
                .containsExactly("https://maven.acme.example/releases/");
        assertThat(result.root().repositories().getFirst().name()).isEqualTo("repo1");
        assertThat(module(result, "api").repositories()).isEmpty();
        assertThat(messages(result.report())).anySatisfy(m -> assertThat(m).contains("mavenLocal()"));
        assertThat(result.machineCredentials()).isEmpty();
    }

    /**
     * A named repository keeps its name, its {@code content { includeGroup }} filter is its
     * {@code groups}, and a credential the build reads from an environment variable is a
     * {@code ~/.jk/config.toml} table naming the variable.
     */
    @Test
    void a_named_repository_keeps_its_name_groups_and_the_variable_its_credential_reads() {
        String model = THREE_MODULES.replace(
                "{\"url\":\"https://maven.acme.example/releases/\",\"name\":\"maven\"}",
                "{\"url\":\"https://gitlab.example/api/v4/projects/7/packages/maven\",\"name\":\"GitLab\","
                        + "\"groups\":[\"com.acme.internal\"],\"unmappedContent\":[\"com.other:lib\"],"
                        + "\"credential\":{\"kind\":\"header\",\"secretEnv\":\"ACME_PULL_TOKEN\"}}");

        GradleBuildImport.Result result =
                GradleModelImporter.importModel(model, Path.of("/tmp/shop"), RefreshVersions.NONE);

        assertThat(result.root().repositories()).singleElement().satisfies(r -> {
            assertThat(r.name()).isEqualTo("gitlab");
            assertThat(r.url()).hasToString("https://gitlab.example/api/v4/projects/7/packages/maven/");
            assertThat(r.groups()).containsExactly("com.acme.internal");
            assertThat(r.credentialOpt()).isEmpty();
        });
        assertThat(result.machineCredentials()).singleElement().satisfies(t -> {
            assertThat(t.name()).isEqualTo("gitlab");
            assertThat(t.url()).hasToString("https://gitlab.example/api/v4/projects/7/packages/maven/");
            assertThat(t.credential()).containsExactly(Map.entry("token", "${env:ACME_PULL_TOKEN}"));
        });
        assertThat(messages(result.report()))
                .contains("gitlab: credential added to ~/.jk/config.toml", "on CI, set JK_REPO_<ID>_* for gitlab")
                .anySatisfy(m -> assertThat(m).contains("com.other:lib"));
    }

    /** {@code mavenContent { releasesOnly() }} is {@code snapshots = false}. */
    @Test
    void a_releases_only_repository_is_asked_for_no_snapshots() {
        String model = THREE_MODULES.replace(
                "{\"url\":\"https://maven.acme.example/releases/\",\"name\":\"maven\"}",
                "{\"url\":\"https://maven.acme.example/releases/\",\"name\":\"acme\",\"snapshots\":false,"
                        + "\"credential\":{\"kind\":\"header\",\"secretEnv\":\"ACME_TOKEN\"}}");

        GradleBuildImport.Result result =
                GradleModelImporter.importModel(model, Path.of("/tmp/shop"), RefreshVersions.NONE);

        assertThat(result.root().repositories()).singleElement().satisfies(r -> {
            assertThat(r.releases()).isTrue();
            assertThat(r.snapshots()).isFalse();
        });
        assertThat(messages(result.report())).contains("on CI, set JK_REPO_<ID>_* for acme");
    }

    /** A credential no environment variable holds is a row asking for a login, and nothing is written for it. */
    @Test
    void a_credential_no_variable_holds_is_a_row_not_a_table() {
        String model = THREE_MODULES.replace(
                "{\"url\":\"https://maven.acme.example/releases/\",\"name\":\"maven\"}",
                "{\"url\":\"https://maven.acme.example/releases/\",\"name\":\"acme\","
                        + "\"credential\":{\"kind\":\"password\",\"username\":\"ci\"}}");

        GradleBuildImport.Result result =
                GradleModelImporter.importModel(model, Path.of("/tmp/shop"), RefreshVersions.NONE);

        assertThat(result.machineCredentials()).isEmpty();
        assertThat(messages(result.report())).anySatisfy(m -> assertThat(m).contains("jk repo login acme"));
    }

    /** A BOM two configurations import is one platform row: jk's platform table serves every scope. */
    @Test
    void a_platform_two_configurations_import_is_one_row() {
        String model = THREE_MODULES.replace(
                "{\"name\":\"testImplementation\",\"dependencies\":[",
                "{\"name\":\"testImplementation\",\"dependencies\":[{\"kind\":\"module\",\"group\":\"org.junit\","
                        + "\"artifact\":\"junit-bom\",\"version\":\"6.1.3\",\"category\":\"platform\",\"excludes\":[]},");

        GradleBuildImport.Result result =
                GradleModelImporter.importModel(model, Path.of("/tmp/shop"), RefreshVersions.NONE);

        assertThat(only(module(result, "core"), Scope.PLATFORM).module()).isEqualTo("org.junit:junit-bom");
    }

    /** A toolchain above the host JDK, or one naming a vendor, is a JDK the build needs: a `jdk` pin. */
    @Test
    void a_toolchain_the_host_cannot_stand_in_for_is_a_jdk_pin() {
        int above = Runtime.version().feature() + 1;
        String model = THREE_MODULES.replace("\"toolchain\":21,", "\"toolchain\":" + above + ",");

        GradleBuildImport.Result result =
                GradleModelImporter.importModel(model, Path.of("/tmp/shop"), RefreshVersions.NONE);
        assertThat(result.root().project().jdkMajor()).isEqualTo(above);
        assertThat(module(result, "api").project().jdkMajor())
                .as("inherited from the root")
                .isZero();

        String vendor = THREE_MODULES.replace("\"toolchain\":21,", "\"toolchain\":21,\"vendor\":\"GRAAL_VM\",");
        GradleBuildImport.Result graal =
                GradleModelImporter.importModel(vendor, Path.of("/tmp/shop"), RefreshVersions.NONE);
        assertThat(graal.root().project().jdkMajor()).isEqualTo(21);
    }

    /** A member does not repeat the `java` its workspace root declares. */
    @Test
    void a_member_inherits_the_roots_java_level() {
        GradleBuildImport.Result result =
                GradleModelImporter.importModel(THREE_MODULES, Path.of("/tmp/shop"), RefreshVersions.NONE);

        assertThat(result.root().project().java()).isEqualTo(21);
        assertThat(module(result, "app").project().java()).isZero();
        assertThat(module(result, "api").project().java()).isEqualTo(17);
    }

    /** The errorprone plugin's NullAway wiring, as Gradle hands it to javac. */
    private static final String NULLAWAY_ARGS = """
            "compilerArgs":{"compileJava":["-parameters","-Xplugin:ErrorProne -XepDisableAllChecks -Xep:NullAway:ERROR -XepOpt:NullAway:OnlyNullMarked=true -XepOpt:NullAway:JSpecifyMode=true","-XDcompilePolicy=simple","--should-stop=ifError=FLOW","-XDaddTypeAnnotationsToSymbol=true","--release","17"],
                            "compileTestJava":["-parameters"]},
            """;

    private static final String ERRORPRONE_CONFIGURATION = """
            {"name":"errorprone","dependencies":[{"kind":"module","group":"com.google.errorprone","artifact":"error_prone_core","version":"2.50.0","excludes":[]},{"kind":"module","group":"com.uber.nullaway","artifact":"nullaway","version":"0.14.2","excludes":[]}],"constraints":[]},
            """;

    /** javac arguments land in [javac] args, and NullAway run alone through Error Prone becomes [nullness]. */
    @Test
    void compiler_args_are_javac_args_and_nullaway_through_error_prone_is_nullness() {
        String model = THREE_MODULES
                .replace(
                        "\"repositories\":[{\"url\":\"https://repo.maven.apache.org",
                        NULLAWAY_ARGS.strip() + "\"repositories\":[{\"url\":\"https://repo.maven.apache.org")
                .replace("{\"name\":\"api\",", ERRORPRONE_CONFIGURATION.strip() + "{\"name\":\"api\",");

        GradleBuildImport.Result result =
                GradleModelImporter.importModel(model, Path.of("/tmp/shop"), RefreshVersions.NONE);
        JkBuild api = module(result, "api");

        assertThat(api.build().javac().args()).containsExactly("-parameters");
        assertThat(api.build().javac().test())
                .as("compile-test runs the same args once the wiring is gone")
                .isNull();
        NullnessConfig nullness = Objects.requireNonNull(api.build().nullness());
        assertThat(nullness.include()).isEmpty();
        assertThat(nullness.severity()).isEqualTo(NullnessConfig.Severity.ERROR);
        assertThat(nullness.tests()).isFalse();
        assertThat(api.dependencies().of(Scope.PROCESSOR))
                .extracting(Dependency::module)
                .containsExactly("org.projectlombok:lombok");
    }

    /** Error Prone running a check beside NullAway keeps its plugin argument and its jars; no [nullness]. */
    @Test
    void error_prone_running_other_checks_keeps_its_wiring() {
        String model = THREE_MODULES
                .replace(
                        "\"repositories\":[{\"url\":\"https://repo.maven.apache.org",
                        NULLAWAY_ARGS
                                        .strip()
                                        .replace(
                                                "-Xep:NullAway:ERROR", "-Xep:NullAway:ERROR -Xep:MissingOverride:ERROR")
                                + "\"repositories\":[{\"url\":\"https://repo.maven.apache.org")
                .replace("{\"name\":\"api\",", ERRORPRONE_CONFIGURATION.strip() + "{\"name\":\"api\",");

        JkBuild api = module(GradleModelImporter.importModel(model, Path.of("/tmp/shop"), RefreshVersions.NONE), "api");

        assertThat(api.build().nullness()).isNull();
        assertThat(api.build().javac().args())
                .anySatisfy(a -> assertThat(a).startsWith("-Xplugin:ErrorProne").contains("-Xep:MissingOverride:ERROR"))
                .contains("-parameters", "-XDcompilePolicy=simple")
                .doesNotContain("--release", "17");
        assertThat(api.dependencies().of(Scope.PROCESSOR))
                .extracting(Dependency::module)
                .contains("com.google.errorprone:error_prone_core", "com.uber.nullaway:nullaway");
    }

    /** JSpecify and an @NullMarked package with no checker is a row suggesting [nullness]. */
    @Test
    void jspecify_and_null_marked_packages_without_a_checker_suggest_nullness(@TempDir Path root) throws Exception {
        Path info = root.resolve("api/src/main/java/com/acme/api/package-info.java");
        Files.createDirectories(info.getParent());
        Files.writeString(info, "@NullMarked\npackage com.acme.api;\n\nimport org.jspecify.annotations.NullMarked;\n");
        String model = THREE_MODULES.replace(
                "{\"name\":\"api\",\"dependencies\":[",
                "{\"name\":\"api\",\"dependencies\":[{\"kind\":\"module\",\"group\":\"org.jspecify\","
                        + "\"artifact\":\"jspecify\",\"version\":\"1.0.1\",\"excludes\":[]},");

        GradleBuildImport.Result result = GradleModelImporter.importModel(model, root, RefreshVersions.NONE);

        assertThat(messages(result.report())).anySatisfy(m -> assertThat(m)
                .isEqualTo("api: @NullMarked code but no null checker; add [nullness] include = [] to check it"));
        assertThat(messages(GradleModelImporter.importModel(THREE_MODULES, root, RefreshVersions.NONE)
                        .report()))
                .as("no JSpecify, no row")
                .noneSatisfy(m -> assertThat(m).contains("[nullness]"));
    }

    /** Without the errorprone plugin the arguments are only [javac] args, and the tool jars stay out. */
    @Test
    void compiler_args_without_error_prone_are_only_javac_args() {
        String model = THREE_MODULES.replace(
                "\"repositories\":[{\"url\":\"https://repo.maven.apache.org",
                "\"compilerArgs\":{\"compileJava\":[\"-parameters\",\"-Xlint:all\"],\"compileTestJava\":[\"-parameters\"]},"
                        + "\"repositories\":[{\"url\":\"https://repo.maven.apache.org");

        JkBuild api = module(GradleModelImporter.importModel(model, Path.of("/tmp/shop"), RefreshVersions.NONE), "api");

        assertThat(api.build().javac().args()).containsExactly("-parameters", "-Xlint:all");
        assertThat(Objects.requireNonNull(api.build().javac().test()).args()).containsExactly("-parameters");
        assertThat(api.build().nullness()).isNull();
    }

    /** The test task's JVM arguments and system properties are [test] jvm-args and system-properties. */
    @Test
    void the_test_tasks_jvm_settings_are_the_test_jvm() {
        String model = THREE_MODULES.replace(
                "\"repositories\":[{\"url\":\"https://repo.maven.apache.org",
                "\"test\":{\"systemProperties\":{\"user.timezone\":\"America/Denver\"},"
                        + "\"jvmArgs\":[\"--enable-native-access=ALL-UNNAMED\"]},"
                        + "\"repositories\":[{\"url\":\"https://repo.maven.apache.org");

        JkBuild api = module(GradleModelImporter.importModel(model, Path.of("/tmp/shop"), RefreshVersions.NONE), "api");

        assertThat(api.build().testJvm().jvmArgs()).containsExactly("--enable-native-access=ALL-UNNAMED");
        assertThat(api.build().testJvm().systemProperties())
                .containsExactly(Map.entry("user.timezone", "America/Denver"));
    }

    /** A module is named by the artifact it publishes, siblings follow, and the POM's name and description come along. */
    @Test
    void a_module_is_named_by_its_published_artifact_id() {
        String model = THREE_MODULES.replace(
                "\"repositories\":[{\"url\":\"https://repo.maven.apache.org",
                "\"archivesName\":\"shop-api\",\"publications\":[{\"name\":\"library\",\"groupId\":\"com.acme\","
                        + "\"artifactId\":\"shop-api\",\"pomName\":\"Shop API\",\"pomDescription\":\"The shop's API.\"}],"
                        + "\"repositories\":[{\"url\":\"https://repo.maven.apache.org");

        GradleBuildImport.Result result =
                GradleModelImporter.importModel(model, Path.of("/tmp/shop"), RefreshVersions.NONE);
        JkBuild api = module(result, "api");

        assertThat(api.project().name()).isEqualTo("shop-api");
        assertThat(api.project().description()).isEqualTo("The shop's API.");
        assertThat(Objects.requireNonNull(api.publish()).name()).isEqualTo("Shop API");
        assertThat(module(result, "core")
                        .dependencies()
                        .of(Scope.MAIN)
                        .getFirst()
                        .library())
                .isEqualTo("shop-api");
        assertThat(Objects.requireNonNull(result.root().workspace()).modules()).containsExactly("api", "app", "core");
    }

    /** A module on a Kotlin below jk's floor imports at the floor, with a row saying so. */
    @Test
    void a_kotlin_below_jks_floor_comes_out_at_the_floor_with_a_row() {
        String model = THREE_MODULES.replace("\"kotlinVersion\":\"2.4.10\"", "\"kotlinVersion\":\"2.2.21\"");

        GradleBuildImport.Result result =
                GradleModelImporter.importModel(model, Path.of("/tmp/shop"), RefreshVersions.NONE);

        JkBuild core = module(result, "core");
        assertThat(Objects.requireNonNull(core.project().kotlin()).raw()).isEqualTo(KotlinResolver.FLOOR_VERSION);
        assertThat(result.report().issues())
                .extracting(ImportReport.Issue::message)
                .anySatisfy(m -> assertThat(m).contains("Kotlin 2.2.21 is below jk's minimum"));
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

        GradleBuildImport.Result result =
                GradleModelImporter.importModel(single, Path.of("/tmp/widget"), RefreshVersions.NONE);

        assertThat(result.modules()).isEmpty();
        assertThat(result.root().isWorkspaceRoot()).isFalse();
        assertThat(result.root().project().name()).isEqualTo("widget");
        assertThat(only(result.root(), Scope.MAIN).module()).isEqualTo("com.google.guava:guava");
    }

    /** The shadowJar task's relocators travel in the model; a project with no main ships them under [library]. */
    @Test
    void shadow_relocators_in_the_model_are_the_librarys_rules_and_the_plugin_is_no_row() {
        String shaded = """
                {"gradle":"9.5.1","rootName":"lucene9-shaded","settingsRepositories":[],"projects":[
                  {"path":":","projectName":"lucene9-shaded","dir":"","group":"org.demo","version":"2.1.0",
                   "plugins":["java-library","com.gradleup.shadow"],"pluginClasses":["com.github.jengelman.gradle.plugins.shadow.ShadowPlugin"],
                   "pluginVersions":{"com.gradleup.shadow":"9.0.0"},"java":{"sourceCompatibility":"25","targetCompatibility":"25"},"bootBuildInfo":false,"repositories":[],
                   "configurations":[],"tasks":[],
                   "relocations":[
                     {"pattern":"org.apache.lucene","shadedPattern":"org.demo.shaded.lucene9","rawString":false,"includes":[],"excludes":[]},
                     {"pattern":"org/apache/lucene","shadedPattern":"org/demo/shaded/lucene9","rawString":true,"includes":[],"excludes":[]}
                   ]}]}
                """;

        GradleBuildImport.Result result =
                GradleModelImporter.importModel(shaded, Path.of("/tmp/lucene9-shaded"), RefreshVersions.NONE);

        JkBuild root = result.root();
        assertThat(root.applicationOpt()).isEmpty();
        assertThat(root.libraryOpt()).isPresent();
        assertThat(root.assembly()).isTrue();
        assertThat(root.relocate()).containsExactly(Map.entry("org.apache.lucene", "org.demo.shaded.lucene9"));
        assertThat(messages(result.report()))
                .noneMatch(m -> m.contains("relocate"))
                .noneMatch(m -> m.contains("com.gradleup.shadow"));
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

        GradleBuildImport.Result result =
                GradleModelImporter.importModel(boot, Path.of("/tmp/gs"), RefreshVersions.NONE);

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
        assertThat(messages(result.report())).anySatisfy(m -> assertThat(m)
                .isEqualTo("library: Gradle plugin com.acme.gradle.HousePlugin: not imported"));
    }

    /** A library under dependency-management importing Spring Cloud's BOM beside Boot's, as the script text names them. */
    @Test
    void the_boms_a_dependency_management_block_imports_are_platform_rows_and_the_managed_versions_stay_managed() {
        String cloud = """
                {"gradle":"9.3.1","rootName":"cloud-lib","settingsRepositories":[],"projects":[
                  {"path":":","projectName":"cloud-lib","dir":"","group":"com.example","version":"1.0",
                   "plugins":["java","io.spring.dependency-management"],"pluginClasses":["io.spring.gradle.dependencymanagement.DependencyManagementPlugin"],
                   "pluginVersions":{"io.spring.dependency-management":"1.1.7"},"bootBuildInfo":false,"repositories":[],
                   "configurations":[{"name":"implementation","dependencies":[
                     {"kind":"module","group":"org.springframework.cloud","artifact":"spring-cloud-starter-config","version":"","excludes":[]},
                     {"kind":"module","group":"org.springframework.boot","artifact":"spring-boot-starter","version":"","excludes":[]}]}],
                   "managedVersions":{"org.springframework.cloud:spring-cloud-starter-config":"5.0.0","org.springframework.boot:spring-boot-starter":"3.5.11"},
                   "springBootBom":"org.springframework.boot:spring-boot-dependencies:3.5.11",
                   "importedBoms":["org.springframework.boot:spring-boot-dependencies:3.5.11","org.springframework.cloud:spring-cloud-dependencies:2025.0.0","com.acme:acme-bom:${acmeVersion}"],
                   "tasks":[]}]}
                """;

        GradleBuildImport.Result result =
                GradleModelImporter.importModel(cloud, Path.of("/tmp/cloud"), RefreshVersions.NONE);

        JkBuild lib = result.root();
        assertThat(lib.dependencies().of(Scope.PLATFORM))
                .extracting(d -> d.module() + ":" + d.version().raw())
                .containsExactly(
                        "org.springframework.boot:spring-boot-dependencies:3.5.11",
                        "org.springframework.cloud:spring-cloud-dependencies:2025.0.0");
        assertThat(lib.dependencies().of(Scope.MAIN))
                .as("the BOMs manage the versions; no exact pin is written")
                .allMatch(Dependency::isPlatformManaged);
        List<String> rows = messages(result.report());
        assertThat(rows).noneMatch(m -> m.contains("pinned exactly"));
        assertThat(rows).anySatisfy(m -> assertThat(m)
                .contains("BOM com.acme:acme-bom:${acmeVersion}: version reads an undefined property"));
    }

    /** A `dependencyManagement { dependencies { } }` block with no BOM: the plugin's version is all there is to write. */
    @Test
    void a_managed_version_from_no_bom_is_an_exact_pin_with_a_row() {
        String direct = """
                {"gradle":"9.3.1","rootName":"lib","settingsRepositories":[],"projects":[
                  {"path":":","projectName":"lib","dir":"","group":"com.example","version":"1.0",
                   "plugins":["java","io.spring.dependency-management"],"pluginClasses":[],"pluginVersions":{},"bootBuildInfo":false,"repositories":[],
                   "configurations":[{"name":"implementation","dependencies":[
                     {"kind":"module","group":"com.google.guava","artifact":"guava","version":"","excludes":[]}]}],
                   "managedVersions":{"com.google.guava:guava":"33.4.8-jre"},"importedBoms":[],"tasks":[]}]}
                """;

        GradleBuildImport.Result result =
                GradleModelImporter.importModel(direct, Path.of("/tmp/lib"), RefreshVersions.NONE);

        Dependency guava = only(result.root(), Scope.MAIN);
        assertThat(guava.version().raw()).isEqualTo("33.4.8-jre");
        assertThat(result.root().dependencies().of(Scope.PLATFORM)).isEmpty();
        assertThat(messages(result.report())).anySatisfy(m -> assertThat(m)
                .isEqualTo("com.google.guava:guava 33.4.8-jre from dependencyManagement { }; pinned exactly"));
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

        GradleBuildImport.Result result =
                GradleModelImporter.importModel(shared, Path.of("/tmp/retrofit"), RefreshVersions.NONE);

        assertThat(result.modules().keySet()).containsExactly("retrofit-adapters/guava", "retrofit-converters/guava");
        assertThat(module(result, "retrofit-adapters/guava").project().name()).isEqualTo("retrofit-adapters-guava");
        assertThat(module(result, "retrofit-converters/guava").project().name()).isEqualTo("retrofit-converters-guava");
        Dependency edge = only(module(result, "retrofit-converters/guava"), Scope.MAIN);
        assertThat(edge.isWorkspace()).isTrue();
        assertThat(edge.library()).isEqualTo("retrofit-adapters-guava");
        assertThat(messages(result.report()))
                .anySatisfy(m -> assertThat(m).contains("share the name guava").contains("retrofit-adapters/guava"))
                .anySatisfy(m -> assertThat(m).contains("retrofit-bom").contains("java-platform"));
    }

    /** kotlin4example: Gradle reports the declared version {@code _}; the pin is in versions.properties at the root. */
    @Test
    void a_refresh_versions_placeholder_reads_its_pin_from_the_roots_versions_properties(@TempDir Path root)
            throws Exception {
        Files.writeString(root.resolve("versions.properties"), """
                version.kotlinx.coroutines=1.10.2
                version.io.github.microutils..kotlin-logging=3.0.5
                """);
        String model = """
                {"gradle":"9.0.0","rootName":"kotlin4example","settingsRepositories":[],"projects":[
                  {"path":":","projectName":"kotlin4example","dir":"","group":"com.github.jillesvangurp","version":"1.0","plugins":["org.jetbrains.kotlin.jvm"],
                   "pluginClasses":[],"pluginVersions":{},"kotlinVersion":"2.4.20","bootBuildInfo":false,"repositories":[],
                   "configurations":[{"name":"implementation","dependencies":[
                     {"kind":"module","group":"org.jetbrains.kotlinx","artifact":"kotlinx-coroutines-core","version":"_","excludes":[]},
                     {"kind":"module","group":"io.github.microutils","artifact":"kotlin-logging","version":"_","excludes":[]},
                     {"kind":"module","group":"io.kotest","artifact":"kotest-assertions-core","version":"_","excludes":[]}]}],
                   "tasks":[]}]}
                """;

        GradleBuildImport.Result result = GradleModelImporter.importModel(model, root, RefreshVersions.beside(root));

        List<Dependency> main = result.root().dependencies().of(Scope.MAIN);
        assertThat(main.subList(0, 2))
                .extracting(d -> d.module() + ":" + d.version().raw())
                .containsExactly(
                        "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2",
                        "io.github.microutils:kotlin-logging:3.0.5");
        assertThat(main.get(2).module()).isEqualTo("io.kotest:kotest-assertions-core");
        assertThat(main.get(2).isPlatformManaged()).as("no key fits kotest").isTrue();
        assertThat(messages(result.report()))
                .hasSize(1)
                .first()
                .asString()
                .startsWith("io.kotest:kotest-assertions-core: no ")
                .contains("in versions.properties; pin it in jk.toml");
    }

    /**
     * junit-multiple-engines: Kotlin and Groovy plugins over Java test sources, {@code --release}
     * set on every JavaCompile. The module declares java at that release so its Java compiles, and
     * Kotlin and Groovy side by side, as Gradle compiles them.
     */
    @Test
    void a_kotlin_and_groovy_project_with_java_sources_declares_all_three(@TempDir Path dir) throws Exception {
        Path test = dir.resolve("src/test/java/junit/JupiterTests.java");
        Files.createDirectories(test.getParent());
        Files.writeString(test, "package junit; class JupiterTests {}");
        String model = """
                {"gradle":"9.8.0","rootName":"junit-multiple-engines","settingsRepositories":[],"projects":[
                  {"path":":","projectName":"junit-multiple-engines","dir":"","group":"","version":"unspecified",
                   "plugins":["java","groovy","org.jetbrains.kotlin.jvm"],"pluginClasses":["org.jetbrains.kotlin.gradle.plugin.KotlinPluginWrapper"],
                   "pluginVersions":{"org.jetbrains.kotlin.jvm":"2.4.20"},"kotlinVersion":"2.4.20","kotlinJvmTarget":"17",
                   "java":{"sourceCompatibility":"25","targetCompatibility":"25","release":17},"bootBuildInfo":false,"repositories":[],
                   "sourceSets":[{"name":"test","java":["src/test/java"],"resources":["src/test/resources"],"kotlin":["src/test/kotlin","src/test/java"]}],
                   "configurations":[
                     {"name":"testImplementation","dependencies":[
                       {"kind":"module","group":"org.spockframework","artifact":"spock-core","version":"2.4-groovy-5.0","excludes":[]},
                       {"kind":"module","group":"org.apache.groovy","artifact":"groovy-bom","version":"5.1.2","category":"platform","excludes":[]}],"constraints":[]}],
                   "resolved":["org.apache.groovy:groovy@5.1.2","org.spockframework:spock-core@2.4-groovy-5.0"],
                   "tasks":[]}]}
                """;

        GradleBuildImport.Result result = GradleModelImporter.importModel(model, dir, RefreshVersions.NONE);

        JkBuild build = result.root();
        assertThat(build.project().java()).isEqualTo(17);
        assertThat(Objects.requireNonNull(build.project().kotlin()).raw()).isEqualTo("2.4.20");
        assertThat(Objects.requireNonNull(build.project().groovy()).raw()).isEqualTo("5.1.2");
        assertThat(messages(result.report())).noneSatisfy(m -> assertThat(m).contains("groovy: no version found"));
    }

    /** A Groovy module over Java sources declares the Groovy its classpath resolves and java at the release. */
    @Test
    void a_groovy_project_declares_the_resolved_groovy(@TempDir Path dir) throws Exception {
        Path source = dir.resolve("src/main/java/a/A.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package a; class A {}");
        String model = """
                {"gradle":"9.8.0","rootName":"g","settingsRepositories":[],"projects":[
                  {"path":":","projectName":"g","dir":"","group":"com.acme","version":"1.0",
                   "plugins":["java","groovy"],"pluginClasses":[],"pluginVersions":{},
                   "java":{"sourceCompatibility":"21","targetCompatibility":"21"},"bootBuildInfo":false,"repositories":[],
                   "configurations":[{"name":"implementation","dependencies":[
                     {"kind":"module","group":"org.apache.groovy","artifact":"groovy","version":"","excludes":[]}],"constraints":[]}],
                   "resolved":["org.apache.groovy:groovy@5.0.2"],
                   "tasks":[]}]}
                """;

        GradleBuildImport.Result result = GradleModelImporter.importModel(model, dir, RefreshVersions.NONE);

        assertThat(Objects.requireNonNull(result.root().project().groovy()).raw())
                .isEqualTo("5.0.2");
        assertThat(result.root().project().java()).isEqualTo(21);
        assertThat(messages(result.report())).noneMatch(m -> m.contains("groovy: no version found"));
    }

    /**
     * junit-pioneer: {@code useJUnitJupiter()} adds junit-jupiter at Gradle's default, and the
     * project's junit-bom lifts it; the pin is the version Gradle resolves.
     */
    @Test
    void a_declared_version_below_what_gradle_resolves_is_pinned_at_the_resolved_one() {
        String model = """
                {"gradle":"9.8.0","rootName":"junit-pioneer","settingsRepositories":[],"projects":[
                  {"path":":","projectName":"junit-pioneer","dir":"","group":"org.junit-pioneer","version":"unspecified",
                   "plugins":["java"],"pluginClasses":[],"pluginVersions":{},
                   "java":{"sourceCompatibility":"17","targetCompatibility":"17"},"bootBuildInfo":false,"repositories":[],
                   "configurations":[
                     {"name":"implementation","dependencies":[
                       {"kind":"module","group":"org.junit","artifact":"junit-bom","version":"6.1.0","category":"platform","excludes":[]}],"constraints":[]},
                     {"name":"testImplementation","dependencies":[
                       {"kind":"module","group":"org.junit.jupiter","artifact":"junit-jupiter","version":"5.12.2","excludes":[]},
                       {"kind":"module","group":"org.strict","artifact":"pinned","version":"1.0","strict":"1.0","excludes":[]}],"constraints":[]}],
                   "resolved":["org.junit.jupiter:junit-jupiter@6.1.0","org.strict:pinned@1.0"],
                   "tasks":[]}]}
                """;

        GradleBuildImport.Result result =
                GradleModelImporter.importModel(model, Path.of("/tmp/pioneer"), RefreshVersions.NONE);

        assertThat(result.root().dependencies().of(Scope.TEST))
                .extracting(d -> d.module() + ":" + d.version().raw())
                .containsExactly("org.junit.jupiter:junit-jupiter:6.1.0", "org.strict:pinned:1.0");
        assertThat(messages(result.report()))
                .anySatisfy(m -> assertThat(m).contains(" 5.12.2 resolves to 6.1.0 in Gradle; wrote 6.1.0"));
    }

    /**
     * gs-producing-web-service: the xjc plugin adds jakarta.xml.bind-api at its own version, below
     * the one Boot's BOM manages, and dependency-management lets it stand. The import leaves the
     * version to the platform, and the plugin's extension is the [jaxb] table.
     */
    @Test
    void a_declared_version_below_the_boms_is_platform_managed_and_xjc_is_the_jaxb_table() {
        String model = """
                {"gradle":"9.8.0","rootName":"producing-web-service","settingsRepositories":[],"projects":[
                  {"path":":","projectName":"producing-web-service","dir":"","group":"com.example","version":"0.0.1-SNAPSHOT",
                   "plugins":["java","org.springframework.boot","io.spring.dependency-management","com.github.bjornvester.xjc"],
                   "pluginClasses":["org.springframework.boot.gradle.plugin.SpringBootPlugin","com.github.bjornvester.xjc.XjcPlugin"],
                   "pluginVersions":{"org.springframework.boot":"4.0.8"},"java":{"toolchain":17,"sourceCompatibility":"17","targetCompatibility":"17"},
                   "bootBuildInfo":false,"repositories":[],
                   "configurations":[
                     {"name":"implementation","dependencies":[
                       {"kind":"module","group":"org.springframework.boot","artifact":"spring-boot-starter-webservices","version":"","excludes":[]},
                       {"kind":"module","group":"jakarta.xml.bind","artifact":"jakarta.xml.bind-api","version":"4.0.4","excludes":[]}],"constraints":[]},
                     {"name":"xjc","dependencies":[{"kind":"module","group":"org.glassfish.jaxb","artifact":"jaxb-xjc","version":"4.0.4","excludes":[]}],"constraints":[]}],
                   "managedVersions":{"org.springframework.boot:spring-boot-starter-webservices":"4.0.8","jakarta.xml.bind:jakarta.xml.bind-api":"4.0.5"},
                   "springBootBom":"org.springframework.boot:spring-boot-dependencies:4.0.8",
                   "xjc":{"xsdDir":"src/main/resources/META-INF/schemas","bindingFiles":[],"options":[]},
                   "resolved":["jakarta.xml.bind:jakarta.xml.bind-api@4.0.4"],
                   "tasks":[]}]}
                """;

        GradleBuildImport.Result result =
                GradleModelImporter.importModel(model, Path.of("/tmp/gs"), RefreshVersions.NONE);

        JkBuild build = result.root();
        Dependency bind = build.dependencies().of(Scope.MAIN).stream()
                .filter(d -> d.module().equals("jakarta.xml.bind:jakarta.xml.bind-api"))
                .findFirst()
                .orElseThrow();
        assertThat(bind.isPlatformManaged()).isTrue();
        assertThat(build.pluginConfig("jaxb").orElseThrow().string("src"))
                .isEqualTo("src/main/resources/META-INF/schemas");
        assertThat(messages(result.report()))
                .anySatisfy(m -> assertThat(m).contains(" 4.0.4 is below its BOM's 4.0.5; wrote managed"))
                .noneMatch(m -> m.contains("Gradle plugin") && m.contains("xjc"))
                .noneMatch(m -> m.contains("com.github.bjornvester.xjc.XjcPlugin"));
    }

    /**
     * java-test-fixtures, as a 51-module Spring build used it: the producer's fixtures read a
     * sibling, and that sibling's tests read the fixtures back.
     */
    private static final String TEST_FIXTURES = """
            {"gradle":"9.8.0","rootName":"shop","settingsRepositories":[],"projects":[
              {"path":":","projectName":"shop","dir":"","group":"com.acme","version":"1.0","plugins":[],"pluginClasses":[],
               "pluginVersions":{},"bootBuildInfo":false,"repositories":[],"configurations":[],"tasks":[]},
              {"path":":arch","projectName":"arch","dir":"arch","group":"com.acme","version":"1.0",
               "plugins":["java","java-library","java-test-fixtures"],"pluginClasses":[],"pluginVersions":{},"bootBuildInfo":false,"repositories":[],
               "sourceSets":[{"name":"main","java":["src/main/java"],"resources":["src/main/resources"],"kotlin":[]},
                             {"name":"test","java":["src/test/java"],"resources":["src/test/resources"],"kotlin":[]},
                             {"name":"testFixtures","java":["src/testFixtures/java"],"resources":["src/testFixtures/resources"],"kotlin":[]}],
               "configurations":[
                 {"name":"testFixturesApi","dependencies":[{"kind":"project","projectPath":":arch"},
                   {"kind":"module","group":"com.tngtech.archunit","artifact":"archunit-junit5","version":"1.4.2","excludes":[]}],"constraints":[]},
                 {"name":"testFixturesImplementation","dependencies":[{"kind":"project","projectPath":":infra"}],"constraints":[]},
                 {"name":"testImplementation","dependencies":[{"kind":"project","projectPath":":arch","fixtures":true},
                   {"kind":"project","projectPath":":infra"}],"constraints":[]}
               ],"tasks":[]},
              {"path":":infra","projectName":"infra","dir":"infra","group":"com.acme","version":"1.0",
               "plugins":["java","java-library"],"pluginClasses":[],"pluginVersions":{},"bootBuildInfo":false,"repositories":[],
               "sourceSets":[{"name":"main","java":["src/main/java"],"resources":["src/main/resources"],"kotlin":[]}],
               "configurations":[
                 {"name":"testImplementation","dependencies":[{"kind":"project","projectPath":":arch","fixtures":true},
                   {"kind":"project","projectPath":":arch"}],"constraints":[]}
               ],"tasks":[]}
            ]}
            """;

    @Test
    void test_fixtures_import_as_the_producers_fixtures_root_and_fixtures_edges() {
        GradleBuildImport.Result result =
                GradleModelImporter.importModel(TEST_FIXTURES, Path.of("/tmp/shop"), RefreshVersions.NONE);

        JkBuild arch = module(result, "arch");
        assertThat(arch.build().fixtures()).isEqualTo("src/testFixtures/java");
        assertThat(arch.dependencies().of(Scope.TEST))
                .as("its own fixtures and main are implicit; the fixtures' sibling and libraries are test dependencies")
                .extracting(Dependency::library)
                .containsExactly("archunit-junit5", "infra");

        List<Dependency> infraTests = module(result, "infra").dependencies().of(Scope.TEST);
        assertThat(infraTests)
                .as("one edge per sibling, with fixtures; the fixtures' api libraries ride along")
                .extracting(Dependency::library)
                .containsExactly("arch", "archunit-junit5");
        assertThat(infraTests.getFirst().fixtures()).isTrue();
        assertThat(infraTests.getFirst().isWorkspace()).isTrue();

        assertThat(messages(result.report()))
                .noneMatch(m -> m.contains("testFixtures") || m.contains("java-test-fixtures"));
        assertThat(result.pinsResolved()).isTrue();
    }

    /**
     * The Avro plugin, as a Kafka service build uses it: one module reads src/main/avro, another
     * reads schemas a registry-download task writes under build/.
     */
    private static final String AVRO_MODULES = """
            {"gradle":"9.8.0","rootName":"shop","settingsRepositories":[],"projects":[
              {"path":":","projectName":"shop","dir":"","group":"com.acme","version":"1.0","plugins":[],"pluginClasses":[],
               "pluginVersions":{},"bootBuildInfo":false,"repositories":[],"configurations":[],"tasks":[]},
              {"path":":events","projectName":"events","dir":"events","group":"com.acme","version":"1.0",
               "plugins":["java","com.github.davidmc24.gradle.plugin.avro","com.github.davidmc24.gradle.plugin.avro-base"],
               "pluginClasses":["com.github.davidmc24.gradle.plugin.avro.AvroPlugin","com.github.davidmc24.gradle.plugin.avro.AvroBasePlugin"],
               "pluginVersions":{},"bootBuildInfo":false,"repositories":[],"configurations":[],"tasks":[],
               "sourceSets":[{"name":"main","java":["src/main/java","build/generated-main-avro-java"],"resources":["src/main/resources"],"kotlin":[]}],
               "avro":{"sources":["src/main/avro"],"buildDir":"build"},
               "ruled":{"com.github.davidmc24.gradle.plugin.avro":{"stringType":"String","fieldVisibility":"PRIVATE",
                       "createSetters":true,"createOptionalGetters":false,"enableDecimalLogicalType":true}},
               "resolved":["org.apache.avro:avro@1.11.5"]},
              {"path":":flags","projectName":"flags","dir":"flags","group":"com.acme","version":"1.0",
               "plugins":["java","com.github.davidmc24.gradle.plugin.avro"],
               "pluginClasses":["com.github.davidmc24.gradle.plugin.avro.AvroPlugin"],
               "pluginVersions":{},"bootBuildInfo":false,"repositories":[],"configurations":[],"tasks":[],
               "avro":{"sources":["build/generated/avro/shared"],"buildDir":"build"},
               "ruled":{"com.github.davidmc24.gradle.plugin.avro":{"stringType":"CharSequence",
                       "fieldVisibility":"PUBLIC","createSetters":false,"enableDecimalLogicalType":false}}}
            ]}
            """;

    @Test
    void the_avro_plugin_imports_as_the_avro_table_with_only_what_differs() {
        GradleBuildImport.Result result =
                GradleModelImporter.importModel(AVRO_MODULES, Path.of("/tmp/shop"), RefreshVersions.NONE);

        assertThat(module(result, "events").pluginConfig("avro").orElseThrow().values())
                .as("src/main/avro and String are the table's defaults; the plugin's decimal default is not")
                .containsOnly(Map.entry("decimal-logical-type", true), Map.entry("version", "1.11.5"));
        assertThat(module(result, "flags").pluginConfig("avro").orElseThrow().values())
                .containsOnly(
                        Map.entry("string-type", "CharSequence"),
                        Map.entry("field-visibility", "PUBLIC"),
                        Map.entry("setters", false));
        assertThat(messages(result.report()))
                .contains(
                        "flags: Avro schemas are generated into build/generated/avro/shared; put them in src/main/avro")
                .noneMatch(m -> m.contains("davidmc24"))
                .noneMatch(m -> m.contains("generated-main-avro-java"));
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
