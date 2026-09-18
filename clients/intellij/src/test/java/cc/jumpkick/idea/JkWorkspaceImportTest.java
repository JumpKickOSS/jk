// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import static java.util.Objects.requireNonNull;

import com.intellij.execution.PsiLocation;
import com.intellij.execution.actions.ConfigurationContext;
import com.intellij.execution.actions.ConfigurationFromContext;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder;
import com.intellij.openapi.externalSystem.model.DataNode;
import com.intellij.openapi.externalSystem.model.ProjectKeys;
import com.intellij.openapi.externalSystem.model.project.ContentRootData;
import com.intellij.openapi.externalSystem.model.project.ExternalSystemSourceType;
import com.intellij.openapi.externalSystem.model.project.LibraryData;
import com.intellij.openapi.externalSystem.model.project.LibraryDependencyData;
import com.intellij.openapi.externalSystem.model.project.LibraryPathType;
import com.intellij.openapi.externalSystem.model.project.ModuleData;
import com.intellij.openapi.externalSystem.model.project.ProjectData;
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode;
import com.intellij.openapi.externalSystem.service.project.ExternalProjectRefreshCallback;
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager;
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil;
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil;
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
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.testFramework.HeavyPlatformTestCase;
import com.intellij.testFramework.PlatformTestUtil;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;
import org.junit.Assume;

/**
 * Headless import of this checkout — jk's own workspace — through the real external-system path:
 * link, {@link JkProjectResolver} running the installed {@code jk}, and the platform applying the
 * result. Asserts every module resolved with roots, libraries with sources, SDKs, compiler output
 * under {@code target/jdt}, and no {@code .iml} anywhere in the checkout. Skips, loudly, when no
 * {@code jk} is on PATH.
 */
public class JkWorkspaceImportTest extends HeavyPlatformTestCase {

    private @Nullable String previousJkBin;
    private List<String> imlsBefore = List.of();

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        previousJkBin = System.getProperty("jk.bin");
        imlsBefore = imlFiles(checkoutRoot());
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
            // A red run must not leave module files in the checkout: drop only what this test added.
            Path checkout = checkoutRoot();
            for (String iml : imlFiles(checkout)) {
                if (!imlsBefore.contains(iml)) Files.deleteIfExists(checkout.resolve(iml));
            }
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
        Path checkout = checkoutRoot();
        assumeJkOnPath();

        JkCliRunner.Result sources = JkCliRunner.run(checkout.toFile(), "sync", "--sources");
        assertTrue("jk sync --sources: " + sources.stderr(), sources.ok());

        Import result = importProject(checkout.toFile());
        assertNull("sync failed: " + result.failure, result.failure);
        DataNode<ProjectData> graph = requireNonNull(result.graph);

        List<DataNode<ModuleData>> resolved = modules(graph);
        assertTrue("jk's workspace has dozens of modules, resolved " + resolved.size(), resolved.size() >= 30);
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
        assertTrue("modules with source roots: " + withSourceRoots, withSourceRoots >= 30);

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
        assertTrue("jk depends on more than a hundred artifacts: " + libraries.length, libraries.length > 100);
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
        System.out.println("jk workspace import: " + resolved.size() + " modules, " + libraries.length + " libraries, "
                + withSources + " with sources");

        Sdk projectSdk =
                requireNonNull(ProjectRootManager.getInstance(getProject()).getProjectSdk(), "project SDK set");
        assertTrue(projectSdk.getName(), projectSdk.getName().startsWith("jk-"));

        assertGutterRoutesThroughJk(checkout);

        PlatformTestUtil.saveProject(getProject());
        assertEquals("no .iml written into the checkout", imlsBefore, imlFiles(checkout));
        assertFalse(
                ".idea/modules.xml not written into the checkout", Files.exists(checkout.resolve(".idea/modules.xml")));
    }

    /**
     * A test class of an imported module resolves, from its PSI alone, to a JumpKick run
     * configuration: {@code jk test -m <module> --class <fqcn>} from the workspace root, and under
     * Debug the same command with the JDWP address the debugger attaches to.
     */
    private void assertGutterRoutesThroughJk(Path checkout) throws Exception {
        Path source = checkout.resolve("shared/host/src/test/java/cc/jumpkick/host/PathUtilTest.java");
        assertTrue(source.toString(), Files.isRegularFile(source));
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
        assertEquals(checkout.toString(), config.rootDir());
        assertEquals("shared/host", config.moduleRel());
        assertEquals(cls.getQualifiedName(), config.className());
        assertEquals("jk test PathUtilTest", config.getName());
        assertTrue(new JkRunConfigurationProducer().isConfigurationFromContext(config, context));
        assertEquals(
                List.of("test", "-m", "shared/host", "--class", cls.getQualifiedName(), "--debug-jvm=localhost:7007"),
                JkCommandLines.args(config.kind(), config.moduleRel(), config.className(), "localhost:7007"));
    }

    public void test_a_failing_cli_surfaces_its_first_error_line() throws Exception {
        Path checkout = checkoutRoot();
        Path fake = createTempDir("fake-jk").toPath().resolve("jk");
        Files.writeString(
                fake, "#!/bin/sh\necho 'jk: engine refused the connection' >&2\necho 'second line' >&2\nexit 3\n");
        assertTrue(fake.toFile().setExecutable(true));
        System.setProperty("jk.bin", fake.toString());

        Import result = importProject(checkout.toFile());
        assertNull(result.graph);
        String failure = requireNonNull(result.failure);
        assertTrue(failure, failure.contains("jk: engine refused the connection"));
        assertFalse(failure, failure.contains("second line"));
    }

    private record Import(
            @Nullable DataNode<ProjectData> graph, @Nullable String failure) {}

    private Import importProject(File base) {
        JkSync.link(getProject(), base);
        assertTrue(JkSync.isLinked(getProject(), base));
        List<DataNode<ProjectData>> graph = new ArrayList<>();
        List<String> failure = new ArrayList<>();
        ImportSpecBuilder spec = new ImportSpecBuilder(getProject(), JkSystem.ID)
                .use(ProgressExecutionMode.MODAL_SYNC)
                .dontReportRefreshErrors()
                .callback(new ExternalProjectRefreshCallback() {
                    @Override
                    public void onSuccess(@Nullable DataNode<ProjectData> node) {
                        if (node == null) return;
                        graph.add(node);
                        ProjectDataManager.getInstance().importData(node, getProject());
                    }

                    @Override
                    public void onFailure(String message, @Nullable String details) {
                        failure.add(message + (details == null ? "" : "\n" + details));
                    }
                });
        ExternalSystemUtil.refreshProject(JkSync.projectPath(base), spec);
        return new Import(graph.isEmpty() ? null : graph.get(0), failure.isEmpty() ? null : failure.get(0));
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

    /** The checkout root: the nearest ancestor of the working dir holding {@code jk.toml} and this plugin. */
    private static Path checkoutRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("jk.toml")) && Files.isDirectory(dir.resolve("clients/intellij")))
                return dir;
            dir = dir.getParent();
        }
        throw new AssertionError("no checkout root above " + Path.of("").toAbsolutePath());
    }

    private static void assumeJkOnPath() {
        boolean present;
        try {
            Process p = new ProcessBuilder(JkBin.path(), "--version")
                    .redirectErrorStream(true)
                    .start();
            p.getInputStream().readAllBytes();
            present = p.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            present = false;
        }
        if (!present)
            System.err.println(
                    "SKIPPED " + JkWorkspaceImportTest.class.getSimpleName() + ": no jk on PATH (JK_BIN / -Djk.bin)");
        Assume.assumeTrue("jk on PATH", present);
    }

    private static List<String> imlFiles(Path root) throws IOException {
        List<String> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.getFileName().toString().endsWith(".iml"))
                    .filter(p -> !p.toString().contains("/.git/"))
                    .forEach(p -> out.add(root.relativize(p).toString()));
        }
        return out;
    }
}
