// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.generate;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.ToolJars;
import cc.jumpkick.plugin.build.TaskExec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.lang.model.SourceVersion;
import org.jspecify.annotations.Nullable;

/**
 * The generate step's body: expand the entry's inputs, extract the jar it unpacks, fork
 * {@code java -cp <tool> <main> <args>} on the build's JDK with the output dir as the working
 * directory, report every located line the tool printed as a diagnostic, fail on a non-zero exit
 * with the output's tail; an entry that runs once per input forks the tool for each, into a
 * directory of its own under the output. Schemas a {@code [schema-registry]} table pins join the inputs. Inputs that name no file under the module — a table a reactor parent
 * hands to a module with nothing to generate — leave the output empty and say so once, unless the
 * entry's key probe names what the tool reads instead.
 */
final class GeneratorStep {

    /** The extra holding the schemas the module's {@code [schema-registry]} table pins for this tool. */
    static final String REGISTRY_SCHEMAS = "schema-registry";

    /** How many trailing output lines a failure message carries. */
    private static final int TAIL = 40;

    private GeneratorStep() {}

    static void run(TaskExec exec, GeneratorEntry entry) throws Exception {
        List<Path> inputs = Inputs.expand(exec.moduleDir(), entry.inputs());
        Optional<Path> registry = exec.extra(REGISTRY_SCHEMAS);
        if (registry.isPresent()) inputs = Inputs.withAll(inputs, registry.get());
        if (inputs.isEmpty() && !entry.inputs().isEmpty() && entry.keyProbe() == null) {
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
        if (toolArtifact != null) classpath.addAll(ToolJars.classpath(exec.requireExtra(toolArtifact)));
        String main = entry.main() != null ? entry.main() : mainClass(classpath, entry);
        exec.label(entry.name() + " (" + describe(inputs, entry) + ")");

        Map<String, String> env = new LinkedHashMap<>(entry.env());
        for (Map.Entry<String, String> variable : entry.secretEnv().entrySet()) {
            exec.secret(variable.getValue()).ifPresent(value -> env.put(variable.getKey(), value));
        }
        if (!entry.eachInput()) {
            fork(
                    exec,
                    entry,
                    classpath,
                    main,
                    env,
                    new Arguments.Scope(inputs, unpacked, out, exec.moduleDir(), exec::requireExtra));
            return;
        }
        Map<String, Path> runs = runs(entry, inputs);
        for (Map.Entry<String, Path> run : runs.entrySet()) {
            Path runOut = Files.createDirectories(out.resolve(run.getKey()));
            fork(
                    exec,
                    entry,
                    classpath,
                    main,
                    env,
                    new Arguments.Scope(
                            List.of(run.getValue()),
                            unpacked,
                            runOut,
                            exec.moduleDir(),
                            exec::requireExtra,
                            run.getKey()));
        }
    }

    /**
     * Each input under the name of its run, in input order; two inputs that would share a name, and
     * so an output directory and a package, are refused before the tool runs.
     */
    static Map<String, Path> runs(GeneratorEntry entry, List<Path> inputs) {
        Map<String, Path> runs = new LinkedHashMap<>();
        for (Path input : inputs) {
            Path taken = runs.putIfAbsent(runName(input), input);
            if (taken != null) {
                throw new IllegalStateException("[generate." + entry.name() + "] " + taken + " and " + input
                        + " both generate into \"" + runName(input) + "\"; rename one of them");
            }
        }
        return runs;
    }

    /**
     * The name an input's own run generates under, an output directory and a Java package segment
     * at once: the file name without its extension, lower case, every character a package name
     * cannot hold an underscore ({@code pet-store.yaml} is {@code pet_store}), with an underscore
     * ahead of a leading digit and after a keyword.
     */
    static String runName(Path input) {
        String file = input.getFileName().toString();
        int dot = file.lastIndexOf('.');
        String stem = (dot > 0 ? file.substring(0, dot) : file).toLowerCase(Locale.ROOT);
        StringBuilder name = new StringBuilder(stem.length() + 1);
        for (int i = 0; i < stem.length(); i++) {
            char c = stem.charAt(i);
            name.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' ? c : '_');
        }
        if (name.isEmpty() || Character.isDigit(name.charAt(0))) name.insert(0, '_');
        if (SourceVersion.isKeyword(name)) name.append('_');
        return name.toString();
    }

    /**
     * One fork of the tool over {@code scope}, its output directory the working directory: every
     * located line it printed becomes a diagnostic, a non-zero exit fails the step with its tail,
     * and what the entry discards is removed from what it wrote.
     */
    private static void fork(
            TaskExec exec,
            GeneratorEntry entry,
            List<Path> classpath,
            String main,
            Map<String, String> env,
            Arguments.Scope scope)
            throws Exception {
        List<String> args = Arguments.expand(entry.args(), scope);
        List<String> output = new ArrayList<>();
        int exit = exec.java().classpath(classpath).mainClass(main).args(args).env(env).cwd(scope.out()).stream(
                output::add);
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
            String cause = failureCause(output);
            throw new IllegalStateException(main + " failed (exit " + exit + ")" + (cause == null ? "" : ": " + cause)
                    + (tail.isEmpty() ? "" : "\n" + String.join("\n", tail)));
        }
        discard(scope.out(), entry.discard());
    }

    private static final Pattern UNCAUGHT = Pattern.compile("^Exception in thread \"[^\"]*\" (.+)$");
    private static final Pattern LOGGED_ERROR =
            Pattern.compile("^(?:\\[[^\\]]*\\]\\s*)?(?:ERROR|FATAL|SEVERE)\\b\\s*(.+)$");

    /**
     * The line that says why the tool failed, for the head of the failure message, which is what a
     * terminal shows when it shows one line: the uncaught exception (its last {@code Caused by:}
     * when there is one), else the last error-level log line; null when the output has neither.
     */
    static @Nullable String failureCause(List<String> output) {
        String uncaught = null;
        String causedBy = null;
        String logged = null;
        for (String raw : output) {
            String line = raw.strip();
            Matcher m = UNCAUGHT.matcher(line);
            if (m.matches()) {
                uncaught = m.group(1);
                causedBy = null;
            } else if (line.startsWith("Caused by: ") && uncaught != null) {
                causedBy = line.substring("Caused by: ".length());
            } else {
                Matcher e = LOGGED_ERROR.matcher(line);
                if (e.matches()) logged = e.group(1);
            }
        }
        if (causedBy != null) return causedBy;
        if (uncaught != null) return uncaught;
        return logged;
    }

    /**
     * Remove what the tool wrote beside its contribution: a plain path under {@code out} with
     * everything below it, or every file a glob over {@code out} matches. A plain path that names
     * {@code out} itself, lies outside it, or reaches outside it through a link the tool wrote is
     * refused before anything is deleted. A glob only matches files the walk of {@code out} finds,
     * and that walk does not enter linked directories.
     */
    static void discard(Path out, List<String> patterns) throws IOException {
        Path base = out.toAbsolutePath().normalize();
        List<Path> targets = new ArrayList<>();
        for (String pattern : patterns) {
            if (Inputs.isGlob(pattern)) continue;
            if (!PathUtil.isStrictlyBelow(pattern)) {
                throw new IOException("discard \"" + pattern + "\" is not under the output directory");
            }
            Path target = base.resolve(pattern).normalize();
            Path parent;
            try {
                parent = Objects.requireNonNull(target.getParent()).toRealPath();
            } catch (NoSuchFileException absent) {
                continue;
            }
            if (!parent.startsWith(base.toRealPath())) {
                throw new IOException("discard \"" + pattern + "\" leads out of the output directory through a link");
            }
            targets.add(target);
        }
        for (Path target : targets) PathUtil.deleteRecursivelyOrThrow(target);
        for (String pattern : patterns) {
            if (!Inputs.isGlob(pattern)) continue;
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
        if (inputs.isEmpty()) return entry.unpack() != null ? "unpacked " + entry.unpack() : "live input";
        return inputs.size() + (inputs.size() == 1 ? " input" : " inputs");
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
                        .orElseThrow(() -> new IllegalStateException("[generate." + entry.name() + "] no " + artifact
                                + "-*.jar among the jars of " + entry.toolCoordinate() + "; set main = \"<class>\""));
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
