// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.generate;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.plugin.build.TaskExec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import org.jspecify.annotations.Nullable;

/**
 * The generate step's body: expand the entry's inputs, extract the jar it unpacks, fork
 * {@code java -cp <tool> <main> <args>} on the build's JDK with the output dir as the working
 * directory, report every located line the tool printed as a diagnostic, fail on a non-zero exit
 * with the output's tail. Inputs that name no file under the module — a table a reactor parent
 * hands to a module with nothing to generate — leave the output empty and say so once.
 */
final class GeneratorStep {

    /** How many trailing output lines a failure message carries. */
    private static final int TAIL = 40;

    private GeneratorStep() {}

    static void run(TaskExec exec, GeneratorEntry entry) throws Exception {
        List<Path> inputs = Inputs.expand(exec.moduleDir(), entry.inputs());
        if (inputs.isEmpty() && !entry.inputs().isEmpty()) {
            exec.outputDir(entry.out());
            exec.label(entry.name() + " (no inputs)");
            exec.diagnostic(
                    "warning",
                    null,
                    0,
                    0,
                    "[generate." + entry.name() + "] inputs " + entry.inputs() + " match no file under "
                            + exec.moduleDir() + "; nothing was generated");
            return;
        }
        Path unpacked = entry.unpack() == null
                ? null
                : Unpack.extract(
                        exec.requireExtra(entry.unpackArtifact()),
                        exec.scratch().resolve("unpacked"));
        Path out = exec.outputDir(entry.out());
        List<Path> classpath = new ArrayList<>(entry.classpath());
        String toolArtifact = entry.toolArtifact();
        if (toolArtifact != null) classpath.addAll(toolClasspath(exec.requireExtra(toolArtifact)));
        String main = entry.main() != null ? entry.main() : mainClass(classpath, entry);
        List<String> args =
                Arguments.expand(entry.args(), new Arguments.Scope(inputs, unpacked, out, exec.moduleDir()));
        exec.label(entry.name() + " (" + describe(inputs, entry) + ")");

        List<String> output = new ArrayList<>();
        int exit = exec.java().classpath(classpath).mainClass(main).args(args).cwd(out).stream(output::add);
        for (String line : output) {
            ToolDiagnostics.parse(line)
                    .ifPresent(d -> exec.diagnostic(
                            d.severity() != null ? d.severity() : exit == 0 ? "warning" : "error",
                            d.file(),
                            d.line(),
                            d.col(),
                            d.message()));
        }
        if (exit != 0) {
            List<String> tail = output.subList(Math.max(0, output.size() - TAIL), output.size());
            throw new IllegalStateException(
                    main + " failed (exit " + exit + ")" + (tail.isEmpty() ? "" : ":\n" + String.join("\n", tail)));
        }
        discard(out, entry.discard());
    }

    /**
     * Remove what the tool wrote beside its contribution: a plain path under {@code out} with
     * everything below it, or every file a glob over {@code out} matches.
     */
    static void discard(Path out, List<String> patterns) throws IOException {
        for (String pattern : patterns) {
            if (!Inputs.isGlob(pattern)) {
                PathUtil.deleteRecursivelyOrThrow(out.resolve(pattern));
                continue;
            }
            List<PathMatcher> matchers = Inputs.matchers(pattern);
            List<Path> matched = new ArrayList<>();
            PathUtil.forEachRegularFile(out, (file, attrs) -> {
                Path relative = out.relativize(file);
                if (matchers.stream().anyMatch(m -> m.matches(relative))) matched.add(file);
            });
            for (Path file : matched) Files.delete(file);
        }
    }

    /** The step label's parenthetical: the input count, or the unpacked coordinate when there are none. */
    private static String describe(List<Path> inputs, GeneratorEntry entry) {
        if (inputs.isEmpty()) return "unpacked " + entry.unpack();
        return inputs.size() + (inputs.size() == 1 ? " input" : " inputs");
    }

    /** The fetched tool as a classpath: the jar itself, or every jar of a materialized closure dir. */
    static List<Path> toolClasspath(Path tool) throws IOException {
        if (!Files.isDirectory(tool)) return List.of(tool);
        List<Path> jars = new ArrayList<>();
        PathUtil.forEachRegularFile(tool, (file, attrs) -> {
            if (file.getFileName().toString().endsWith(".jar")) jars.add(file);
        });
        jars.sort(null);
        return List.copyOf(jars);
    }

    /**
     * The {@code Main-Class} of the tool's own jar — the one named after the coordinate's
     * artifact — when the entry names no {@code main}.
     */
    static String mainClass(List<Path> classpath, GeneratorEntry entry) throws IOException {
        String artifact = artifactOf(Objects.requireNonNull(entry.toolCoordinate(), "tool"));
        Path own = classpath.size() == 1
                ? classpath.getFirst()
                : classpath.stream()
                        .filter(jar -> jar.getFileName().toString().startsWith(artifact + "-"))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("[generate." + entry.name() + "] resolved "
                                + entry.toolCoordinate() + " but no jar named " + artifact + "-*.jar is in its closure;"
                                + " set main = \"<class>\""));
        @Nullable String main;
        try (JarFile jar = new JarFile(own.toFile())) {
            Manifest manifest = jar.getManifest();
            main = manifest == null ? null : manifest.getMainAttributes().getValue("Main-Class");
        }
        if (main == null || main.isBlank()) {
            throw new IllegalStateException("[generate." + entry.name() + "] " + own.getFileName()
                    + " declares no Main-Class; set main = \"<class>\"");
        }
        return main;
    }

    private static String artifactOf(String coordinate) {
        String[] parts = coordinate.split(":");
        return parts.length >= 2 ? parts[1] : coordinate;
    }
}
