// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.graph;

import cc.jumpkick.layout.GraphLayout;
import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A rename proves itself before it answers, for the CLI and MCP alike: the engine compiles the
 * modules the rename wrote in (main and tests, as a job), then the graph worker checks that every
 * renamed symbol's new name has exactly the references the old one had, or undoes the rename whole
 * when the compile failed. The worker names the modules on the first line of its proof file; that
 * line is all the engine reads of it.
 */
final class RenameCheck {

    private RenameCheck() {}

    /** One more graph request with {@code options} added ({@code phase=verify}, {@code phase=undo}): its text. */
    interface Ask {
        String ask(List<String> options) throws IOException, InterruptedException;
    }

    /** Where the worker leaves a rename's proof. */
    static Path proof(Path root) {
        return GraphLayout.dir(root).resolve("rename").resolve("proof");
    }

    /** The line the rename's answer ends with: proven, rolled back, or why the check did not run. */
    static String prove(Path root, GraphRunner.@Nullable Compiler compiler, Ask ask)
            throws IOException, InterruptedException {
        if (compiler == null) return "";
        List<String> prefixes = modules(proof(root));
        Map<Path, JkBuild> all = GraphRunner.modules(root);
        List<String> args = new ArrayList<>();
        if (all.size() > 1) {
            for (Map.Entry<Path, JkBuild> m : all.entrySet()) {
                String rel = root.relativize(m.getKey()).toString().replace('\\', '/');
                String prefix = rel.isEmpty() ? "" : rel + "/";
                if (prefixes.contains(prefix)) {
                    args.add(GraphRunner.moduleArg(
                            root, m.getKey(), m.getValue().project().name()));
                }
            }
        }
        String why = compiler.compile(root, args, true);
        if (why == null) return ask.ask(List.of("phase=verify"));
        if (why.startsWith("the code does not compile")) return ask.ask(List.of("phase=undo", "why=" + why));
        return "Not proven: " + why + ". The edit stays; build and test to check it.";
    }

    /**
     * Compiles every module's tests before a rename plans, so each test source's reference index
     * describes it as it stands (a question compiles main sources only). Nothing when no compiler.
     */
    static void compileTests(Path root, GraphRunner.@Nullable Compiler compiler) throws InterruptedException {
        if (compiler != null) compiler.compile(root, List.of(), true);
    }

    /** The module directories the proof's first line names ({@code .} is the root's: {@code ""}). */
    static List<String> modules(Path proof) throws IOException {
        List<String> out = new ArrayList<>();
        List<String> lines = Files.readAllLines(proof, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.getFirst().startsWith("modules\t")) return out;
        String list = lines.getFirst().substring("modules\t".length());
        if (list.isBlank()) return out;
        for (String m : list.split(",")) out.add(m.equals(".") ? "" : m);
        return out;
    }
}
