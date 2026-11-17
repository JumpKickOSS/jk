// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.graph;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.codegraph.Modules;
import cc.jumpkick.guard.extract.FactsIndexing;
import cc.jumpkick.java.compiler.IndexedJavac;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * A one-module workspace compiled the way jk compiles it: main and test classes, each with the
 * reference index javac writes, and the facts index the engine hands the graph worker. A rename
 * runs through {@link GraphService} as the engine asks for it; {@link #compile} after an edit is
 * the engine's compile step, so the proof ({@code phase=verify}) reads what javac resolved.
 */
final class RenameFixture {

    final Path root;

    RenameFixture(Path root, Map<String, String> files) throws IOException {
        this.root = Files.createDirectories(root);
        Files.writeString(root.resolve("jk.toml"), """
                group = "org.acme"
                name = "petclinic"
                version = "1.0.0"
                java = 25
                """);
        for (Map.Entry<String, String> e : files.entrySet()) {
            Path f = root.resolve(e.getKey());
            Files.createDirectories(f.getParent());
            Files.writeString(f, e.getValue());
        }
        assertThat(compile()).as("the fixture compiles").isNull();
    }

    static Map<String, String> files(String... pairs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) out.put(pairs[i], pairs[i + 1]);
        return out;
    }

    /** Compiles main, then tests against it, as the engine's compile step does; javac's errors, or {@code null}. */
    @Nullable
    String compile() throws IOException {
        for (String dir : List.of("target/classes", "target/test-classes", "target/incremental")) {
            clear(root.resolve(dir));
        }
        String main = IndexedJavac.compile(root, root.resolve("target/classes"), sources("src/main/java"), List.of());
        if (main != null) return main;
        List<Path> tests = sources("src/test/java");
        if (tests.isEmpty()) return null;
        return IndexedJavac.compile(
                root, root.resolve("target/test-classes"), tests, List.of(root.resolve("target/classes")));
    }

    private List<Path> sources(String dir) throws IOException {
        Path base = root.resolve(dir);
        if (!Files.isDirectory(base)) return List.of();
        try (Stream<Path> s = Files.walk(base)) {
            return s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static void clear(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p :
                    s.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) Files.delete(p);
        }
    }

    GraphService.Workspace workspace() throws IOException {
        List<GraphBuild.ClassIndex> indexes = new ArrayList<>();
        for (String set : List.of("main", "test")) {
            Path classes = root.resolve(set.equals("main") ? "target/classes" : "target/test-classes");
            if (!Files.isDirectory(classes)) continue;
            FactsIndexing.Ensured e =
                    FactsIndexing.ensure(classes, FactsIndexing.indexPath(root.resolve("target"), set));
            indexes.add(
                    new GraphBuild.ClassIndex(e.index(), "", set, e.bodyDigest(), e.classes(), Set.of(), List.of()));
        }
        return new GraphService.Workspace(
                root,
                root.resolve("target/graph"),
                root.resolve("target/jk-cache"),
                indexes,
                Modules.NONE,
                new ScriptReader(null, null),
                Map.of(),
                List.of(),
                List.of(),
                null,
                List.of(),
                ModuleReach.UNKNOWN);
    }

    /** {@code rename address to} with {@code options} ({@code key=value}), as the engine asks for it. */
    String rename(String address, String to, String... options) throws IOException {
        return rename(8000, address, to, options);
    }

    /** {@code rename address to} in a {@code budget} of tokens, as the engine asks for it (MCP's is 2,000). */
    String rename(int budget, String address, String to, String... options) throws IOException {
        Map<String, String> opts = new LinkedHashMap<>();
        for (String o : options) opts.put(o.substring(0, o.indexOf('=')), o.substring(o.indexOf('=') + 1));
        GraphService.Request r = new GraphService.Request(
                "rename",
                List.of(address, to),
                "bfs",
                3,
                budget,
                List.of(),
                false,
                8,
                null,
                false,
                false,
                null,
                List.of(),
                null,
                opts);
        return GraphService.run(workspace(), r, GraphBuild.Progress.NONE).text();
    }

    /** The engine's check after a rename: compile, then the proof, or the undo when the compile failed. */
    String check() throws IOException {
        String errors = compile();
        if (errors != null) {
            String line = errors.lines().findFirst().orElse("").strip();
            return rename("-", "-", "phase=undo", "why=the code does not compile (" + line + ")");
        }
        return rename("-", "-", "phase=verify");
    }

    String read(String rel) throws IOException {
        return Files.readString(root.resolve(rel));
    }

    boolean exists(String rel) {
        return Files.exists(root.resolve(rel));
    }
}
