// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.extract;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.layout.BuildLayout;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The workspace lane reads every module's classes, whether or not that module has indexed them yet. */
class WorkspaceFactsTest {

    @Test
    void a_module_that_has_compiled_but_not_indexed_is_still_read(@TempDir Path root) throws Exception {
        Path module = root.resolve("app");
        Files.createDirectories(module);
        Path classes = BuildLayout.moduleTargetDir(module).resolve("classes").resolve("main");
        String resource = WorkspaceFactsTest.class.getName().replace('.', '/') + ".class";
        Path target = classes.resolve(resource);
        Files.createDirectories(target.getParent());
        try (InputStream in = WorkspaceFactsTest.class.getClassLoader().getResourceAsStream(resource)) {
            Files.copy(in, target);
        }
        Path idx = FactsIndexing.indexPath(BuildLayout.moduleTargetDir(module), "main");
        assertThat(idx).doesNotExist();

        FactsIndex merged = WorkspaceFacts.merged(root, List.of(module, root.resolve("web")));

        assertThat(merged.classes())
                .containsKey(WorkspaceFactsTest.class.getName().replace('.', '/'));
        assertThat(idx).as("the read left the index behind for the module lane").exists();
    }

    @Test
    void a_module_with_no_classes_contributes_nothing(@TempDir Path root) throws Exception {
        Path web = root.resolve("web");
        Files.createDirectories(web);
        assertThat(WorkspaceFacts.merged(root, List.of(web)).classes()).isEmpty();
    }

    @Test
    void a_class_whose_stamped_source_is_gone_is_not_in_the_workspace_view(@TempDir Path root) throws Exception {
        Path module = root.resolve("app");
        String internal = WorkspaceFactsTest.class.getName().replace('.', '/');
        Path classes = BuildLayout.moduleTargetDir(module).resolve("classes");
        Path target = classes.resolve(internal + ".class");
        Files.createDirectories(target.getParent());
        try (InputStream in = WorkspaceFactsTest.class.getClassLoader().getResourceAsStream(internal + ".class")) {
            Files.copy(in, target);
        }
        String tail = internal + ".java";
        Path source = module.resolve("src/main/java").resolve(tail);
        Files.writeString(
                classes.resolve(BuildStamps.JAVA),
                "TASK compile-main\n" + BuildStamps.SOURCE_LINE + "none " + source + "\n");
        String pkg = WorkspaceFactsTest.class.getPackageName();

        assertThat(WorkspaceFacts.removedClasses(root, module)).containsExactly(internal);
        assertThat(WorkspaceFacts.merged(root, List.of(module)).classes()).doesNotContainKey(internal);
        assertThat(WorkspaceFacts.packageModuleDirs(root, List.of(module))).doesNotContainKey(pkg);

        Path moved = module.resolve("src/main/kotlin").resolve(tail);
        Files.createDirectories(moved.getParent());
        Files.writeString(moved, "");
        assertThat(WorkspaceFacts.removedClasses(root, module))
                .as("another source root holds the file again: its class is the module's")
                .isEmpty();
        assertThat(WorkspaceFacts.merged(root, List.of(module)).classes()).containsKey(internal);
        assertThat(WorkspaceFacts.packageModuleDirs(root, List.of(module))).containsKey(pkg);
    }
}
