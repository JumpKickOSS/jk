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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.spi.ToolProvider;

/**
 * A small Java program jk ships as source under {@code /cc/jumpkick/runtime/} and compiles once,
 * with this JDK's own javac and jar, into {@code <store>/tools/<family>/<version>-<hash>/<jarName>}.
 * The programs run in child JVMs whose classpath is a third-party runtime (Groovy, Kotlin), which
 * jk's own build does not link.
 *
 * @param sources resource file names, each one top-level class
 */
public record ResourceToolJar(String family, String jarName, List<String> sources) {

    private static final String RESOURCE_DIR = "/cc/jumpkick/runtime/";

    public ResourceToolJar {
        sources = List.copyOf(sources);
    }

    /** One source, read from the engine jar. */
    public String source(String name) throws IOException {
        try (InputStream in = ResourceToolJar.class.getResourceAsStream(RESOURCE_DIR + name)) {
            if (in == null) throw new IllegalStateException(RESOURCE_DIR + name + " missing from the jk image");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Hash of what the jar's bytes depend on: every source, the {@code version} of the runtime they
     * compile against, and the JDK release javac targets.
     */
    public String hash(String version) throws IOException {
        StringBuilder all = new StringBuilder();
        for (String name : sources)
            all.append(name).append('\n').append(source(name)).append('\n');
        all.append(version).append('|').append(Runtime.version().feature());
        return Hashing.sha256Hex(all.toString()).substring(0, 16);
    }

    /** The jar compiled against {@code classpath} (the runtime at {@code version}), building it once. */
    public Path ensure(List<Path> classpath, String version) throws IOException {
        Path toolsRoot = JkDirs.tools();
        Path jar = ensureAt(
                classpath,
                toolsRoot.resolve(family).resolve(version + "-" + hash(version)).resolve(jarName));
        ToolUse.record(toolsRoot, family, Objects.requireNonNull(jar.getParent()));
        return jar;
    }

    /**
     * The jar at {@code jar}, compiled there if absent. Each call compiles in a staging directory of
     * its own beside the jar and moves only the finished jar into place, so two engines sharing a
     * home never read each other's half-written output.
     */
    Path ensureAt(List<Path> classpath, Path jar) throws IOException {
        if (Files.isRegularFile(jar) && Files.size(jar) > 0) return jar;
        Path dir = Objects.requireNonNull(jar.getParent(), "tool jar dir");
        Files.createDirectories(dir);
        Path work = Files.createTempDirectory(dir, "compile-");
        try {
            Path classes = Files.createDirectories(work.resolve("classes"));
            List<String> javac = new ArrayList<>(List.of("-nowarn", "-proc:none", "-d", classes.toString()));
            if (!classpath.isEmpty()) javac.addAll(List.of("-cp", Classpaths.join(classpath)));
            for (String name : sources) {
                Path src = work.resolve(name);
                Files.writeString(src, source(name), StandardCharsets.UTF_8);
                javac.add(src.toString());
            }
            tool("javac", "compiling", javac);
            Path staging = work.resolve(jarName);
            tool("jar", "packaging", List.of("--create", "--file", staging.toString(), "-C", classes.toString(), "."));
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
    private void tool(String name, String doing, List<String> args) {
        ToolProvider tool =
                ToolProvider.findFirst(name).orElseThrow(() -> new IllegalStateException("this JDK has no " + name));
        StringWriter log = new StringWriter();
        PrintWriter w = new PrintWriter(log);
        int exit = tool.run(w, w, args.toArray(String[]::new));
        w.flush();
        if (exit != 0) {
            throw new IllegalStateException(
                    doing + " " + jarName + " failed:\n" + log.toString().strip());
        }
    }
}
