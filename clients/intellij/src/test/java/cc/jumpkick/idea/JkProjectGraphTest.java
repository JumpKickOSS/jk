// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.intellij.externalSystem.JavaModuleData;
import com.intellij.externalSystem.JavaProjectData;
import com.intellij.openapi.externalSystem.model.DataNode;
import com.intellij.openapi.externalSystem.model.Key;
import com.intellij.openapi.externalSystem.model.ProjectKeys;
import com.intellij.openapi.externalSystem.model.project.ContentRootData;
import com.intellij.openapi.externalSystem.model.project.ExternalSystemSourceType;
import com.intellij.openapi.externalSystem.model.project.LibraryData;
import com.intellij.openapi.externalSystem.model.project.LibraryDependencyData;
import com.intellij.openapi.externalSystem.model.project.LibraryPathType;
import com.intellij.openapi.externalSystem.model.project.ModuleData;
import com.intellij.openapi.externalSystem.model.project.ModuleDependencyData;
import com.intellij.openapi.externalSystem.model.project.ModuleSdkData;
import com.intellij.openapi.externalSystem.model.project.ProjectData;
import com.intellij.openapi.externalSystem.model.project.ProjectSdkData;
import com.intellij.openapi.roots.DependencyScope;
import com.intellij.pom.java.LanguageLevel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The resolver's output tree over the acme model: what IntelliJ will be told, node by node. */
public class JkProjectGraphTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private AcmeFixture acme;
    private DataNode<ProjectData> project;

    @Before
    public void build() throws IOException {
        acme = AcmeFixture.create(tmp.getRoot().toPath());
        project = JkProjectGraph.build(acme.model(), JkSourceRoots::of);
    }

    @Test
    public void the_project_carries_the_default_sdk_and_an_ide_owned_output_root() {
        assertEquals("acme", project.getData().getExternalName());
        assertEquals(acme.ws.toString(), project.getData().getLinkedExternalProjectPath());
        assertEquals("jk-temurin-25", child(project, ProjectSdkData.KEY).getSdkName());
        JavaProjectData java = child(project, JavaProjectData.KEY);
        assertEquals(acme.ws.resolve("target/jdt").toString(), java.getCompileOutputPath());
        assertEquals(JkProjectGraph.level(25), java.getLanguageLevel());
    }

    @Test
    public void every_module_resolves_plus_a_root_module_for_the_workspace_dir() {
        assertEquals(List.of("acme-core", "acme-app", "acme"), moduleNames());
        ModuleData root = module("acme").getData();
        assertTrue(root.isInheritProjectCompileOutputPath());
        ContentRootData rootContent =
                children(module("acme"), ProjectKeys.CONTENT_ROOT).get(0);
        assertEquals(acme.ws.toString(), rootContent.getRootPath());
        assertEquals(
                Set.of(acme.ws.resolve("target").toString()), paths(rootContent, ExternalSystemSourceType.EXCLUDED));
    }

    @Test
    public void compiler_output_is_the_jdt_tree_never_target_classes() {
        for (String name : List.of("acme-core", "acme-app")) {
            ModuleData m = module(name).getData();
            assertFalse(m.isInheritProjectCompileOutputPath());
            String main = m.getCompileOutputPath(ExternalSystemSourceType.SOURCE);
            String test = m.getCompileOutputPath(ExternalSystemSourceType.TEST);
            assertNotNull(main);
            assertNotNull(test);
            assertTrue(main, main.endsWith("/target/jdt/classes/main"));
            assertTrue(test, test.endsWith("/target/jdt/classes/test"));
        }
    }

    @Test
    public void content_roots_carry_every_discovered_root_by_kind_and_exclude_target() {
        DataNode<ModuleData> core = module("acme-core");
        ContentRootData content = children(core, ProjectKeys.CONTENT_ROOT).get(0);
        String dir = acme.ws.resolve("core").toString();
        assertEquals(dir, content.getRootPath());
        assertEquals(Set.of(dir + "/src"), paths(content, ExternalSystemSourceType.SOURCE));
        assertEquals(Set.of(dir + "/resources"), paths(content, ExternalSystemSourceType.RESOURCE));
        assertEquals(
                Set.of(dir + "/test/src", dir + "/integration/src"), paths(content, ExternalSystemSourceType.TEST));
        assertEquals(Set.of(dir + "/test/resources"), paths(content, ExternalSystemSourceType.TEST_RESOURCE));
        assertEquals(Set.of(dir + "/target"), paths(content, ExternalSystemSourceType.EXCLUDED));

        ContentRootData app =
                children(module("acme-app"), ProjectKeys.CONTENT_ROOT).get(0);
        String appDir = acme.ws.resolve("app").toString();
        assertEquals(
                Set.of(appDir + "/src/test/java", appDir + "/src/guard/java"),
                paths(app, ExternalSystemSourceType.TEST));
    }

    @Test
    public void generated_roots_outside_the_module_dir_are_content_roots_of_their_own() {
        List<ContentRootData> appRoots = children(module("acme-app"), ProjectKeys.CONTENT_ROOT);
        assertEquals(3, appRoots.size()); // module dir + generated main + generated test
        String gen = acme.ws.resolve("target/app/generated/sources/annotations").toString();
        assertEquals(Set.of(gen + "/main"), paths(appRoots.get(1), ExternalSystemSourceType.SOURCE_GENERATED));
        assertEquals(Set.of(gen + "/test"), paths(appRoots.get(2), ExternalSystemSourceType.TEST_GENERATED));
        // core has no processors and no generated dir on disk: no generated roots.
        assertEquals(1, children(module("acme-core"), ProjectKeys.CONTENT_ROOT).size());
    }

    @Test
    public void libraries_are_project_level_with_sources_and_coordinates() {
        List<LibraryData> libs = children(project, ProjectKeys.LIBRARY);
        assertEquals(3, libs.size());
        LibraryData slf4j = libs.get(0);
        assertEquals("org.slf4j:slf4j-api:jar::2.0.17", slf4j.getExternalName());
        assertEquals("org.slf4j", slf4j.getGroupId());
        assertEquals("slf4j-api", slf4j.getArtifactId());
        assertEquals("2.0.17", slf4j.getVersion());
        assertEquals(
                Set.of(acme.m2
                        .resolve("org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar")
                        .toString()),
                slf4j.getPaths(LibraryPathType.BINARY));
        assertEquals(
                Set.of(acme.m2
                        .resolve("org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17-sources.jar")
                        .toString()),
                slf4j.getPaths(LibraryPathType.SOURCE));
        assertTrue(libs.get(1).getPaths(LibraryPathType.SOURCE).isEmpty());
    }

    @Test
    public void library_dependencies_take_the_most_permissive_intellij_scope() {
        assertEquals(
                List.of("org.slf4j:slf4j-api:jar::2.0.17=COMPILE", "org.junit.jupiter:junit-jupiter:jar::5.13.4=TEST"),
                libScopes("acme-core"));
        assertEquals(
                List.of(
                        "org.slf4j:slf4j-api:jar::2.0.17=COMPILE",
                        "org.junit.jupiter:junit-jupiter:jar::5.13.4=TEST",
                        "org.projectlombok:lombok:jar::1.18.40=PROVIDED"),
                libScopes("acme-app"));
    }

    @Test
    public void a_tests_kind_sibling_edge_is_one_module_dependency_with_production_on_test() {
        List<ModuleDependencyData> deps = children(module("acme-app"), ProjectKeys.MODULE_DEPENDENCY);
        assertEquals(1, deps.size());
        assertEquals("acme-core", deps.get(0).getTarget().getExternalName());
        assertEquals(DependencyScope.COMPILE, deps.get(0).getScope());
        assertTrue(deps.get(0).isProductionOnTestDependency());
        assertTrue(children(module("acme-core"), ProjectKeys.MODULE_DEPENDENCY).isEmpty());
    }

    @Test
    public void each_module_names_its_sdk_and_language_level() {
        assertEquals(
                "jk-temurin-17", child(module("acme-core"), ModuleSdkData.KEY).getSdkName());
        assertEquals(
                "jk-temurin-25", child(module("acme-app"), ModuleSdkData.KEY).getSdkName());
        assertEquals("jk-temurin-25", child(module("acme"), ModuleSdkData.KEY).getSdkName());
        JavaModuleData core = child(module("acme-core"), JavaModuleData.KEY);
        assertEquals(LanguageLevel.parse("17"), core.getLanguageLevel());
        assertEquals("17", core.getTargetBytecodeVersion());
        assertTrue(children(module("acme"), JavaModuleData.KEY).isEmpty());
    }

    @Test
    public void the_root_module_yields_its_name_to_a_module_that_already_has_it() {
        String json = acme.json.replace("\"rootName\":\"acme\"", "\"rootName\":\"acme-app\"");
        DataNode<ProjectData> p = JkProjectGraph.build(JkWireModel.parse(json), JkSourceRoots::of);
        assertEquals(
                List.of("acme-core", "acme-app", "acme-app-workspace"),
                children(p, ProjectKeys.MODULE).stream()
                        .map(ModuleData::getExternalName)
                        .toList());
    }

    @Test
    public void a_release_newer_than_the_ide_maps_to_its_highest_level() {
        assertEquals(LanguageLevel.JDK_17, JkProjectGraph.level(17));
        assertEquals(LanguageLevel.HIGHEST, JkProjectGraph.level(99));
        assertTrue(JkProjectGraph.level(0) == null);
    }

    @Test
    public void scope_mapping_mirrors_the_iml_export() {
        assertEquals(DependencyScope.COMPILE, JkProjectGraph.scope(List.of("TEST", "EXPORT")));
        assertEquals(DependencyScope.PROVIDED, JkProjectGraph.scope(List.of("PROVIDED", "PROCESSOR")));
        assertEquals(DependencyScope.RUNTIME, JkProjectGraph.scope(List.of("RUNTIME", "TEST")));
        assertEquals(DependencyScope.TEST, JkProjectGraph.scope(List.of("TEST")));
        assertEquals(DependencyScope.COMPILE, JkProjectGraph.scope(List.of("PLATFORM")));
    }

    private List<String> moduleNames() {
        return children(project, ProjectKeys.MODULE).stream()
                .map(ModuleData::getExternalName)
                .toList();
    }

    private DataNode<ModuleData> module(String name) {
        for (DataNode<?> n : project.getChildren()) {
            if (n.getKey().equals(ProjectKeys.MODULE) && name.equals(((ModuleData) n.getData()).getExternalName())) {
                @SuppressWarnings("unchecked")
                DataNode<ModuleData> m = (DataNode<ModuleData>) n;
                return m;
            }
        }
        throw new AssertionError("no module " + name + " in " + moduleNames());
    }

    private List<String> libScopes(String module) {
        List<String> out = new ArrayList<>();
        for (LibraryDependencyData d : children(module(module), ProjectKeys.LIBRARY_DEPENDENCY)) {
            out.add(d.getExternalName() + "=" + d.getScope().name());
        }
        return out;
    }

    private static <T> T child(DataNode<?> node, Key<T> key) {
        List<T> found = children(node, key);
        assertEquals("one " + key + " under " + node.getData(), 1, found.size());
        return found.get(0);
    }

    private static <T> List<T> children(DataNode<?> node, Key<T> key) {
        List<T> out = new ArrayList<>();
        for (DataNode<?> n : node.getChildren()) {
            if (n.getKey().equals(key)) {
                @SuppressWarnings("unchecked")
                T data = (T) n.getData();
                out.add(data);
            }
        }
        return out;
    }

    private static Set<String> paths(ContentRootData content, ExternalSystemSourceType type) {
        return content.getPaths(type).stream()
                .map(ContentRootData.SourceRoot::getPath)
                .collect(Collectors.toSet());
    }
}
