// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import com.sun.source.util.JavacTask;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.jspecify.annotations.Nullable;

/**
 * For other modules' tests: compiles Java sources with the reference index jk's compile writes beside
 * the classes ({@code <target>/incremental/<classes>-refs/<source>.refs}), so a test of a reader
 * (callers, rename) reads the index javac wrote, not one written by hand.
 */
public final class IndexedJavac {

    private IndexedJavac() {}

    /**
     * Compiles {@code sources} of the module at {@code moduleRoot} into {@code classes} with {@code
     * classpath} (may be empty) and writes their index; {@code null} when javac passed, else its
     * diagnostics.
     */
    public static @Nullable String compile(Path moduleRoot, Path classes, List<Path> sources, List<Path> classpath)
            throws IOException {
        Files.createDirectories(classes);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try (StandardJavaFileManager fm = javac.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            List<String> options = new ArrayList<>(List.of("-g", "-proc:none", "-d", classes.toString()));
            // Never the test JVM's own class path, which javac takes when given none.
            options.add("-cp");
            options.add(
                    classpath.isEmpty()
                            ? classes.toString()
                            : String.join(
                                    File.pathSeparator,
                                    classpath.stream().map(Path::toString).toList()));
            JavacTask task = (JavacTask) javac.getTask(
                    new PrintWriter(err, true, StandardCharsets.UTF_8),
                    fm,
                    null,
                    options,
                    null,
                    fm.getJavaFileObjects(sources.toArray(Path[]::new)));
            References refs = new References(task);
            task.addTaskListener(refs);
            if (!task.call()) return err.toString(StandardCharsets.UTF_8);
            refs.write(classes, moduleRoot);
        }
        return null;
    }
}
