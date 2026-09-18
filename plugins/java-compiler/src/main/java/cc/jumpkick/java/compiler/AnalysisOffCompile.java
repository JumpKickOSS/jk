// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import cc.jumpkick.host.Classpaths;
import cc.jumpkick.java.compiler.ZincSetup.QuietLogger;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import xsbti.VirtualFile;
import xsbti.compile.IncToolOptionsUtil;
import xsbti.compile.SingleOutput;

/**
 * The compile a module gets when Zinc's Java analysis cannot run on it: a full javac run, no
 * analysis, one warning. The analysis loads each compiled class's library supertypes with the
 * worker's own JDK and reflects over their members, so a supertype whose signature names a type
 * that JDK no longer has — a release-11 module extending a class that returns {@code
 * java.security.acl.Group[]} — ends it with a {@link LinkageError} javac never saw, since {@code
 * --release} reads the older API from ct.sym. {@link ZincWorkdir#analysisOff} keeps the decision.
 */
final class AnalysisOffCompile {

    private AnalysisOffCompile() {}

    /**
     * A full javac run with no incremental analysis: every source, a clean class output, and one
     * warning naming the type the analysis could not load. The result is the compile's classes, as
     * complete as Zinc's would be; only the next build's ability to compile less is given up. The
     * in-process javac reports to the reporter it was built with, so the result carries what this
     * run added to it — a compile the analysis ended after javac had already spoken does not say
     * each javac warning twice.
     */
    static ZincJavaCompiler.Result run(
            RecordingJavaCompiler javac,
            VirtualFile[] sourceFiles,
            String[] javacOpts,
            List<Path> classpath,
            Path classOutput,
            CollectingReporter reporter,
            String missingType)
            throws IOException {
        int before = reporter.diagnostics().size();
        ZincJavaCompiler.deleteClassFiles(classOutput);
        List<String> options = new ArrayList<>(Arrays.asList(javacOpts));
        if (!options.contains("-classpath") && !options.contains("-cp") && !options.contains("--module-path")) {
            options.add("-classpath");
            options.add(Classpaths.join(classpath));
        }
        SingleOutput output = () -> classOutput.toFile();
        boolean ok = javac.run(
                sourceFiles,
                options.toArray(new String[0]),
                output,
                IncToolOptionsUtil.defaultIncToolOptions(),
                reporter,
                QuietLogger.INSTANCE);
        List<ZincJavaCompiler.Diag> all = reporter.diagnostics();
        List<ZincJavaCompiler.Diag> diags = new ArrayList<>(all.subList(Math.min(before, all.size()), all.size()));
        if (!ok || reporter.hasErrors()) {
            return new ZincJavaCompiler.Result(false, diags, javac.compiledSources());
        }
        diags.add(new ZincJavaCompiler.Diag(
                "WARNING",
                null,
                0,
                0,
                "compiled without incremental analysis: the analysis loads this module's classes and their library"
                        + " supertypes with the compiler worker's JDK, which has no " + missingType
                        + " — javac compiled the module at its --release, so every build compiles it in full;"
                        + " a change to the compile classpath or a clean build tries the analysis again"));
        return new ZincJavaCompiler.Result(true, diags, javac.compiledSources());
    }

    /** The type a {@link LinkageError} from the analysis could not load, as a class name. */
    static String missingType(LinkageError e) {
        String message = e.getMessage();
        if (e instanceof NoClassDefFoundError && message != null && !message.isBlank()) {
            String name = message.strip();
            while (name.startsWith("[")) name = name.substring(1);
            if (name.startsWith("L") && name.endsWith(";")) name = name.substring(1, name.length() - 1);
            return name.replace('/', '.');
        }
        return e.toString();
    }
}
