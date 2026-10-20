// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import static java.util.Objects.requireNonNull;

import com.intellij.execution.PsiLocation;
import com.intellij.execution.actions.ConfigurationContext;
import com.intellij.execution.actions.ConfigurationFromContext;
import com.intellij.execution.process.ProcessOutputType;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.externalSystem.model.DataNode;
import com.intellij.openapi.externalSystem.model.ProjectKeys;
import com.intellij.openapi.externalSystem.model.project.ContentRootData;
import com.intellij.openapi.externalSystem.model.project.ExternalSystemSourceType;
import com.intellij.openapi.externalSystem.model.project.LibraryData;
import com.intellij.openapi.externalSystem.model.project.LibraryDependencyData;
import com.intellij.openapi.externalSystem.model.project.LibraryPathType;
import com.intellij.openapi.externalSystem.model.project.ModuleData;
import com.intellij.openapi.externalSystem.model.project.ProjectData;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType;
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.CompilerModuleExtension;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.testFramework.HeavyPlatformTestCase;
import com.intellij.testFramework.PlatformTestUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;

/**
 * Headless import of a two-module jk workspace through the real external-system path: link, {@link
 * JkProjectResolver} running the {@code jk} on PATH, and the platform applying the result. Asserts
 * every module resolved with roots, a library with its sources jar, SDKs, compiler output under
 * {@code target/jdt}, the gutter's run configuration, and no {@code .iml} in the workspace. Skips,
 * loudly, when no {@code jk} is on PATH.
 */
public class JkWorkspaceImportTest extends HeavyPlatformTestCase {

    private @Nullable String previousJkBin;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        previousJkBin = System.getProperty("jk.bin");
    }

    /** External module storage applies to directory-based projects only, as every opened jk project is. */
    @Override
    protected boolean isCreateDirectoryBasedProject() {
        return true;
    }

    /** No harness module: a real jk project maps one-to-one onto its IDE project, which is what sets the project SDK. */
    @Override
    protected void setUpModule() {}

    @Override
    protected void tearDown() throws Exception {
        try {
            if (previousJkBin == null) System.clearProperty("jk.bin");
            else System.setProperty("jk.bin", previousJkBin);
            WriteAction.run(() -> {
                ProjectJdkTable table = ProjectJdkTable.getInstance();
                for (Sdk sdk : table.getAllJdks()) {
                    if (sdk.getName().startsWith("jk-")) table.removeJdk(sdk);
                }
            });
        } finally {
            super.tearDown();
        }
    }

    public void test_jk_workspace_imports_with_roots_libraries_sdks_and_no_iml() throws Exception {
        JkImport.assumeJkOnPath(JkWorkspaceImportTest.class);
        Path ws = workspace(createTempDir("jk-import").toPath());
        // Written behind the IDE's back: the VFS sees the roots only once it has looked.
        VfsUtil.markDirtyAndRefresh(
                false, true, true, requireNonNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(ws)));

        JkCliRunner.Result sources = JkCliRunner.run(ws.toFile(), "sync", "--sources");
        assertTrue("jk sync --sources: " + sources.stderr(), sources.ok());

        JkImport.Result result = JkImport.importProject(getProject(), ws.toFile());
        assertNull("sync failed: " + result.failure(), result.failure());
        DataNode<ProjectData> graph = requireNonNull(result.graph());

        List<DataNode<ModuleData>> resolved = modules(graph);
        List<String> names = new ArrayList<>();
        for (DataNode<ModuleData> node : resolved) names.add(node.getData().getExternalName());
        assertEquals("the workspace's modules and its root", Set.of("core", "app", "acme"), Set.copyOf(names));
        int jkModules = 0;
        for (Module m : ModuleManager.getInstance(getProject()).getModules()) {
            if (ExternalSystemApiUtil.isExternalSystemAwareModule(JkSystem.ID, m)) jkModules++;
        }
        assertEquals("every resolved module exists in the IDE as a JumpKick module", resolved.size(), jkModules);

        int withSourceRoots = 0;
        for (DataNode<ModuleData> node : resolved) {
            Module module = requireNonNull(
                    ModuleManager.getInstance(getProject())
                            .findModuleByName(node.getData().getInternalName()),
                    node.getData().getInternalName());
            ModuleRootManager roots = ModuleRootManager.getInstance(module);
            assertTrue(module.getName() + " has a content root", roots.getContentRootUrls().length > 0);
            if (declares(node, ExternalSystemSourceType.SOURCE)) {
                assertTrue(module.getName() + " has source roots", roots.getSourceRoots(false).length > 0);
                withSourceRoots++;
            }
            if (declares(node, ExternalSystemSourceType.TEST)) {
                assertTrue(
                        module.getName() + " has test roots",
                        roots.getSourceRootUrls(true).length > roots.getSourceRootUrls(false).length);
            }
            Sdk sdk = requireNonNull(roots.getSdk(), module.getName() + " has an SDK");
            assertTrue(
                    module.getName() + " uses a jk-registered SDK: " + sdk.getName(),
                    sdk.getName().startsWith("jk-"));
            CompilerModuleExtension compiler = requireNonNull(CompilerModuleExtension.getInstance(module));
            if (!node.getData().isInheritProjectCompileOutputPath()) {
                String main = requireNonNull(compiler.getCompilerOutputUrl(), module.getName() + " output");
                String test =
                        requireNonNull(compiler.getCompilerOutputUrlForTests(), module.getName() + " test output");
                assertTrue(main, main.endsWith("/target/jdt/classes/main"));
                assertTrue(test, test.endsWith("/target/jdt/classes/test"));
                assertFalse(main, main.contains("/target/classes"));
            }
        }
        assertEquals("modules with source roots", 2, withSourceRoots);

        Library[] libraries = LibraryTablesRegistrar.getInstance()
                .getLibraryTable(getProject())
                .getLibraries();
        Set<String> referenced = new HashSet<>();
        for (DataNode<ModuleData> module : resolved) {
            for (DataNode<?> n : module.getChildren()) {
                if (n.getKey().equals(ProjectKeys.LIBRARY_DEPENDENCY)) {
                    referenced.add(((LibraryDependencyData) n.getData()).getExternalName());
                }
            }
        }
        // The platform materializes the libraries some module depends on; the rest stay nodes.
        assertEquals("every referenced library became a project library", referenced.size(), libraries.length);
        assertTrue(
                "slf4j-api is a project library: " + referenced,
                referenced.stream().anyMatch(name -> name.startsWith("org.slf4j:slf4j-api:")));
        int withSources = 0;
        for (Library lib : libraries) if (lib.getUrls(OrderRootType.SOURCES).length > 0) withSources++;
        int modelWithSources = 0;
        for (DataNode<?> n : graph.getChildren()) {
            if (n.getKey().equals(ProjectKeys.LIBRARY)) {
                LibraryData lib = (LibraryData) n.getData();
                if (referenced.contains(lib.getExternalName())
                        && !lib.getPaths(LibraryPathType.SOURCE).isEmpty()) {
                    modelWithSources++;
                }
            }
        }
        // After `jk sync --sources` the model lists a sources jar for every library that publishes
        // one, and each becomes a SOURCES root of its project library.
        assertEquals("libraries with sources jars", modelWithSources, withSources);
        assertTrue("libraries with sources jars after jk sync --sources: " + withSources, withSources > 0);

        Sdk projectSdk =
                requireNonNull(ProjectRootManager.getInstance(getProject()).getProjectSdk(), "project SDK set");
        assertTrue(projectSdk.getName(), projectSdk.getName().startsWith("jk-"));

        assertGutterRoutesThroughJk(ws);

        PlatformTestUtil.saveProject(getProject());
        assertEquals("no .iml written into the workspace", List.of(), imlFiles(ws));
        assertFalse(".idea/modules.xml not written into the workspace", Files.exists(ws.resolve(".idea/modules.xml")));
    }

    /**
     * A test class of an imported module resolves, from its PSI alone, to a JumpKick run
     * configuration: {@code jk test -m <module> --class <fqcn>} from the workspace root, and under
     * Debug the same command with the JDWP address the debugger attaches to.
     */
    private void assertGutterRoutesThroughJk(Path ws) throws Exception {
        Path source = ws.resolve("core/src/test/java/com/example/core/GreeterTest.java");
        VirtualFile vf = requireNonNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source));
        PsiJavaFile psi = (PsiJavaFile)
                requireNonNull(PsiManager.getInstance(getProject()).findFile(vf));
        PsiClass cls = psi.getClasses()[0];
        ConfigurationContext context =
                ConfigurationContext.createEmptyContextForLocation(new PsiLocation<>(getProject(), cls));
        ConfigurationFromContext from =
                requireNonNull(new JkRunConfigurationProducer().createConfigurationFromContext(context));
        JkRunConfiguration config = (JkRunConfiguration) from.getConfiguration();
        assertEquals(JkCommandLines.KIND_TEST, config.kind());
        assertEquals(
                FileUtil.toSystemIndependentName(ws.toString()), FileUtil.toSystemIndependentName(config.rootDir()));
        assertEquals("core", config.moduleRel());
        assertEquals(cls.getQualifiedName(), config.className());
        assertEquals("jk test GreeterTest", config.getName());
        assertTrue(new JkRunConfigurationProducer().isConfigurationFromContext(config, context));
        assertEquals(
                List.of("test", "-m", "core", "--class", cls.getQualifiedName(), "--debug-jvm=localhost:7007"),
                JkCommandLines.args(config.kind(), config.moduleRel(), config.className(), "localhost:7007"));
    }

    public void test_a_failing_cli_surfaces_its_first_error_line() throws Exception {
        Path ws = workspace(createTempDir("jk-import").toPath());
        Path fake = fakeJk(createTempDir("fake-jk").toPath(), "jk: engine refused the connection", "second line", 3);
        System.setProperty("jk.bin", fake.toString());

        JkImport.Result result = JkImport.importProject(getProject(), ws.toFile());
        assertNull(result.graph());
        String failure = requireNonNull(result.failure());
        assertTrue(failure, failure.contains("jk: engine refused the connection"));
        assertFalse(failure, failure.contains("second line"));
    }

    /**
     * A run of jk tasks takes the task names and the arguments from the execution settings, where
     * the platform puts both, and streams the CLI's output to the run console.
     */
    public void test_a_task_run_passes_the_settings_tasks_and_arguments_to_jk() throws Exception {
        Path ws = workspace(createTempDir("jk-task").toPath());
        Path fake = echoJk(createTempDir("echo-jk").toPath());
        System.setProperty("jk.bin", fake.toString());
        JkExecutionSettings settings = new JkExecutionSettings(fake.toString());
        settings.setTasks(List.of("build"));
        settings.withArguments("-m", "core");
        ExternalSystemTaskId id =
                ExternalSystemTaskId.create(JkSystem.ID, ExternalSystemTaskType.EXECUTE_TASK, getProject());
        StringBuilder console = new StringBuilder();

        new JkTaskManager().executeTasks(ws.toString(), id, settings, new ExternalSystemTaskNotificationListener() {
            @Override
            public void onTaskOutput(ExternalSystemTaskId task, String text, ProcessOutputType type) {
                console.append(text);
            }
        });

        assertTrue(console.toString(), console.toString().contains("args: build -m core"));
    }

    /** A jk that prints the arguments it was given and succeeds. */
    private static Path echoJk(Path dir) throws IOException {
        if (SystemInfo.isWindows) {
            Path cmd = dir.resolve("jk.cmd");
            Files.writeString(cmd, "@echo args: %*\r\n@exit /b 0\r\n");
            return cmd;
        }
        Path sh = dir.resolve("jk");
        Files.writeString(sh, "#!/bin/sh\necho \"args: $*\"\n");
        if (!sh.toFile().setExecutable(true)) throw new IOException("cannot make " + sh + " executable");
        return sh;
    }

    /**
     * A workspace {@code acme} under {@code dir}: {@code core} with a library that publishes sources
     * and a JUnit test, and {@code app} depending on {@code core}.
     */
    private static Path workspace(Path dir) throws IOException {
        Path ws = dir.resolve("acme");
        write(ws.resolve("jk.toml"), """
                group = "com.example"
                name = "acme"
                version = "1.0.0"
                java = 25

                [workspace]
                modules = ["core", "app"]
                """);
        write(ws.resolve("core/jk.toml"), """
                name = "core"

                [dependencies]
                slf4j-api = { group = "org.slf4j", version = "2.0.17" }

                [test-dependencies]
                junit-jupiter = "6.1.3"
                """);
        write(ws.resolve("core/src/main/java/com/example/core/Greeter.java"), """
                package com.example.core;

                public final class Greeter {
                    public static String greet(String name) {
                        return "hello " + name;
                    }
                }
                """);
        write(ws.resolve("core/src/test/java/com/example/core/GreeterTest.java"), """
                package com.example.core;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                class GreeterTest {
                    @Test
                    void greets() {
                        assertEquals("hello jk", Greeter.greet("jk"));
                    }
                }
                """);
        write(ws.resolve("app/jk.toml"), """
                name = "app"

                [dependencies]
                core.workspace = true
                """);
        write(ws.resolve("app/src/main/java/com/example/app/App.java"), """
                package com.example.app;

                import com.example.core.Greeter;

                public final class App {
                    public static void main(String[] args) {
                        System.out.println(Greeter.greet("app"));
                    }
                }
                """);
        return ws;
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    /** A stand-in jk that prints {@code first} and {@code second} on stderr and exits {@code exit}. */
    private static Path fakeJk(Path dir, String first, String second, int exit) throws IOException {
        if (SystemInfo.isWindows) {
            Path cmd = dir.resolve("jk.cmd");
            Files.writeString(
                    cmd, "@echo " + first + " 1>&2\r\n@echo " + second + " 1>&2\r\n@exit /b " + exit + "\r\n");
            return cmd;
        }
        Path sh = dir.resolve("jk");
        Files.writeString(sh, "#!/bin/sh\necho '" + first + "' >&2\necho '" + second + "' >&2\nexit " + exit + "\n");
        if (!sh.toFile().setExecutable(true)) throw new IOException("cannot make " + sh + " executable");
        return sh;
    }

    private static List<DataNode<ModuleData>> modules(DataNode<ProjectData> graph) {
        List<DataNode<ModuleData>> out = new ArrayList<>();
        for (DataNode<?> n : graph.getChildren()) {
            if (n.getKey().equals(ProjectKeys.MODULE)) {
                @SuppressWarnings("unchecked")
                DataNode<ModuleData> m = (DataNode<ModuleData>) n;
                out.add(m);
            }
        }
        return out;
    }

    private static boolean declares(DataNode<ModuleData> module, ExternalSystemSourceType type) {
        for (DataNode<?> n : module.getChildren()) {
            if (n.getKey().equals(ProjectKeys.CONTENT_ROOT)
                    && !((ContentRootData) n.getData()).getPaths(type).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static List<String> imlFiles(Path root) throws IOException {
        List<String> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.getFileName().toString().endsWith(".iml"))
                    .forEach(p -> out.add(root.relativize(p).toString()));
        }
        return out;
    }
}
