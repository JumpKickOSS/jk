// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.extract;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.facts.FactsIndex;
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
        Path classes =
                BuildLayout.moduleTargetDir(root, module).resolve("classes").resolve("main");
        String resource = WorkspaceFactsTest.class.getName().replace('.', '/') + ".class";
        Path target = classes.resolve(resource);
        Files.createDirectories(target.getParent());
        try (InputStream in = WorkspaceFactsTest.class.getClassLoader().getResourceAsStream(resource)) {
            Files.copy(in, target);
        }
        Path idx = FactsIndexing.indexPath(BuildLayout.moduleTargetDir(root, module), "main");
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
}
