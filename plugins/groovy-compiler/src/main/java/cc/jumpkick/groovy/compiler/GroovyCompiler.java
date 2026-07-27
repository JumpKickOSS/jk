// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.groovy.compiler;

import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginManifest;
import cc.jumpkick.plugin.protocol.PluginSpec;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.codehaus.groovy.GroovyBugError;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.ErrorCollector;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.control.messages.WarningMessage;
import org.codehaus.groovy.syntax.SyntaxException;
import org.codehaus.groovy.tools.javac.JavaAwareCompilationUnit;

/**
 * Child-JVM entry point that drives the Groovy 5 compiler in-process.
 *
 * <p>jk launches this as {@code java -cp <worker.jar>:<groovy-closure>
 * cc.jumpkick.plugin.process.PluginMain @&lt;spec&gt;}. The plugin reads the {@link CompileSpec},
 * runs a full JVM compile ({@link CompilationUnit}, or {@link JavaAwareCompilationUnit} when the
 * source set carries {@code .java} files — joint mode), streams diagnostics back as JSONL, and
 * exits: {@code 0} success, {@code 1} compilation error, {@code 3} OOM/internal compiler error,
 * {@code 2} bad spec / unexpected failure.
 *
 * <p>Joint mode uses the Java sources for resolution only: Groovy-class stubs are written to
 * {@code stubsOut} (kept only when the spec names one) and javac's {@code .class} output is
 * diverted to a discard dir under the workdir — the real Java outputs are owned by jk's javac
 * step, never this worker. Depends only on the Groovy compiler at compile time — the Groovy jar
 * arrives on the classpath at runtime, version-matched by jk, so the plugin never leaks compiler
 * deps into jk.
 */
public final class GroovyCompiler implements Plugin {

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-groovy-compiler", "##JKGC:");
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) {
        GcProtocol proto = new GcProtocol(out);
        try {
            if (args.size() != 1) {
                System.err.println("usage: jk-groovy-compiler <spec-file>|@<spec-file>");
                return 2;
            }
            String specArg = args.get(0).startsWith("@") ? args.get(0).substring(1) : args.get(0);
            CompileSpec spec = CompileSpec.from(PluginSpec.read(Path.of(specArg)));
            return compile(spec, proto);
        } catch (Throwable t) {
            System.err.println("jk-groovy-compiler: " + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace(System.err);
            return 2;
        }
    }

    static int compile(CompileSpec spec, GcProtocol proto) throws Exception {
        spec.outputDir.mkdirs();

        List<File> files = allSources(spec);
        boolean joint = files.stream().anyMatch(CompileSpec::isJava);

        CompilerConfiguration cfg = configure(spec, proto);
        CompilationUnit unit;
        if (joint) {
            File scratch = scratchDir(spec);
            File stubDir = spec.stubsOut != null ? spec.stubsOut : new File(scratch, "stubs");
            stubDir.mkdirs();
            // javac output is resolution-only: divert it to a discard dir (the trailing -d wins),
            // NOT the output dir — jk's javac step owns the real Java outputs.
            File discard = new File(scratch, "javac-classes");
            discard.mkdirs();
            Map<String, Object> joint0 = new LinkedHashMap<>();
            joint0.put("stubDir", stubDir);
            joint0.put("keepStubs", spec.stubsOut != null);
            List<String> named = new ArrayList<>(List.of("d", discard.getAbsolutePath()));
            if (!spec.processorPath.isEmpty()) {
                // Mixed module with annotation processors (Lombok, source generators): the swept
                // javac pass must run them or references to generated members fail resolution
                // (JK-1232). Generated sources land in scratch; their classes go to the discard
                // dir like all swept output.
                File generated = new File(scratch, "javac-generated");
                generated.mkdirs();
                named.addAll(List.of(
                        "processorpath",
                        spec.processorPath.stream()
                                .map(File::getAbsolutePath)
                                .collect(java.util.stream.Collectors.joining(File.pathSeparator)),
                        "s",
                        generated.getAbsolutePath()));
            }
            joint0.put("namedValues", named.toArray(String[]::new));
            cfg.setJointCompilationOptions(joint0);
            JavaAwareCompilationUnit jacu = new JavaAwareCompilationUnit(cfg);
            // Post-javac re-resolution loads the Java classes through the unit's loader; the
            // discard dir must be visible there since the output dir never receives them.
            jacu.getClassLoader().addClasspath(discard.getAbsolutePath());
            unit = jacu;
        } else {
            unit = new CompilationUnit(cfg);
        }
        unit.addSources(files.toArray(File[]::new));

        try {
            unit.compile();
        } catch (OutOfMemoryError | GroovyBugError e) {
            proto.diagnostic("ERROR", null, 0, 0, "internal compiler error: " + e);
            proto.result("COMPILER_INTERNAL_ERROR");
            proto.done(3);
            return 3;
        } catch (CompilationFailedException e) {
            if (!emitDiagnostics(unit.getErrorCollector(), proto)) {
                proto.diagnostic("ERROR", null, 0, 0, e.getMessage());
            }
            proto.result("COMPILATION_ERROR");
            proto.done(1);
            return 1;
        }

        emitDiagnostics(unit.getErrorCollector(), proto);
        proto.result("COMPILATION_SUCCESS");
        proto.done(0);
        return 0;
    }

    /**
     * Explicit sources plus every {@code .java} under the spec's Java source roots — joint
     * resolution needs the whole Java neighborhood on javac's compile set, since only stubs (not
     * the roots) ride its sourcepath. Deduped by absolute path, spec order first.
     */
    static List<File> allSources(CompileSpec spec) throws java.io.IOException {
        Set<File> out = new LinkedHashSet<>();
        for (File f : spec.sources) out.add(f.getAbsoluteFile());
        for (File root : spec.javaSourceRoots) {
            if (!root.isDirectory()) continue;
            try (Stream<Path> walk = Files.walk(root.toPath())) {
                walk.filter(p -> p.toString().endsWith(".java"))
                        .filter(Files::isRegularFile)
                        .sorted()
                        .forEach(p -> out.add(p.toFile().getAbsoluteFile()));
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * Translate the spec into a {@link CompilerConfiguration}. The plugin maps only what the spec
     * describes; unknown {@code ARG} entries are reported and skipped (groovyc has no raw
     * passthrough into an in-process {@code CompilerConfiguration}).
     */
    static CompilerConfiguration configure(CompileSpec spec, GcProtocol proto) {
        CompilerConfiguration cfg = new CompilerConfiguration();
        cfg.setTargetDirectory(spec.outputDir);
        cfg.setTargetBytecode(spec.jvmTarget);
        if (!spec.classpath.isEmpty()) {
            cfg.setClasspathList(spec.classpath.stream().map(File::getAbsolutePath).toList());
        }
        for (String arg : spec.extraArgs) {
            switch (arg) {
                case "--parameters", "-parameters" -> cfg.setParameters(true);
                case "--enable-preview" -> cfg.setPreviewFeatures(true);
                default -> proto.diagnostic("WARNING", null, 0, 0, "ignoring unsupported groovyc arg: " + arg);
            }
        }
        return cfg;
    }

    /** Emit every collected error + warning as protocol diagnostics; true when any error emitted. */
    private static boolean emitDiagnostics(ErrorCollector collector, GcProtocol proto) {
        boolean any = false;
        for (int i = 0; i < collector.getErrorCount(); i++) {
            var message = collector.getError(i);
            if (message instanceof SyntaxErrorMessage sem) {
                SyntaxException cause = sem.getCause();
                proto.diagnostic(
                        "ERROR",
                        cause.getSourceLocator(),
                        cause.getLine(),
                        cause.getStartColumn(),
                        cause.getOriginalMessage());
            } else {
                proto.diagnostic("ERROR", null, 0, 0, render(message::write));
            }
            any = true;
        }
        for (int i = 0; i < collector.getWarningCount(); i++) {
            WarningMessage warning = collector.getWarning(i);
            proto.diagnostic("WARNING", null, 0, 0, render(warning::write));
        }
        return any;
    }

    private static String render(java.util.function.Consumer<PrintWriter> write) {
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            write.accept(pw);
        }
        return sw.toString().strip();
    }

    /** The scratch root for joint-mode side products: the spec's workdir, else a temp dir. */
    private static File scratchDir(CompileSpec spec) throws java.io.IOException {
        if (spec.workDir != null) {
            spec.workDir.mkdirs();
            return spec.workDir;
        }
        return Files.createTempDirectory("jk-groovyc-").toFile();
    }
}
