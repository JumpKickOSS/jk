// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import com.sun.source.util.JavacTask;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import sbt.internal.inc.javac.DiagnosticsReporter;
import xsbti.Logger;
import xsbti.PathBasedFile;
import xsbti.Reporter;
import xsbti.VirtualFile;
import xsbti.compile.IncToolOptions;
import xsbti.compile.JavaCompiler;
import xsbti.compile.Output;

/**
 * ToolProvider javac that installs wrapped processors so generated-file provenance is recorded.
 * Used instead of Zinc's {@code JavaCompiler.local} when a processor path is present.
 */
final class ProvenanceJavac implements JavaCompiler {

    private final URLClassLoader loader;
    private final ApProvenance provenance;
    private final Charset encoding;

    ProvenanceJavac(URLClassLoader loader, ApProvenance provenance, Charset encoding) {
        this.loader = loader;
        this.provenance = provenance;
        this.encoding = encoding;
    }

    @Override
    public boolean run(
            VirtualFile[] sources,
            String[] options,
            Output output,
            IncToolOptions incToolOptions,
            Reporter reporter,
            Logger log) {
        var javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) throw new IllegalStateException("no system javac (run under a JDK)");
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        Path classOut = output.getSingleOutputAsPath().orElseThrow();
        // This path reads sources through the file manager, and the charset given here is what
        // decides: it outranks -encoding, which BaseFileManager.getDecoder only falls back to.
        // Same constant as the flag, so the two spellings of the charset cannot drift apart.
        try (StandardJavaFileManager fm = javac.getStandardFileManager(diags, Locale.ROOT, encoding)) {
            Files.createDirectories(classOut);
            fm.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classOut));
            for (int i = 0; i < options.length - 1; i++) {
                if ("-s".equals(options[i])) {
                    Path srcOut = Path.of(options[i + 1]);
                    Files.createDirectories(srcOut);
                    fm.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT, List.of(srcOut));
                }
            }
            List<Path> srcPaths = new ArrayList<>();
            for (VirtualFile vf : sources) {
                if (vf instanceof PathBasedFile pathFile) srcPaths.add(pathFile.toPath());
            }
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromPaths(srcPaths);
            JavacTask task = (JavacTask) javac.getTask(null, fm, diags, Arrays.asList(options), null, units);
            task.setProcessors(provenance.wrap(ZincJavaCompiler.freshProcessors(loader)));
            boolean ok = task.call();
            DiagnosticsReporter bridge = new DiagnosticsReporter(reporter);
            for (var d : diags.getDiagnostics()) bridge.report(d);
            return ok && !bridge.hasErrors();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
