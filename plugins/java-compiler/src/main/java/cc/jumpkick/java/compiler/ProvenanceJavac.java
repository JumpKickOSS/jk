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
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.jspecify.annotations.Nullable;
import sbt.internal.inc.javac.DiagnosticsReporter;
import xsbti.Logger;
import xsbti.PathBasedFile;
import xsbti.Reporter;
import xsbti.VirtualFile;
import xsbti.compile.IncToolOptions;
import xsbti.compile.JavaCompiler;
import xsbti.compile.Output;

/**
 * ToolProvider javac for every in-process compile this worker runs, installing wrapped processors so
 * generated-file provenance is recorded when there are processors to install.
 *
 * <p>This is used instead of Zinc's {@code JavaCompiler.local} whether or not a processor path is
 * present, because the file manager it compiles through is held across compiles rather than opened
 * per compile — see {@link ReusedJavacFileManager} for what that is worth. Zinc's own compiler opens
 * one per compile, so a build that used it for its processor-free modules would pay full classpath
 * indexing on most of its compiles.
 */
final class ProvenanceJavac implements JavaCompiler {

    /**
     * Null when this compile has no processors to install, which is not the same as installing an
     * empty list: {@code setProcessors} with an empty list turns annotation processing <em>off</em>
     * outright, while leaving it unset lets javac decide. A compile without a {@code -processorpath}
     * that asks for {@code -proc:full} is entitled to run a processor from its own compile classpath,
     * and that only works if this stays unset.
     */
    private final @Nullable URLClassLoader loader;

    private final ApProvenance provenance;
    private final Charset encoding;

    ProvenanceJavac(@Nullable URLClassLoader loader, ApProvenance provenance, Charset encoding) {
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
        StandardJavaFileManager fm =
                ReusedJavacFileManager.acquire(javac, encoding, diags, declaresProcessorPath(options));
        try {
            Files.createDirectories(classOut);
            fm.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classOut));
            // Every location this compile depends on is set on every compile, never left to whatever
            // the last one set: the manager is reused, so a module compiled without -s must clear the
            // previous module's generated-source directory instead of inheriting it and writing its
            // generated sources there. Clearing goes through setLocation, the only one of the two
            // that documents null as "reset to the default" — setLocationFromPaths throws on it.
            Path srcOut = sourceOutput(options);
            if (srcOut == null) {
                fm.setLocation(StandardLocation.SOURCE_OUTPUT, null);
            } else {
                Files.createDirectories(srcOut);
                fm.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT, List.of(srcOut));
            }
            List<Path> srcPaths = new ArrayList<>();
            for (VirtualFile vf : sources) {
                // Every source this worker compiles is a real file, because the converter Zinc is set
                // up with is PlainVirtualFileConverter. Refusing anything else is deliberate: this
                // path can only compile what it can name as a path, and dropping a source silently
                // would ship a jar with a class missing and no diagnostic to explain it.
                if (!(vf instanceof PathBasedFile pathFile)) {
                    throw new IllegalStateException("source is not a file on disk: " + vf.id());
                }
                srcPaths.add(pathFile.toPath());
            }
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromPaths(srcPaths);
            JavacTask task = (JavacTask) javac.getTask(null, fm, diags, Arrays.asList(options), null, units);
            if (loader != null) {
                task.setProcessors(provenance.wrap(ZincJavaCompiler.freshProcessors(loader)));
            }
            boolean ok = task.call();
            DiagnosticsReporter bridge = new DiagnosticsReporter(reporter);
            for (var d : diags.getDiagnostics()) bridge.report(d);
            return ok && !bridge.hasErrors();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Clears every location, so a reused manager looks to javac exactly like a freshly opened one.
     *
     * <p>This is what makes reuse safe rather than merely fast. javac sets locations on the file
     * manager as it parses options — {@code -classpath}, {@code -processorpath}, {@code -s} — and
     * only the ones this compile names get set. Anything the <em>previous</em> compile set and this
     * one does not mention would otherwise still be there: a module that declares no processor path
     * would inherit the last module's and silently run its processors, generating code into a build
     * that never asked for any. Null is documented as "reset to the default", which is precisely the
     * state a new manager would be in.
     */
    /** Whether this compile names its own {@code -processorpath}. */
    private static boolean declaresProcessorPath(String[] options) {
        for (String option : options) {
            if ("-processorpath".equals(option) || "--processor-path".equals(option)) return true;
        }
        return false;
    }

    /**
     * The {@code -s} generated-source directory, or null when this compile did not ask for one. The
     * last {@code -s} wins, which is both what javac does with a repeated option and what the loop
     * this replaced did by setting the location once per match.
     */
    private static @Nullable Path sourceOutput(String[] options) {
        Path srcOut = null;
        for (int i = 0; i < options.length - 1; i++) {
            if ("-s".equals(options[i])) srcOut = Path.of(options[i + 1]);
        }
        return srcOut;
    }
}
