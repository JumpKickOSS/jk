// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.extract.WorkspaceFacts;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.schema.Lane;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.SourceFileAttribute;
import java.lang.constant.ClassDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every guard lane counts the modules a run has not seen with one definition: a module is unseen
 * while a main source file has no class or while it has packaged nothing.
 */
class UnseenModulesTest {

    /**
     * Five modules, each with one source file {@code p/<m>/<M>.java}:
     * {@code a} compiled, nothing packaged; {@code b} packaged, its source not compiled;
     * {@code c} compiled and packaged; {@code d} neither; {@code e} packaged, not compiled.
     */
    private static List<Path> workspace(Path root) throws IOException {
        List<String> names = List.of("a", "b", "c", "d", "e");
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.acme"
                name = "ws"
                version = "1.0.0"
                java = 25
                [workspace]
                modules = ["a", "b", "c", "d", "e"]
                """);
        List<Path> modules = new ArrayList<>();
        for (String m : names) {
            Path dir = Files.createDirectories(root.resolve(m));
            Files.writeString(
                    dir.resolve("jk.toml"),
                    "group = \"com.acme\"\nname = \"" + m + "\"\nversion = \"1.0.0\"\njava = 25\n");
            Path source = dir.resolve("src/main/java/p/" + m + "/" + simple(m) + ".java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, "package p." + m + "; public class " + simple(m) + " {}\n");
            modules.add(dir);
        }
        for (String m : List.of("a", "c")) compile(root.resolve(m), m);
        for (OutputArtifacts.Module m : OutputArtifacts.of(root, modules, null)) {
            if (List.of("b", "c", "e").contains(m.module())) {
                Files.createDirectories(m.jar().getParent());
                OutputEvaluatorTest.jar(m.jar(), Map.of(), "p/" + m.module() + "/" + simple(m.module()) + ".class");
            }
        }
        return modules;
    }

    private static String simple(String module) {
        return module.toUpperCase(Locale.ROOT);
    }

    /** {@code p/<m>/<M>.class} in the module's classes tree, compiled from {@code <M>.java}. */
    private static void compile(Path module, String m) throws IOException {
        String internal = "p/" + m + "/" + simple(m);
        byte[] bytes = ClassFile.of()
                .build(ClassDesc.ofInternalName(internal), cb -> cb.with(SourceFileAttribute.of(simple(m) + ".java")));
        Path file = module.resolve("target/classes/" + internal + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    @Test
    void a_module_is_unseen_when_it_is_not_compiled_or_not_packaged(@TempDir Path root) throws IOException {
        List<Path> modules = workspace(root);

        assertThat(UnseenModules.of(root, modules)).containsExactly("a", "b", "d", "e");
    }

    @Test
    void the_bytecode_and_output_views_alone_would_count_this_workspace_differently(@TempDir Path root)
            throws IOException {
        List<Path> modules = workspace(root);
        FactsIndex merged = WorkspaceFacts.merged(root, modules);
        EvalContext workspaceLane =
                new EvalContext(Lane.WORKSPACE, root, "", null, modules, () -> merged, () -> null, List::of);

        assertThat(ClassesView.uncompiled(workspaceLane))
                .as("the workspace lane's own view: b, d and e have a source with no class")
                .hasSize(3);
        assertThat(OutputArtifacts.unbuilt(OutputArtifacts.of(root, modules, null)))
                .as("the output lane's own view: a and d packaged nothing")
                .containsExactly("a", "d");
        assertThat(UnseenModules.of(root, modules))
                .as("one count for both: every module either view lacks")
                .hasSize(4);
    }

    @Test
    void a_fully_built_workspace_has_no_unseen_module(@TempDir Path root) throws IOException {
        List<Path> modules = workspace(root);
        for (String m : List.of("b", "d", "e")) compile(root.resolve(m), m);
        for (OutputArtifacts.Module m : OutputArtifacts.of(root, modules, null)) {
            if (m.module().equals("a") || m.module().equals("d")) {
                Files.createDirectories(m.jar().getParent());
                OutputEvaluatorTest.jar(m.jar(), Map.of(), "x.class");
            }
        }

        assertThat(UnseenModules.of(root, modules)).isEmpty();
    }
}
