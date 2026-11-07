// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.helidon;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.plugin.build.PackageIo;
import cc.jumpkick.plugin.build.TaskExec;
import io.helidon.service.maven.plugin.JkCreateApplication;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The bodies of the {@code helidon-binding} and {@code helidon-test-binding} steps: what {@code
 * helidon-service-maven-plugin}'s {@code create-application} and {@code create-test-application}
 * goals do, run as {@link JkCreateApplication} in a JVM forked on the project's JDK over the
 * plugin's locked closure. No Maven class is ever on that classpath.
 *
 * <p>The production step's output is a copy of the module's classes with the real binding (and
 * {@code ApplicationMain}) compiled over the empty one, which {@code transformsClasses} makes the
 * module's classes for tests, {@code jk run}, {@code jk dev} and the jar. With {@code binding}
 * unset it runs Helidon only when a class carries {@code @Service.GenerateBinding} (or {@code
 * generate-main} asks for a main class); otherwise the copy is the classes as compiled. A recompile
 * ({@code jk dev}'s rebuild on change) keeps the empty binding unless {@code generate-main} needs
 * the fork.
 */
final class HelidonBinding {

    /** The step-dependency artifact the manifest declares for the plugin's jar and its closure. */
    static final String TOOL = "helidon-service-maven-plugin";

    /** The annotation the processor writes an empty binding for, as a class file spells it. */
    private static final byte[] GENERATE_BINDING_DESCRIPTOR =
            "Lio/helidon/service/registry/Service$GenerateBinding;".getBytes(StandardCharsets.UTF_8);

    private static final String PREFIX = JkCreateApplication.PREFIX;
    private static final int TAIL = 40;

    private HelidonBinding() {}

    /** {@code create-application}: the module's classes with the real binding compiled in. */
    static void production(TaskExec exec) throws Exception {
        HelidonSettings settings = HelidonSettings.of(exec.config());
        Path classes = exec.classesDir();
        if (!Files.isDirectory(classes)) throw new IOException("no compiled classes at " + classes);
        Path out = exec.outputDir(HelidonPlugin.CLASSES);
        PathUtil.copyTree(classes, out);
        boolean annotated = carriesGenerateBinding(classes);
        boolean binding = settings.binding().orElse(annotated);
        if (!binding && !settings.generateMain()) {
            exec.label("helidon-binding (no @Service.GenerateBinding)");
            return;
        }
        // A recompile (jk dev's rebuild on change) keeps the empty binding, which falls back to
        // classpath discovery: the fork scans the whole runtime classpath and costs about a second
        // on every edit. ApplicationMain has no such fallback, so generate-main always forks.
        if (exec.recompile() && !settings.generateMain()) {
            exec.label("helidon-binding (deferred on recompile: the empty binding discovers services)");
            return;
        }
        List<Path> classpath = new ArrayList<>();
        classpath.add(out);
        classpath.addAll(exec.runtimeClasspath());
        Path module = exec.moduleDir();
        List<String> args = new ArrayList<>(List.of("--scope", "production"));
        args.addAll(sourceRoots("--main-source-root", module.resolve("src/main/java")));
        args.addAll(sourceRoots("--source-root", module.resolve("src/main/java")));
        fork(exec, settings, binding, classpath, out, exec.outputDir(HelidonPlugin.GENERATED), args);
    }

    /**
     * {@code create-test-application}: {@code TestApplicationBinding} for the test scope, compiled
     * into a directory the test JVM's classpath carries after the test classes.
     */
    static void test(TaskExec exec) throws Exception {
        HelidonSettings settings = HelidonSettings.of(exec.config());
        Path testClasses = exec.testClassesDir();
        if (!Files.isDirectory(testClasses)) throw new IOException("no compiled test classes at " + testClasses);
        Path out = exec.outputDir(HelidonPlugin.TEST_CLASSES);
        Set<Path> classpath = new LinkedHashSet<>();
        classpath.add(testClasses);
        for (PackageIo.RuntimeEntry entry : exec.runtimeEntries()) {
            Path jar = entry.jar();
            if (jar != null) classpath.add(jar);
        }
        classpath.add(exec.classesDir());
        Path module = exec.moduleDir();
        List<String> args = new ArrayList<>(List.of("--scope", "test", "--binding-class", "TestApplicationBinding"));
        args.addAll(sourceRoots("--main-source-root", module.resolve("src/main/java")));
        args.addAll(sourceRoots("--source-root", module.resolve("src/test/java")));
        fork(exec, settings, true, List.copyOf(classpath), out, exec.outputDir(HelidonPlugin.TEST_GENERATED), args);
        // The generator restates the scope's registry file at the test classes' own location with
        // no services (it gathers no descriptors of its own); beside the test classes it would only
        // add a second copy of that location, so the binding class is all the directory carries.
        PathUtil.deleteRecursivelyOrThrow(out.resolve("META-INF"));
    }

    private static void fork(
            TaskExec exec,
            HelidonSettings settings,
            boolean binding,
            List<Path> classpath,
            Path out,
            Path generated,
            List<String> scopeArgs)
            throws Exception {
        Path classpathFile = exec.scratch().resolve("helidon-classpath.txt");
        List<String> lines = new ArrayList<>();
        for (Path entry : classpath) lines.add(entry.toAbsolutePath().toString());
        Files.write(classpathFile, lines, StandardCharsets.UTF_8);

        List<String> args = new ArrayList<>(scopeArgs);
        args.addAll(List.of(
                "--output", out.toString(),
                "--generated", generated.toString(),
                "--classpath-file", classpathFile.toString(),
                "--release", String.valueOf(exec.project().javaRelease())));
        if (binding) args.add("--generate-binding");
        if (settings.generateMain()) args.add("--generate-main");
        if (settings.validate()) args.add("--validate");

        List<Path> toolClasspath = new ArrayList<>(ownJar());
        toolClasspath.addAll(toolClasspath(exec.requireExtra(TOOL)));
        Report report = new Report();
        int exit = exec
                .java()
                .classpath(toolClasspath)
                .mainClass(JkCreateApplication.class.getName())
                .args(args)
                .cwd(exec.moduleDir())
                .stream(report::accept);
        if (exit != 0) throw new IllegalStateException(report.failure(exit));
        for (String warning : report.warnings) exec.diagnostic("warning", null, 0, 0, warning);
        exec.label("helidon-binding ("
                + (report.generated.isEmpty() ? "no services" : String.join(", ", report.generated)) + ")");
    }

    /** The fork's lines, sorted by what the worker does with them. */
    static final class Report {
        final List<String> errors = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final List<String> generated = new ArrayList<>();
        final List<String> other = new ArrayList<>();

        @Nullable
        String failed;

        void accept(String line) {
            if (!line.startsWith(PREFIX)) {
                other.add(line);
                return;
            }
            String body = line.substring(PREFIX.length());
            int space = body.indexOf(' ');
            String level = space < 0 ? body : body.substring(0, space);
            String message = space < 0 ? "" : unescape(body.substring(space + 1));
            switch (level) {
                case "ERROR" -> errors.add(message);
                case "WARNING" -> warnings.add(message);
                case "BINDING", "MAIN" -> generated.add(simpleName(message));
                case "FAILED" -> failed = message;
                default -> {
                    // INFO: the Mojo's progress, which a build that succeeds does not show
                }
            }
        }

        /**
         * Helidon's own words: the failure, each error and warning it logged (the validator's
         * findings, javac's diagnostics), then the tail of anything else the fork printed.
         */
        String failure(int exit) {
            StringBuilder text = new StringBuilder("Helidon's create-application failed");
            text.append(failed == null ? " (exit " + exit + ")" : ": " + failed);
            for (String error : errors) text.append("\n  ").append(error);
            for (String warning : warnings) text.append("\n  ").append(warning);
            if (failed == null && !other.isEmpty()) {
                text.append('\n')
                        .append(String.join("\n", other.subList(Math.max(0, other.size() - TAIL), other.size())));
            }
            return text.toString();
        }

        private static String simpleName(String fqn) {
            int dot = fqn.lastIndexOf('.');
            return dot < 0 ? fqn : fqn.substring(dot + 1);
        }

        static String unescape(String escaped) {
            StringBuilder b = new StringBuilder(escaped.length());
            for (int i = 0; i < escaped.length(); i++) {
                char c = escaped.charAt(i);
                if (c == '\\' && i + 1 < escaped.length()) {
                    char n = escaped.charAt(++i);
                    b.append(n == 'n' ? '\n' : n);
                } else {
                    b.append(c);
                }
            }
            return b.toString();
        }
    }

    /**
     * Whether a class under {@code classes} carries {@code @Service.GenerateBinding} (retained in
     * the class file), which is what made the processor write an empty binding.
     */
    static boolean carriesGenerateBinding(Path classes) throws IOException {
        boolean[] found = {false};
        PathUtil.forEachRegularFile(classes, (file, attrs) -> {
            if (found[0] || !file.getFileName().toString().endsWith(".class")) return;
            if (indexOf(Files.readAllBytes(file), GENERATE_BINDING_DESCRIPTOR) >= 0) found[0] = true;
        });
        return found[0];
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static List<String> sourceRoots(String flag, Path root) {
        return Files.isDirectory(root) ? List.of(flag, root.toString()) : List.of();
    }

    /** The fetched tool as a classpath: the jar itself, or every jar of its materialized closure. */
    static List<Path> toolClasspath(Path tool) throws IOException {
        if (!Files.isDirectory(tool)) return List.of(tool);
        List<Path> jars = new ArrayList<>();
        PathUtil.forEachRegularFile(tool, (file, attrs) -> {
            if (file.getFileName().toString().endsWith(".jar")) jars.add(file);
        });
        jars.sort(null);
        return List.copyOf(jars);
    }

    /** This worker's own jar (or classes directory), which carries {@link JkCreateApplication}. */
    static List<Path> ownJar() {
        CodeSource source = HelidonBinding.class.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            throw new IllegalStateException("the helidon worker cannot name its own jar for the binding fork");
        }
        try {
            return List.of(Path.of(source.getLocation().toURI()));
        } catch (URISyntaxException e) {
            throw new IllegalStateException("the helidon worker's jar has no path: " + source.getLocation(), e);
        }
    }
}
