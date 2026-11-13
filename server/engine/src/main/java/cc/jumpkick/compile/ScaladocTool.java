// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.engine.plugin.JobWorkers;
import cc.jumpkick.host.Classpaths;
import cc.jumpkick.host.Processes;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jdk.JdkFingerprint;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Forks Scala 3's scaladoc over a module's compiled classes: scaladoc reads the TASTy files scalac
 * wrote, not the sources, so it documents exactly what the compile produced. Arguments travel in an
 * {@code @argfile}. The site root points at an absent directory, so a module's own {@code docs/}
 * never turns the API docs into a static site.
 */
public final class ScaladocTool {

    /** scaladoc's entry point in {@code org.scala-lang:scaladoc_3}. */
    static final String MAIN = "dotty.tools.scaladoc.Main";

    private static final long TIMEOUT_MINUTES = 30;

    private ScaladocTool() {}

    /**
     * Document the TASTy under {@code classesDir} into {@code outDir}, which must exist and be
     * empty. {@code toolClasspath} is the scaladoc closure; {@code classpath} is what the module
     * compiled against.
     */
    public static JavadocTool.Result run(
            Path javaHome,
            List<Path> toolClasspath,
            Path outDir,
            String moduleName,
            String moduleVersion,
            Path classesDir,
            List<Path> classpath)
            throws IOException, InterruptedException {
        Path argfile = Files.createTempFile(outDir.getParent(), "scaladoc-", ".args");
        try {
            Files.write(
                    argfile,
                    arguments(outDir, moduleName, moduleVersion, classesDir, classpath),
                    StandardCharsets.UTF_8);
            ProcessBuilder pb = JavaHomes.underJdk(
                            new ProcessBuilder(
                                    JdkFingerprint.java(javaHome).toString(),
                                    "-cp",
                                    Classpaths.join(toolClasspath),
                                    MAIN,
                                    "@" + argfile.toAbsolutePath()),
                            javaHome)
                    // Not the output: scaladoc deletes that first, and Windows refuses to delete a
                    // process's working directory.
                    .directory(Objects.requireNonNull(argfile.toAbsolutePath().getParent())
                            .toFile())
                    .redirectErrorStream(true);
            Process proc = JobWorkers.start(pb);
            Processes.Captured c = Processes.capture(proc, Duration.ofMinutes(TIMEOUT_MINUTES));
            if (c.timedOut()) throw new IOException("scaladoc timed out after " + TIMEOUT_MINUTES + " minutes");
            return new JavadocTool.Result(c.exit(), warnings(c.output()), List.of(), c.output());
        } finally {
            Files.deleteIfExists(argfile);
        }
    }

    /** One argument per line: the output, the project's name and version, the classpath, then the input. */
    static List<String> arguments(
            Path outDir, String moduleName, String moduleVersion, Path classesDir, List<Path> classpath) {
        List<String> args = new ArrayList<>();
        args.add("-d");
        args.add(quote(outDir.toAbsolutePath().toString()));
        args.add("-project");
        args.add(quote(moduleName));
        args.add("-project-version");
        args.add(quote(moduleVersion));
        args.add("-siteroot");
        args.add(quote(outDir.resolve("no-site").toAbsolutePath().toString()));
        if (!classpath.isEmpty()) {
            args.add("-classpath");
            args.add(quote(Classpaths.join(classpath)));
        }
        args.add(quote(classesDir.toAbsolutePath().toString()));
        return args;
    }

    /** scaladoc's {@code [warn]} lines, without the tag. */
    static List<String> warnings(String output) {
        List<String> out = new ArrayList<>();
        for (String line : output.split("\\R")) {
            String l = line.strip();
            if (l.startsWith("[warn]")) out.add(l.substring("[warn]".length()).strip());
        }
        return out;
    }

    /** Quote an argfile token the way the scalac argfile reader expects. */
    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
