// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.pom.java.LanguageLevel;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The resolver's output tree over the acme model: what IntelliJ will be told, node by node. */
class JkProjectGraphTest {

    @TempDir
    Path tmp;

    private AcmeFixture acme;
    private DataNode<ProjectData> project;

    @BeforeEach
    void build() throws IOException {
        acme = AcmeFixture.create(tmp);
        project = JkProjectGraph.build(acme.model(), JkSourceRoots::of);
    }

    @Test
    void the_project_carries_the_default_sdk_and_an_ide_owned_output_root() {
        assertThat(project.getData().getExternalName()).isEqualTo("acme");
        assertThat(project.getData().getLinkedExternalProjectPath()).isEqualTo(si(acme.ws));
        assertThat(child(project, ProjectSdkData.KEY).getSdkName()).isEqualTo("jk-temurin-25");
        JavaProjectData java = child(project, JavaProjectData.KEY);
        assertThat(java.getCompileOutputPath())
                .isEqualTo(acme.ws.resolve("target/jdt").toString());
        // The level itself is read through the IDE registry, which a test without an application
        // has no values for; the bytecode target is the same number, stored as written.
        assertThat(java.getTargetBytecodeVersion()).isEqualTo("25");
    }

    @Test
    void every_module_resolves_plus_a_root_module_for_the_workspace_dir() {
        assertThat(moduleNames()).isEqualTo(List.of("acme-core", "acme-app", "acme"));
        ModuleData root = module("acme").getData();
        assertThat(root.isInheritProjectCompileOutputPath()).isTrue();
        ContentRootData rootContent =
                children(module("acme"), ProjectKeys.CONTENT_ROOT).get(0);
        assertThat(rootContent.getRootPath()).isEqualTo(si(acme.ws));
        assertThat(paths(rootContent, ExternalSystemSourceType.EXCLUDED))
                .isEqualTo(Set.of(si(acme.ws.resolve("target"))));
    }

    @Test
    void compiler_output_is_the_jdt_tree_never_target_classes() {
        for (String name : List.of("acme-core", "acme-app")) {
            ModuleData m = module(name).getData();
            assertThat(m.isInheritProjectCompileOutputPath()).isFalse();
            String main = m.getCompileOutputPath(ExternalSystemSourceType.SOURCE);
            String test = m.getCompileOutputPath(ExternalSystemSourceType.TEST);
            assertThat(main).endsWith("/target/jdt/classes/main");
            assertThat(test).endsWith("/target/jdt/classes/test");
        }
    }

    @Test
    void content_roots_carry_every_discovered_root_by_kind_and_exclude_target() {
        DataNode<ModuleData> core = module("acme-core");
        ContentRootData content = children(core, ProjectKeys.CONTENT_ROOT).get(0);
        String dir = si(acme.ws.resolve("core"));
        assertThat(content.getRootPath()).isEqualTo(dir);
        assertThat(paths(content, ExternalSystemSourceType.SOURCE)).isEqualTo(Set.of(dir + "/src"));
        assertThat(paths(content, ExternalSystemSourceType.RESOURCE)).isEqualTo(Set.of(dir + "/resources"));
        assertThat(paths(content, ExternalSystemSourceType.TEST))
                .isEqualTo(Set.of(dir + "/test/src", dir + "/integration/src"));
        assertThat(paths(content, ExternalSystemSourceType.TEST_RESOURCE)).isEqualTo(Set.of(dir + "/test/resources"));
        assertThat(paths(content, ExternalSystemSourceType.EXCLUDED)).isEqualTo(Set.of(dir + "/target"));

        ContentRootData app =
                children(module("acme-app"), ProjectKeys.CONTENT_ROOT).get(0);
        String appDir = si(acme.ws.resolve("app"));
        assertThat(paths(app, ExternalSystemSourceType.TEST))
                .isEqualTo(Set.of(appDir + "/src/test/java", appDir + "/src/guard/java"));
    }

    @Test
    void generated_roots_outside_the_module_dir_are_content_roots_of_their_own() {
        List<ContentRootData> appRoots = children(module("acme-app"), ProjectKeys.CONTENT_ROOT);
        assertThat(appRoots).hasSize(3); // module dir + generated main + generated test
        String gen = si(acme.ws.resolve("target/app/generated/sources/annotations"));
        assertThat(paths(appRoots.get(1), ExternalSystemSourceType.SOURCE_GENERATED))
                .isEqualTo(Set.of(gen + "/main"));
        assertThat(paths(appRoots.get(2), ExternalSystemSourceType.TEST_GENERATED))
                .isEqualTo(Set.of(gen + "/test"));
        // core has no processors and no generated dir on disk: no generated roots.
        assertThat(children(module("acme-core"), ProjectKeys.CONTENT_ROOT)).hasSize(1);
    }

    @Test
    void libraries_are_project_level_with_sources_and_coordinates() {
        List<LibraryData> libs = children(project, ProjectKeys.LIBRARY);
        assertThat(libs).hasSize(3);
        LibraryData slf4j = libs.get(0);
        assertThat(slf4j.getExternalName()).isEqualTo("org.slf4j:slf4j-api:jar::2.0.17");
        assertThat(slf4j.getGroupId()).isEqualTo("org.slf4j");
        assertThat(slf4j.getArtifactId()).isEqualTo("slf4j-api");
        assertThat(slf4j.getVersion()).isEqualTo("2.0.17");
        assertThat(slf4j.getPaths(LibraryPathType.BINARY))
                .isEqualTo(Set.of(si(acme.m2.resolve("org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar"))));
        assertThat(slf4j.getPaths(LibraryPathType.SOURCE))
                .isEqualTo(Set.of(si(acme.m2.resolve("org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17-sources.jar"))));
        assertThat(libs.get(1).getPaths(LibraryPathType.SOURCE).isEmpty()).isTrue();
    }

    @Test
    void library_dependencies_take_the_most_permissive_intellij_scope() {
        assertThat(libScopes("acme-core"))
                .isEqualTo(List.of(
                        "org.slf4j:slf4j-api:jar::2.0.17=COMPILE", "org.junit.jupiter:junit-jupiter:jar::5.13.4=TEST"));
        assertThat(libScopes("acme-app"))
                .isEqualTo(List.of(
                        "org.slf4j:slf4j-api:jar::2.0.17=COMPILE",
                        "org.junit.jupiter:junit-jupiter:jar::5.13.4=TEST",
                        "org.projectlombok:lombok:jar::1.18.40=PROVIDED"));
    }

    @Test
    void a_tests_kind_sibling_edge_is_one_module_dependency_with_production_on_test() {
        List<ModuleDependencyData> deps = children(module("acme-app"), ProjectKeys.MODULE_DEPENDENCY);
        assertThat(deps).hasSize(1);
        assertThat(deps.get(0).getTarget().getExternalName()).isEqualTo("acme-core");
        assertThat(deps.get(0).getScope()).isEqualTo(DependencyScope.COMPILE);
        assertThat(deps.get(0).isProductionOnTestDependency()).isTrue();
        assertThat(children(module("acme-core"), ProjectKeys.MODULE_DEPENDENCY).isEmpty())
                .isTrue();
    }

    @Test
    void each_module_names_its_sdk_and_language_level() {
        assertThat(child(module("acme-core"), ModuleSdkData.KEY).getSdkName()).isEqualTo("jk-temurin-17");
        assertThat(child(module("acme-app"), ModuleSdkData.KEY).getSdkName()).isEqualTo("jk-temurin-25");
        assertThat(child(module("acme"), ModuleSdkData.KEY).getSdkName()).isEqualTo("jk-temurin-25");
        JavaModuleData core = child(module("acme-core"), JavaModuleData.KEY);
        assertThat(core.getLanguageLevel()).isEqualTo(LanguageLevel.parse("17"));
        assertThat(core.getTargetBytecodeVersion()).isEqualTo("17");
        assertThat(children(module("acme"), JavaModuleData.KEY).isEmpty()).isTrue();
    }

    @Test
    void the_root_module_yields_its_name_to_a_module_that_already_has_it() {
        String json = acme.json.replace("\"rootName\":\"acme\"", "\"rootName\":\"acme-app\"");
        DataNode<ProjectData> p = JkProjectGraph.build(JkWireModel.parse(json), JkSourceRoots::of);
        assertThat(children(p, ProjectKeys.MODULE).stream()
                        .map(ModuleData::getExternalName)
                        .toList())
                .isEqualTo(List.of("acme-core", "acme-app", "acme-app-workspace"));
    }

    @Test
    void a_release_newer_than_the_ide_maps_to_its_highest_level() {
        assertThat(JkProjectGraph.level(17)).isEqualTo(LanguageLevel.JDK_17);
        assertThat(JkProjectGraph.level(99)).isEqualTo(LanguageLevel.HIGHEST);
        assertThat(JkProjectGraph.level(0)).isNull();
    }

    @Test
    void scope_mapping_mirrors_the_iml_export() {
        assertThat(JkProjectGraph.scope(List.of("TEST", "EXPORT"))).isEqualTo(DependencyScope.COMPILE);
        assertThat(JkProjectGraph.scope(List.of("PROVIDED", "PROCESSOR"))).isEqualTo(DependencyScope.PROVIDED);
        assertThat(JkProjectGraph.scope(List.of("RUNTIME", "TEST"))).isEqualTo(DependencyScope.RUNTIME);
        assertThat(JkProjectGraph.scope(List.of("TEST"))).isEqualTo(DependencyScope.TEST);
        assertThat(JkProjectGraph.scope(List.of("PLATFORM"))).isEqualTo(DependencyScope.COMPILE);
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
        assertThat(found.size()).as("one " + key + " under " + node.getData()).isEqualTo(1);
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

    /** {@code path} as the model spells it: with forward slashes, on every OS. */
    private static String si(Path path) {
        return FileUtil.toSystemIndependentName(path.toString());
    }
}
