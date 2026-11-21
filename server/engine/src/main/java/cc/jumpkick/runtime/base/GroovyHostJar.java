// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.host.Classpaths;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.util.ToolUse;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.spi.ToolProvider;

/**
 * Compiles {@code JkGroovyHost.java}, the runner {@link BuildLogicGroovyHost} forks, into a jar under
 * {@code <store>/tools/groovy-host/<groovy>-<hash>/}, once, with this JDK's own javac and jar, against
 * the pinned {@code groovy.jar}.
 */
final class GroovyHostJar {

    static final String MAIN = "JkGroovyHost";

    private static final String SOURCE_RESOURCE = "/cc/jumpkick/runtime/JkGroovyHost.java";
    private static final String JAR_NAME = "jk-groovy-host.jar";
    private static final String FAMILY = "groovy-host";

    private GroovyHostJar() {}

    /** The runner source, read from the engine jar. */
    static String source() throws IOException {
        try (InputStream in = GroovyHostJar.class.getResourceAsStream(SOURCE_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("[build] logic: " + SOURCE_RESOURCE + " missing from the jk image");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Hash of what the runner's bytes depend on: its source, the Groovy it compiles against and the
     * JDK release javac targets. Part of every compiled script's cache key too.
     */
    static String hash() throws IOException {
        return Hashing.sha256Hex(source() + "|" + BuildLogicGroovyHost.GROOVY_VER + "|"
                        + Runtime.version().feature())
                .substring(0, 16);
    }

    /** The runner jar, compiling it against {@code groovyJar} if this version of it has not been built. */
    static Path ensure(Path groovyJar) throws IOException {
        Path toolsRoot = JkDirs.tools();
        Path jar = ensure(
                groovyJar,
                toolsRoot
                        .resolve(FAMILY)
                        .resolve(BuildLogicGroovyHost.GROOVY_VER + "-" + hash())
                        .resolve(JAR_NAME));
        ToolUse.record(toolsRoot, FAMILY, Objects.requireNonNull(jar.getParent()));
        return jar;
    }

    /**
     * The runner jar at {@code jar}, compiled there if absent. Each call compiles in a staging
     * directory of its own beside the jar and moves only the finished jar into place, so two engines
     * sharing a home never read each other's half-written output.
     */
    static Path ensure(Path groovyJar, Path jar) throws IOException {
        if (Files.isRegularFile(jar) && Files.size(jar) > 0) return jar;
        Path dir = Objects.requireNonNull(jar.getParent(), "groovy host jar dir");
        Files.createDirectories(dir);
        Path work = Files.createTempDirectory(dir, "compile-");
        try {
            Path src = work.resolve(MAIN + ".java");
            Files.writeString(src, source(), StandardCharsets.UTF_8);
            Path classes = Files.createDirectories(work.resolve("classes"));
            tool(
                    "javac",
                    "compiling",
                    "-nowarn",
                    "-proc:none",
                    "-cp",
                    Classpaths.join(List.of(groovyJar)),
                    "-d",
                    classes.toString(),
                    src.toString());
            Path staging = work.resolve(JAR_NAME);
            tool("jar", "packaging", "--create", "--file", staging.toString(), "-C", classes.toString(), ".");
            // A racing call that finished first left the same bytes; its jar is as good as this one.
            if (!(Files.isRegularFile(jar) && Files.size(jar) > 0)) {
                try {
                    Files.move(staging, jar, StandardCopyOption.REPLACE_EXISTING);
                } catch (FileAlreadyExistsException | AccessDeniedException raced) {
                    // Windows will not replace a jar the racing call just moved in or still holds.
                    if (!(Files.isRegularFile(jar) && Files.size(jar) > 0)) throw raced;
                }
            }
            return jar;
        } finally {
            PathUtil.deleteRecursively(work);
        }
    }

    /** Run one of this JDK's own tools in-process, failing with its output when it fails. */
    private static void tool(String name, String doing, String... args) {
        ToolProvider tool = ToolProvider.findFirst(name)
                .orElseThrow(() -> new IllegalStateException("[build] logic: this JDK has no " + name));
        StringWriter log = new StringWriter();
        PrintWriter w = new PrintWriter(log);
        int exit = tool.run(w, w, args);
        w.flush();
        if (exit != 0) {
            throw new IllegalStateException("[build] logic: " + doing + " the Groovy script runner failed:\n"
                    + log.toString().strip());
        }
    }
}
