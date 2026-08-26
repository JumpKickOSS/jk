// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.image;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.plugin.build.TaskExec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * A Spring Boot jar unpacked into the shape an AOT cache can be trained against.
 *
 * <p>Boot's own jar nests its dependencies under {@code BOOT-INF/lib} and loads them with a custom
 * loader, so nothing useful is on the JVM classpath; jk's layered image goes the other way and
 * explodes the classes, which a CDS dump refuses outright because the entry is a directory
 * (JDK-8329980, Won't Fix). Neither shape can carry a cache.
 *
 * <p>Boot ships the answer: {@code java -Djarmode=tools -jar app.jar extract} produces a thin
 * launcher jar whose manifest {@code Class-Path} names {@code lib/*.jar} — all jars, relative
 * paths, no wildcards. That is what Spring's own Dockerfile recipe and the Paketo buildpack both
 * use, and it is what this produces.
 */
final class BootLayout {

    private static final long EXTRACT_TIMEOUT_SECONDS = 120;

    /** The extracted tree: a thin launcher jar and the libraries its manifest points at. */
    record Extracted(Path root, String launcherJar) {}

    private BootLayout() {}

    /** True when {@code jar} is a Spring Boot application jar rather than a plain one. */
    static boolean isBootJar(Path jar) {
        if (jar == null || !Files.isRegularFile(jar)) return false;
        try (JarFile jf = new JarFile(jar.toFile())) {
            Manifest mf = jf.getManifest();
            if (mf == null) return false;
            var attrs = mf.getMainAttributes();
            if (attrs.getValue("Spring-Boot-Version") != null) return true;
            String main = attrs.getValue("Main-Class");
            return main != null && main.startsWith("org.springframework.boot.loader.");
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Run Boot's own {@code jarmode} extractor. Uses {@code java} from the image's JRE when one was
     * unpacked, so the tool runs on the same JVM the image will — {@code extract} only rewrites a
     * jar, but there is no reason to introduce a second JVM into the equation.
     */
    static Extracted extract(Path bootJar, Path dest, Path javaBin) throws IOException, InterruptedException {
        PathUtil.deleteRecursivelyOrThrow(dest);
        Files.createDirectories(dest);
        // start(), not run(): this fork needs the timeout below, and run() drains to EOF. The argv
        // still comes from the SDK's one fork owner.
        Process process = new TaskExec.ToolRun(javaBin)
                .args(List.of(
                        "-Djarmode=tools",
                        "-jar",
                        bootJar.toAbsolutePath().toString(),
                        "extract",
                        "--force",
                        "--destination",
                        dest.toAbsolutePath().toString()))
                .start();
        // Drain on a separate thread: readAllBytes() on this thread blocks to EOF, which makes
        // the timeout below unreachable while the child holds its pipe open.
        StringBuilder captured = new StringBuilder();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var in = process.inputReader(StandardCharsets.UTF_8)) {
                in.lines().forEach(l -> captured.append(l).append('\n'));
            } catch (IOException ignored) {
            }
        });
        if (!process.waitFor(EXTRACT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("`-Djarmode=tools extract` did not finish within " + EXTRACT_TIMEOUT_SECONDS + "s");
        }
        try {
            reader.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String output = captured.toString();
        if (process.exitValue() != 0) {
            throw new IOException("`-Djarmode=tools extract` failed (exit " + process.exitValue() + "):\n" + output);
        }
        String launcher = launcherJarIn(dest);
        if (launcher == null) {
            throw new IOException("`-Djarmode=tools extract` produced no launcher jar in " + dest
                    + ". It needs Spring Boot 3.3 or newer:\n" + output);
        }
        return new Extracted(dest, launcher);
    }

    /** The thin jar sits at the root; every other jar is under {@code lib/}. */
    private static String launcherJarIn(Path dest) throws IOException {
        try (var list = Files.list(dest)) {
            return list.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".jar"))
                    .sorted()
                    .findFirst()
                    .orElse(null);
        }
    }
}
