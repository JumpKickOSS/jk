// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.groovy.compiler;

import cc.jumpkick.host.Classpaths;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginManifest;
import cc.jumpkick.plugin.protocol.CompilerProtocol;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
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
 * exits {@link Exit#SUCCESS}, {@link Exit#FAILURE} on a compilation error,
 * {@link CompilerProtocol#COMPILER_FAULT} on an OOM/internal compiler error, or
 * {@link Exit#SOFTWARE} for a bad spec / unexpected failure.
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
        return CompilerProtocol.compileFromSpec(
                manifest().id(), args, out, (spec, proto) -> compile(CompileSpec.from(spec), proto));
    }

    static int compile(CompileSpec spec, CompilerProtocol proto) throws Exception {
        spec.outputDir.mkdirs();

        // The engine's SOURCE lines are the whole joint compile set (explicit sources plus the
        // Java neighborhood) — the exact set its action key hashed. Never re-walk here: this jar
        // must not grow a tree cache, and a walk would compile files the key never saw.
        List<File> files = spec.sources;
        boolean joint = files.stream().anyMatch(CompileSpec::isJava);

        CompilerConfiguration cfg = configure(spec, proto);
        // Joint-mode side products (stubs, the swept javac output) live in the spec's workdir,
        // else in a temp directory that exists only for this compile.
        File scratch = joint ? scratchDir(spec) : null;
        try {
            CompilationUnit unit;
            if (scratch != null) {
                File stubDir = spec.stubsOut != null ? spec.stubsOut : new File(scratch, "stubs");
                stubDir.mkdirs();
                // javac output is resolution-only: divert it to a discard dir (the trailing -d wins),
                // NOT the output dir — jk's javac step owns the real Java outputs.
                File discard = new File(scratch, "javac-classes");
                discard.mkdirs();
                Map<String, Object> joint0 = new LinkedHashMap<>();
                joint0.put("stubDir", stubDir);
                joint0.put("keepStubs", spec.stubsOut != null);
                // Pin the swept javac pass to the project's releasewithout it the
                // sweep typechecks at the worker JVM's level — newer-language sources pass here
                // and fail in jk's real javac lane (or vice versa). namedValues keys are javac
                // flags minus the leading dash; -source/-target is the pairing groovy's javac
                // tool spells natively.
                List<String> named = new ArrayList<>(List.of(
                        "d", discard.getAbsolutePath(),
                        "source", spec.jvmTarget,
                        "target", spec.jvmTarget));
                if (!spec.processorPath.isEmpty()) {
                    // Mixed module with annotation processors (Lombok, source generators): the swept
                    // javac pass must run them or references to generated members fail resolution
                    // . Generated sources land in scratch; their classes go to the discard
                    // dir like all swept output.
                    File generated = new File(scratch, "javac-generated");
                    generated.mkdirs();
                    named.addAll(List.of(
                            "processorpath",
                            Classpaths.join(spec.processorPath.stream()
                                    .map(File::toPath)
                                    .toList()),
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
                proto.diagnostic("ERROR", "internal compiler error: " + e);
                proto.result("COMPILER_INTERNAL_ERROR");
                return CompilerProtocol.COMPILER_FAULT;
            } catch (CompilationFailedException e) {
                if (!emitDiagnostics(unit.getErrorCollector(), proto)) {
                    proto.diagnostic("ERROR", String.valueOf(e.getMessage()));
                }
                proto.result("COMPILATION_ERROR");
                return Exit.FAILURE;
            }

            emitDiagnostics(unit.getErrorCollector(), proto);
            proto.result("COMPILATION_SUCCESS");
            return Exit.SUCCESS;
        } finally {
            if (scratch != null && spec.workDir == null) PathUtil.deleteRecursively(scratch.toPath());
        }
    }

    /**
     * Translate the spec into a {@link CompilerConfiguration}. The plugin maps only what the spec
     * describes; unknown {@code ARG} entries are reported and skipped (groovyc has no raw
     * passthrough into an in-process {@code CompilerConfiguration}).
     */
    static CompilerConfiguration configure(CompileSpec spec, CompilerProtocol proto) {
        CompilerConfiguration cfg = new CompilerConfiguration();
        cfg.setTargetDirectory(spec.outputDir);
        cfg.setTargetBytecode(spec.jvmTarget);
        if (!spec.classpath.isEmpty()) {
            cfg.setClasspathList(
                    spec.classpath.stream().map(File::getAbsolutePath).toList());
        }
        for (String arg : spec.extraArgs) {
            switch (arg) {
                case "--parameters", "-parameters" -> cfg.setParameters(true);
                case "--enable-preview" -> cfg.setPreviewFeatures(true);
                default -> proto.diagnostic("WARNING", "ignoring unsupported groovyc arg: " + arg);
            }
        }
        return cfg;
    }

    /** Emit every collected error + warning as protocol diagnostics; true when any error emitted. */
    private static boolean emitDiagnostics(ErrorCollector collector, CompilerProtocol proto) {
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
                proto.diagnostic("ERROR", render(message::write));
            }
            any = true;
        }
        for (int i = 0; i < collector.getWarningCount(); i++) {
            WarningMessage warning = collector.getWarning(i);
            proto.diagnostic("WARNING", render(warning::write));
        }
        return any;
    }

    private static String render(Consumer<PrintWriter> write) {
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            write.accept(pw);
        }
        return sw.toString().strip();
    }

    /** The scratch root for joint-mode side products: the spec's workdir, else a temp dir. */
    private static File scratchDir(CompileSpec spec) throws IOException {
        if (spec.workDir != null) {
            spec.workDir.mkdirs();
            return spec.workDir;
        }
        return Files.createTempDirectory("jk-groovyc-").toFile();
    }
}
