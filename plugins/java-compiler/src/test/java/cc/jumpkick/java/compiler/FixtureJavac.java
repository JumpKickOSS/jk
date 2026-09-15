// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * In-process javac for test fixtures. Diagnostics go to a buffer, not {@code System.err}: a test
 * worker merges stderr into the protocol pipe, and javac aborting on that stream is {@code rc=4}.
 * {@code -proc:none} keeps the module's annotation processors off the fixture sources.
 */
final class FixtureJavac {

    private FixtureJavac() {}

    static void compile(Path dest, Path... sources) {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) throw new IllegalStateException("no system javac");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        List<String> args = new ArrayList<>();
        args.add("-proc:none");
        args.add("-d");
        args.add(dest.toString());
        for (Path src : sources) args.add(src.toString());
        int rc = javac.run(null, null, new PrintStream(err, true, StandardCharsets.UTF_8), args.toArray(new String[0]));
        if (rc != 0) {
            throw new IllegalStateException(
                    "fixture javac failed, rc=" + rc + "\n" + err.toString(StandardCharsets.UTF_8));
        }
    }
}
