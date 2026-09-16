// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.idea;

import com.intellij.externalSystem.JavaModuleData;
import com.intellij.externalSystem.JavaProjectData;
import com.intellij.openapi.externalSystem.model.DataNode;
import com.intellij.openapi.externalSystem.model.ProjectKeys;
import com.intellij.openapi.externalSystem.model.project.ContentRootData;
import com.intellij.openapi.externalSystem.model.project.ExternalSystemSourceType;
import com.intellij.openapi.externalSystem.model.project.LibraryData;
import com.intellij.openapi.externalSystem.model.project.LibraryDependencyData;
import com.intellij.openapi.externalSystem.model.project.LibraryLevel;
import com.intellij.openapi.externalSystem.model.project.LibraryPathType;
import com.intellij.openapi.externalSystem.model.project.ModuleData;
import com.intellij.openapi.externalSystem.model.project.ModuleDependencyData;
import com.intellij.openapi.externalSystem.model.project.ModuleSdkData;
import com.intellij.openapi.externalSystem.model.project.ProjectData;
import com.intellij.openapi.externalSystem.model.project.ProjectSdkData;
import com.intellij.openapi.roots.DependencyScope;
import com.intellij.pom.java.LanguageLevel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import org.jetbrains.annotations.Nullable;

/**
 * Turns a {@link JkWireModel} into the {@link DataNode} tree {@code ProjectDataManager} applies:
 * one module per {@code moduleDirs} entry (plus a root module holding the workspace directory when
 * no module lives there), content roots with every discovered source, test and resource root,
 * generated-source roots, project-level libraries with sources jars, module and library
 * dependencies in IntelliJ's scope vocabulary, per-module SDK and language level, and compiler
 * output pointed at the IDE-owned {@code target/jdt} directories, never at jk's {@code
 * target/classes}. Pure: no IDE services, so it is testable over captured JSON.
 */
final class JkProjectGraph {

    /** Source-root discovery over a module dir and its workspace root ({@link JkSourceRoots#of}). */
    interface Roots extends BiFunction<Path, Path, List<JkSourceRoots.Root>> {}

    /** IDE-owned output tree, relative to the workspace root, for the project-level default. */
    static final String PROJECT_OUTPUT = "target/jdt";

    private JkProjectGraph() {}

    static DataNode<ProjectData> build(JkWireModel model, Roots roots) {
        Path wsRoot = Path.of(model.wsRoot).toAbsolutePath().normalize();
        String ws = wsRoot.toString();
        ProjectData projectData = new ProjectData(JkSystem.ID, model.rootName, ws, ws);
        DataNode<ProjectData> project = new DataNode<>(ProjectKeys.PROJECT, projectData, null);

        JkWireModel.Sdk def = model.defaultSdk;
        if (!def.name().isEmpty()) project.createChild(ProjectSdkData.KEY, new ProjectSdkData(def.name()));
        LanguageLevel projectLevel = level(def.level());
        project.createChild(
                JavaProjectData.KEY,
                new JavaProjectData(
                        JkSystem.ID,
                        wsRoot.resolve(PROJECT_OUTPUT).toString(),
                        projectLevel,
                        projectLevel == null ? null : String.valueOf(def.level())));

        Map<String, LibraryData> libraries = new LinkedHashMap<>();
        for (JkWireModel.Lib lib : model.libs) {
            LibraryData data = library(lib);
            libraries.put(lib.name(), data);
            project.createChild(ProjectKeys.LIBRARY, data);
        }

        List<DataNode<ModuleData>> moduleNodes = new ArrayList<>(model.modules.size());
        Map<String, DataNode<ModuleData>> byName = new LinkedHashMap<>();
        boolean rootIsModule = false;
        for (int i = 0; i < model.modules.size(); i++) {
            JkWireModel.Module m = model.modules.get(i);
            Path dir = Path.of(m.dir()).toAbsolutePath().normalize();
            rootIsModule |= dir.equals(wsRoot);
            DataNode<ModuleData> node = project.createChild(ProjectKeys.MODULE, moduleData(m, dir));
            fillModule(node, m, dir, wsRoot, roots, hasProcessors(model, i));
            moduleNodes.add(node);
            byName.put(m.name(), node);
        }
        if (!rootIsModule) rootModule(project, model, wsRoot, byName.keySet());

        for (JkWireModel.SiblingRef ref : model.siblingRefs) {
            DataNode<ModuleData> owner = nodeAt(moduleNodes, ref.module());
            DataNode<ModuleData> target = byName.get(ref.name());
            if (owner == null || target == null) continue;
            ModuleDependencyData dep = new ModuleDependencyData(owner.getData(), target.getData());
            boolean testScope =
                    JkWireModel.SCOPE_TEST.equals(ref.scope()) || JkWireModel.SCOPE_TEST_KIND.equals(ref.scope());
            dep.setScope(testScope ? DependencyScope.TEST : DependencyScope.COMPILE);
            dep.setProductionOnTestDependency(JkWireModel.SCOPE_TEST_KIND.equals(ref.scope())
                    || JkWireModel.SCOPE_COMPILE_TEST_KIND.equals(ref.scope()));
            owner.createChild(ProjectKeys.MODULE_DEPENDENCY, dep);
        }

        Map<DataNode<ModuleData>, Map<LibraryData, DependencyScope>> libScopes = new LinkedHashMap<>();
        for (JkWireModel.LibEntry e : model.libEntries) {
            DataNode<ModuleData> owner = nodeAt(moduleNodes, e.module());
            LibraryData lib = libraries.get(e.libName());
            if (owner == null || lib == null) continue;
            libScopes
                    .computeIfAbsent(owner, k -> new LinkedHashMap<>())
                    .merge(lib, scope(e.scopes()), JkProjectGraph::wider);
        }
        for (var perModule : libScopes.entrySet()) {
            for (var entry : perModule.getValue().entrySet()) {
                LibraryDependencyData dep =
                        new LibraryDependencyData(perModule.getKey().getData(), entry.getKey(), LibraryLevel.PROJECT);
                dep.setScope(entry.getValue());
                perModule.getKey().createChild(ProjectKeys.LIBRARY_DEPENDENCY, dep);
            }
        }
        return project;
    }

    private static ModuleData moduleData(JkWireModel.Module m, Path dir) {
        String d = dir.toString();
        ModuleData data = new ModuleData(m.name(), JkSystem.ID, JkSystem.JAVA_MODULE_TYPE, m.name(), d, d);
        data.setInheritProjectCompileOutputPath(false);
        data.setCompileOutputPath(ExternalSystemSourceType.SOURCE, m.jdtClassesDir());
        data.setCompileOutputPath(ExternalSystemSourceType.TEST, m.jdtTestClassesDir());
        return data;
    }

    private static void fillModule(
            DataNode<ModuleData> node, JkWireModel.Module m, Path dir, Path wsRoot, Roots roots, boolean processors) {
        ContentRootData content = new ContentRootData(JkSystem.ID, dir.toString());
        for (JkSourceRoots.Root r : roots.apply(dir, wsRoot)) {
            content.storePath(sourceType(r.kind()), dir.resolve(r.relative()).toString());
        }
        content.storePath(
                ExternalSystemSourceType.EXCLUDED, dir.resolve("target").toString());
        node.createChild(ProjectKeys.CONTENT_ROOT, content);

        if (processors || Files.isDirectory(Path.of(m.genSrcDir()))) {
            generatedRoot(node, content, dir, m.genSrcDir(), ExternalSystemSourceType.SOURCE_GENERATED);
            generatedRoot(node, content, dir, m.genTestSrcDir(), ExternalSystemSourceType.TEST_GENERATED);
        }

        if (!m.sdk().name().isEmpty())
            node.createChild(ModuleSdkData.KEY, new ModuleSdkData(m.sdk().name()));
        int level = m.javaRelease() > 0 ? m.javaRelease() : m.sdk().level();
        LanguageLevel languageLevel = level(level);
        if (languageLevel != null) {
            node.createChild(JavaModuleData.KEY, new JavaModuleData(JkSystem.ID, languageLevel, String.valueOf(level)));
        }
    }

    /**
     * A generated-source root inside the module dir joins its content root; one outside it (the
     * workspace-level {@code target/} tree) becomes a content root of its own.
     */
    private static void generatedRoot(
            DataNode<ModuleData> node, ContentRootData content, Path dir, String gen, ExternalSystemSourceType type) {
        if (gen.isEmpty()) return;
        Path path = Path.of(gen).toAbsolutePath().normalize();
        if (path.startsWith(dir)) {
            content.storePath(type, path.toString());
            return;
        }
        ContentRootData own = new ContentRootData(JkSystem.ID, path.toString());
        own.storePath(type, path.toString());
        node.createChild(ProjectKeys.CONTENT_ROOT, own);
    }

    /** The workspace directory as a module of its own so manifests and docs are in the project. */
    private static void rootModule(DataNode<ProjectData> project, JkWireModel model, Path wsRoot, Set<String> taken) {
        String name = taken.contains(model.rootName) ? model.rootName + "-workspace" : model.rootName;
        String ws = wsRoot.toString();
        ModuleData data = new ModuleData(name, JkSystem.ID, JkSystem.JAVA_MODULE_TYPE, name, ws, ws);
        data.setInheritProjectCompileOutputPath(true);
        DataNode<ModuleData> node = project.createChild(ProjectKeys.MODULE, data);
        ContentRootData content = new ContentRootData(JkSystem.ID, ws);
        content.storePath(
                ExternalSystemSourceType.EXCLUDED, wsRoot.resolve("target").toString());
        node.createChild(ProjectKeys.CONTENT_ROOT, content);
        if (!model.defaultSdk.name().isEmpty())
            node.createChild(ModuleSdkData.KEY, new ModuleSdkData(model.defaultSdk.name()));
    }

    private static LibraryData library(JkWireModel.Lib lib) {
        LibraryData data = new LibraryData(JkSystem.ID, lib.name());
        data.addPath(LibraryPathType.BINARY, lib.jar());
        if (lib.sources() != null) data.addPath(LibraryPathType.SOURCE, lib.sources());
        // group:artifact[:type[:classifier]]:version — the version is always last.
        String[] c = lib.name().split(":");
        if (c.length >= 3) {
            data.setGroup(c[0]);
            data.setArtifactId(c[1]);
            data.setVersion(c[c.length - 1]);
        }
        return data;
    }

    private static boolean hasProcessors(JkWireModel model, int module) {
        for (JkWireModel.ProcessorJar p : model.processorJars) if (p.module() == module) return true;
        return false;
    }

    private static @Nullable DataNode<ModuleData> nodeAt(List<DataNode<ModuleData>> nodes, int i) {
        return i >= 0 && i < nodes.size() ? nodes.get(i) : null;
    }

    private static ExternalSystemSourceType sourceType(JkSourceRoots.Kind kind) {
        return switch (kind) {
            case SOURCE -> ExternalSystemSourceType.SOURCE;
            case RESOURCE -> ExternalSystemSourceType.RESOURCE;
            case TEST -> ExternalSystemSourceType.TEST;
            case TEST_RESOURCE -> ExternalSystemSourceType.TEST_RESOURCE;
        };
    }

    /** jk scopes → the most permissive IntelliJ scope, as the {@code .iml} export maps them. */
    static DependencyScope scope(List<String> scopes) {
        Set<String> s = new HashSet<>(scopes);
        if (s.contains("EXPORT") || s.contains("MAIN")) return DependencyScope.COMPILE;
        if (s.contains("PROVIDED")) return DependencyScope.PROVIDED;
        if (s.contains("RUNTIME")) return DependencyScope.RUNTIME;
        if (s.contains("TEST")) return DependencyScope.TEST;
        return DependencyScope.COMPILE;
    }

    private static DependencyScope wider(DependencyScope a, DependencyScope b) {
        return rank(a) <= rank(b) ? a : b;
    }

    private static int rank(DependencyScope s) {
        return switch (s) {
            case COMPILE -> 0;
            case PROVIDED -> 1;
            case RUNTIME -> 2;
            case TEST -> 3;
        };
    }

    /** The IDE's level for a Java feature release; the highest it knows when it is newer than the IDE. */
    static @Nullable LanguageLevel level(int feature) {
        if (feature <= 0) return null;
        LanguageLevel level = LanguageLevel.parse(String.valueOf(feature));
        return level != null ? level : LanguageLevel.HIGHEST;
    }
}
