// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.testing.TestCaches;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A file removed from a resource root leaves the classes tree and the jar, including when the
 * output tree is gone and the next build is served from the action cache.
 */
@Tag("integration")
class DeletedResourceOutputTest {

    private static final String MAIN_GONE = "application.properties";
    private static final String MAIN_KEEP = "keep.properties";
    private static final String TEST_GONE = "META-INF/services/com.example.Spi";
    private static final String TEST_KEEP = "keep.txt";

    @Test
    void a_deleted_resource_is_absent_from_the_next_build(@TempDir Path tmp) throws Exception {
        Project project = project(tmp, "prune");
        build(project, "warm");
        assertPresent(project);

        Files.delete(project.mainResource(MAIN_GONE));
        Files.delete(project.testResource(TEST_GONE));
        build(project, "after the resources were deleted");

        assertAbsent(project);
    }

    @Test
    void a_deleted_resource_is_not_restored_after_the_output_tree_is_removed(@TempDir Path tmp) throws Exception {
        Project project = project(tmp, "restore");
        build(project, "warm");
        // A compile that runs while copied resources already sit in its output directory records
        // those files. The record is what a later build lays back down when the tree is gone.
        Files.writeString(project.dir.resolve("src/main/java/com/example/App.java"), appSource("app-edited"));
        build(project, "compile while the resources are in the output tree");
        assertPresent(project);

        Files.delete(project.mainResource(MAIN_GONE));
        Files.delete(project.testResource(TEST_GONE));
        PathUtil.deleteRecursively(project.layout.buildDir());
        build(project, "after the resources and the output tree were removed");

        assertAbsent(project);
    }

    @Test
    void a_deleted_main_resource_leaves_the_jar_when_tests_are_skipped(@TempDir Path tmp) throws Exception {
        Project project = project(tmp, "jar");
        build(project, "warm", true);
        Files.delete(project.mainResource(MAIN_GONE));
        build(project, "after the resource was deleted", true);
        assertThat(project.layout.classesDir().resolve(MAIN_GONE))
                .as("main classes")
                .doesNotExist();
        assertThat(jarEntries(project.layout.mainJar()))
                .as("packaged jar")
                .doesNotContain(MAIN_GONE)
                .contains(MAIN_KEEP);
    }

    @Test
    void a_rebuild_does_not_keep_a_deleted_resource(@TempDir Path tmp) throws Exception {
        Project project = project(tmp, "redo");
        build(project, "warm");
        Files.delete(project.mainResource(MAIN_GONE));
        Files.delete(project.testResource(TEST_GONE));
        Session redo = Session.defaults()
                .withConfig(JkConfig.empty().withRebuild(true))
                .withCacheDir(project.cache);
        SessionContext.where(redo, () -> {
            build(project, "rebuild after the resources were deleted");
            return null;
        });

        assertAbsent(project);
    }

    private static void assertPresent(Project project) throws IOException {
        assertThat(project.layout.classesDir().resolve(MAIN_GONE)).isRegularFile();
        assertThat(project.layout.classesDir().resolve(MAIN_KEEP)).isRegularFile();
        assertThat(project.layout.testClassesDir().resolve(TEST_GONE)).isRegularFile();
        assertThat(project.layout.testClassesDir().resolve(TEST_KEEP)).isRegularFile();
        assertThat(jarEntries(project.layout.mainJar())).contains(MAIN_GONE, MAIN_KEEP);
    }

    private static void assertAbsent(Project project) throws IOException {
        assertThat(project.layout.classesDir().resolve(MAIN_GONE))
                .as("main classes")
                .doesNotExist();
        assertThat(project.layout.classesDir().resolve(MAIN_KEEP))
                .as("a resource that is still in source")
                .isRegularFile();
        assertThat(project.layout.testClassesDir().resolve(TEST_GONE))
                .as("test classes")
                .doesNotExist();
        assertThat(project.layout.testClassesDir().resolve(TEST_KEEP)).isRegularFile();
        assertThat(jarEntries(project.layout.mainJar()))
                .as("packaged jar")
                .doesNotContain(MAIN_GONE)
                .contains(MAIN_KEEP);
    }

    private static Set<String> jarEntries(Path jar) throws IOException {
        assertThat(jar).isRegularFile();
        Set<String> names = new TreeSet<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            zip.stream().map(e -> e.getName()).forEach(names::add);
        }
        return names;
    }

    private static void build(Project project, String what) {
        build(project, what, false);
    }

    private static void build(Project project, String what, boolean skipTests) {
        WorkspaceResult result = WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(
                        project.dir, project.cache, null, 1, null, skipTests, false, 1, null, false, false),
                new WorkspaceBuildListener() {});
        assertThat(result.errors()).as("errors, " + what).isEmpty();
        assertThat(result.success()).as("success, " + what).isTrue();
    }

    private static Project project(Path tmp, String name) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve(name));
        Path cache = TestCaches.dir("deleted-resource-fix-" + name);
        Files.writeString(dir.resolve("jk.toml"), """
                name = "%s"
                group = "com.example"
                version = "1.0.0"
                java = 25
                layout = "traditional"

                [test-dependencies]
                junit-jupiter = { group = "org.junit.jupiter", name = "junit-jupiter", version = "6.1.3" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """.formatted(name));
        Files.createDirectories(dir.resolve("src/main/java/com/example"));
        Files.writeString(dir.resolve("src/main/java/com/example/App.java"), appSource("app"));
        Files.createDirectories(dir.resolve("src/main/resources"));
        Files.writeString(dir.resolve("src/main/resources").resolve(MAIN_GONE), "name=app\n");
        Files.writeString(dir.resolve("src/main/resources").resolve(MAIN_KEEP), "keep=1\n");
        Files.createDirectories(dir.resolve("src/test/java/com/example"));
        Files.writeString(dir.resolve("src/test/java/com/example/AppTest.java"), """
                package com.example;

                import org.junit.jupiter.api.Test;

                class AppTest {
                    @Test
                    void runs() {}
                }
                """);
        Path testRes = dir.resolve("src/test/resources");
        Files.createDirectories(testRes.resolve("META-INF/services"));
        Files.writeString(testRes.resolve(TEST_GONE), "com.example.Gone\n");
        Files.writeString(testRes.resolve(TEST_KEEP), "keep\n");

        JkBuild manifest = JkBuildParser.parse(dir.resolve("jk.toml"));
        BuildPlan lock =
                LockPlans.lockBuildPlan(dir, manifest, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("lock").isTrue();
        return new Project(dir, cache, BuildLayout.of(dir, manifest));
    }

    private static String appSource(String value) {
        return """
                package com.example;

                public final class App {
                    private App() {}

                    public static String name() {
                        return "%s";
                    }
                }
                """.formatted(value);
    }

    private record Project(Path dir, Path cache, BuildLayout layout) {
        Path mainResource(String rel) {
            return dir.resolve("src/main/resources").resolve(rel);
        }

        Path testResource(String rel) {
            return dir.resolve("src/test/resources").resolve(rel);
        }
    }
}
