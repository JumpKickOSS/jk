// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildLayoutTest {

    /** Library project: no {@code main}. Artifacts land in {@code target/}, as an application's do. */
    private static JkBuild project(String artifact, String version) {
        return JkBuild.of(new Project("com.acme", artifact, version, 25));
    }

    /** Application project: has {@code [application].main}. Artifacts land in {@code target/}. */
    private static JkBuild appProject(String artifact, String version) {
        return JkBuildParser.parse("""
                group   = "com.acme"
                name    = "%s"
                version = "%s"
                java    = 25

                [application]
                main    = "com.acme.Main"
                """.formatted(artifact, version));
    }

    @Test
    void a_library_jar_was_compiled_into_the_module_s_main_classes(@TempDir Path dir) throws IOException {
        BuildLayout layout = BuildLayout.of(dir, project("widget", "0.1.0"));
        Files.createDirectories(layout.classesDir());
        Files.createDirectories(layout.mainJar().getParent());
        Files.createFile(layout.mainJar());

        assertThat(BuildLayout.compiledClassesOf(layout.mainJar())).contains(layout.classesDir());
    }

    @Test
    void an_application_jar_was_compiled_into_the_classes_beside_it(@TempDir Path dir) throws IOException {
        BuildLayout layout = BuildLayout.of(dir, appProject("app", "0.1.0"));
        Files.createDirectories(layout.classesDir());
        Files.createFile(layout.mainJar());

        assertThat(layout.mainJar().getParent()).isEqualTo(layout.targetDir());
        assertThat(BuildLayout.compiledClassesOf(layout.mainJar())).contains(layout.classesDir());
    }

    @Test
    void a_classes_tree_is_its_own_compile_output(@TempDir Path dir) throws IOException {
        BuildLayout layout = BuildLayout.of(dir, project("widget", "0.1.0"));
        Files.createDirectories(layout.classesDir());
        Files.createDirectories(layout.testClassesDir());
        Files.createDirectories(layout.kotlinClassesDir());

        assertThat(BuildLayout.compiledClassesOf(layout.classesDir())).contains(layout.classesDir());
        assertThat(BuildLayout.compiledClassesOf(layout.testClassesDir())).contains(layout.testClassesDir());
        // Another compiler's tree is not a javac output.
        assertThat(BuildLayout.compiledClassesOf(layout.kotlinClassesDir())).isEmpty();
    }

    @Test
    void a_jar_with_no_classes_tree_beside_it_was_not_compiled_by_jk(@TempDir Path dir) throws IOException {
        Path m2 = dir.resolve("m2/lib/guava.jar");
        Files.createDirectories(m2.getParent());
        Files.createFile(m2);
        Path missing = dir.resolve("target/gone.jar");

        assertThat(BuildLayout.compiledClassesOf(m2)).isEmpty();
        assertThat(BuildLayout.compiledClassesOf(missing)).isEmpty();
        assertThat(BuildLayout.compiledClassesOf(dir)).isEmpty();
    }

    @Test
    void single_project_roots_resolve_to_project_dir(@TempDir Path dir) {
        BuildLayout layout = BuildLayout.of(dir, project("widget", "0.1.0"));

        assertThat(layout.workspaceRoot()).isEqualTo(dir);
        assertThat(layout.moduleRoot()).isEqualTo(dir);
        assertThat(layout.buildDir()).isEqualTo(dir.resolve("target"));
        assertThat(layout.targetDir()).isEqualTo(dir.resolve("target"));
    }

    @Test
    void intermediates_live_where_maven_puts_them(@TempDir Path dir) {
        BuildLayout layout = BuildLayout.of(dir, project("widget", "1.2.3"));

        assertThat(layout.classesDir()).isEqualTo(dir.resolve("target/classes"));
        assertThat(layout.testClassesDir()).isEqualTo(dir.resolve("target/test-classes"));
        assertThat(layout.kotlinClassesDir()).isEqualTo(dir.resolve("target/kotlin/main"));
        assertThat(layout.kotlinTestClassesDir()).isEqualTo(dir.resolve("target/kotlin/test"));
        assertThat(layout.generatedSourcesDir("annotations"))
                .isEqualTo(dir.resolve("target/generated-sources/annotations"));
        assertThat(layout.generatedSourcesDir("annotations", "test"))
                .isEqualTo(dir.resolve("target/generated-test-sources/test-annotations"));
        assertThat(layout.generatedSourcesDir("annotations", "fixtures"))
                .isEqualTo(dir.resolve("target/generated-sources/annotations-fixtures"));
        assertThat(layout.testResultsDir()).isEqualTo(dir.resolve("target/surefire-reports"));
        assertThat(layout.integrationResultsDir()).isEqualTo(dir.resolve("target/failsafe-reports"));
        assertThat(layout.jacocoExec()).isEqualTo(dir.resolve("target/jacoco.exec"));
        assertThat(layout.jacocoReportDir()).isEqualTo(dir.resolve("target/site/jacoco"));
        assertThat(layout.apidocsDir()).isEqualTo(dir.resolve("target/site/apidocs"));
    }

    @Test
    void a_library_s_artifacts_live_at_the_target_root(@TempDir Path dir) {
        BuildLayout layout = BuildLayout.of(dir, project("widget", "1.2.3"));

        assertThat(layout.artifactDir()).isEqualTo(dir.resolve("target"));
        assertThat(layout.mainJar()).isEqualTo(dir.resolve("target/widget-1.2.3.jar"));
        assertThat(layout.assemblyJar()).isEqualTo(dir.resolve("target/widget-1.2.3-all.jar"));
        assertThat(layout.minifiedJar()).isEqualTo(dir.resolve("target/widget-1.2.3-min.jar"));
        assertThat(layout.sourcesJar()).isEqualTo(dir.resolve("target/widget-1.2.3-sources.jar"));
        assertThat(layout.javadocJar()).isEqualTo(dir.resolve("target/widget-1.2.3-javadoc.jar"));
        assertThat(layout.nativeBinary())
                .isEqualTo(dir.resolve("target").resolve(BuildLayout.nativeExecutableFileName("widget")));
        assertThat(layout.nativeLibrary()).isEqualTo(dir.resolve("target/libwidget"));
        assertThat(layout.ociImageTar()).isEqualTo(dir.resolve("target/widget.oci.tar"));
        assertThat(layout.sbomDir()).isEqualTo(dir.resolve("target/sbom"));
        assertThat(layout.provenanceDir()).isEqualTo(dir.resolve("target/widget-1.2.3-provenance"));
    }

    @Test
    void a_workspace_member_writes_to_its_own_target(@TempDir Path workspace) {
        Path module = workspace.resolve("core");
        BuildLayout layout = BuildLayout.of(workspace, module, project("jk-core", "0.7.0"));

        assertThat(layout.classesDir())
                .isEqualTo(module.toAbsolutePath().normalize().resolve("target/classes"));
        assertThat(layout.mainJar())
                .isEqualTo(module.toAbsolutePath().normalize().resolve("target/jk-core-0.7.0.jar"));
        assertThat(BuildLayout.moduleTargetDir(module))
                .isEqualTo(module.toAbsolutePath().normalize().resolve("target"));
    }

    @Test
    void of_auto_discovers_enclosing_workspace_root(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("jk.toml"), """
                group    = "com.example"
                name     = "ws-root"
                version  = "1.0.0"

                [workspace]
                modules = ["core"]
                """);
        Path module = workspace.resolve("core");
        Files.createDirectories(module);
        Files.writeString(module.resolve("jk.toml"), """
                group    = "com.example"
                name     = "core"
                version  = "1.0.0"
                """);

        JkBuild moduleProject = JkBuildParser.parse(module.resolve("jk.toml"));
        BuildLayout layout = BuildLayout.of(module, moduleProject);

        assertThat(layout.workspaceRoot()).isEqualTo(workspace.toAbsolutePath().normalize());
        assertThat(layout.moduleRoot()).isEqualTo(module);
        assertThat(layout.mainJar()).isEqualTo(module.resolve("target/core-1.0.0.jar"));
        assertThat(layout.classesDir()).isEqualTo(module.resolve("target/classes"));
    }

    @Test
    void of_workspace_root_itself_keeps_target_at_root(@TempDir Path workspace) {
        BuildLayout layout = BuildLayout.of(workspace, workspaceRootProject("ws-root", "1.0.0"));
        assertThat(layout.workspaceRoot()).isEqualTo(workspace);
        assertThat(layout.moduleRoot()).isEqualTo(workspace);
        assertThat(layout.mainJar()).isEqualTo(workspace.resolve("target/ws-root-1.0.0.jar"));
    }

    private static JkBuild workspaceRootProject(String artifact, String version) {
        return JkBuildParser.parse("""
                group    = "com.example"
                name     = "%s"
                version  = "%s"

                [workspace]
                modules = []
                """.formatted(artifact, version));
    }

    @Test
    void hasMain_false_for_library(@TempDir Path dir) {
        assertThat(BuildLayout.of(dir, project("widget", "1.0.0")).hasMain()).isFalse();
    }

    @Test
    void hasMain_true_for_application(@TempDir Path dir) {
        assertThat(BuildLayout.of(dir, appProject("widget", "1.0.0")).hasMain()).isTrue();
    }

    @Test
    void a_plugin_worker_is_packaged_at_root(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("jk-plugin.toml"), "[plugin]\nid = \"x\"\ntable = \"x\"\n");
        BuildLayout layout = BuildLayout.of(dir, project("jk-x", "1.0.0"));
        assertThat(layout.hasMain()).isFalse();
        assertThat(layout.pluginWorker()).isTrue();
        assertThat(layout.packagedAtRoot()).isTrue();
        assertThat(layout.artifactDir()).isEqualTo(dir.resolve("target"));
        assertThat(layout.mainJar()).isEqualTo(dir.resolve("target/jk-x-1.0.0.jar"));
    }

    @Test
    void artifact_and_version_are_exposed(@TempDir Path dir) {
        BuildLayout layout = BuildLayout.of(dir, project("alpha", "9.9.9-SNAPSHOT"));

        assertThat(layout.artifact()).isEqualTo("alpha");
        assertThat(layout.version()).isEqualTo("9.9.9-SNAPSHOT");
    }

    @Test
    void native_executable_file_name_appends_exe_only_on_windows() {
        String saved = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Windows 11");
            assertThat(BuildLayout.nativeExecutableFileName("jk")).isEqualTo("jk.exe");
            assertThat(BuildLayout.nativeExecutableFileName("jk.exe")).isEqualTo("jk.exe");
            assertThat(BuildLayout.nativeExecutableFileName("jk.EXE")).isEqualTo("jk.exe");
            System.setProperty("os.name", "Linux");
            assertThat(BuildLayout.nativeExecutableFileName("jk")).isEqualTo("jk");
            assertThat(BuildLayout.nativeExecutableFileName("jk.exe")).isEqualTo("jk");
            assertThat(BuildLayout.nativeExecutableFileName("jk.EXE")).isEqualTo("jk");
        } finally {
            System.setProperty("os.name", saved);
        }
    }

    @Test
    void native_binary_honors_native_name(@TempDir Path dir) {
        JkBuild build = JkBuildParser.parse("""
                group = "com.acme"
                name = "jk-cli"
                version = "1.0.0"
                java = 25

                [application]
                main = "com.acme.Main"

                [native]
                enabled = "always"
                name = "jk"
                """);
        BuildLayout layout = BuildLayout.of(dir, build);
        assertThat(layout.nativeBinary())
                .isEqualTo(dir.resolve("target").resolve(BuildLayout.nativeExecutableFileName("jk")));
    }

    @Test
    void native_binary_name_with_exe_suffix_matches_bare_name(@TempDir Path dir) {
        JkBuild withExe = JkBuildParser.parse("""
                group = "com.acme"
                name = "jk-cli"
                version = "1.0.0"
                java = 25

                [application]
                main = "com.acme.Main"

                [native]
                enabled = "always"
                name = "jk.exe"
                """);
        JkBuild bare = JkBuildParser.parse("""
                group = "com.acme"
                name = "jk-cli"
                version = "1.0.0"
                java = 25

                [application]
                main = "com.acme.Main"

                [native]
                enabled = "always"
                name = "jk"
                """);
        assertThat(withExe.nativeConfigOpt().orElseThrow().name()).isEqualTo("jk");
        assertThat(BuildLayout.of(dir, withExe).nativeBinary())
                .isEqualTo(BuildLayout.of(dir, bare).nativeBinary());
    }
}
