// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.engine.JobWorkers;
import cc.jumpkick.host.Classpaths;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Os;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Compiles {@code JkKtsHost.kt} — the child program {@link KtsSession} runs — into a jar under
 * {@code <store>/tools/kts-host/<hash>/}, once.
 *
 * <p>jk's own build is Java, so this one Kotlin file is not built by it. It is provisioned the way
 * any other tool is: compiled on first use by the Kotlin distribution jk already downloads, then
 * reused. Ordinary {@code .kt} produces ordinary bytecode, so the result runs under plain {@code
 * java} — the compiled-script metadata problem that stops a {@code kotlinc -d} script jar from
 * running does not apply here.
 *
 * <p>The directory is keyed by the source's hash and the Kotlin version, so editing the host or
 * moving Kotlin versions produces a new jar rather than silently reusing a stale one.
 */
final class KtsHostJar {

    /** Jars the host needs to compile and to run: the scripting host, its definition, the compiler. */
    static final List<String> KOTLIN_JARS = List.of(
            "kotlin-main-kts.jar",
            "kotlin-scripting-common.jar",
            "kotlin-scripting-jvm.jar",
            "kotlin-script-runtime.jar",
            "kotlin-stdlib.jar",
            "kotlin-compiler.jar",
            "kotlin-scripting-compiler.jar",
            "kotlin-scripting-compiler-impl.jar",
            "kotlin-reflect.jar");

    private static final String SOURCE_RESOURCE = "/cc/jumpkick/runtime/JkKtsHost.kt";
    private static final String JAR_NAME = "jk-kts-host.jar";

    private KtsHostJar() {}

    /** The host source, read from the engine jar. */
    static String source() throws IOException {
        try (InputStream in = KtsHostJar.class.getResourceAsStream(SOURCE_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("[build] logic: " + SOURCE_RESOURCE + " missing from the jk image");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Classpath entries under {@code <kotlinHome>/lib}, in {@link #KOTLIN_JARS} order. */
    static List<Path> kotlinClasspath(Path kotlinHome) {
        List<Path> out = new ArrayList<>(KOTLIN_JARS.size());
        for (String jar : KOTLIN_JARS) {
            out.add(kotlinHome.resolve("lib").resolve(jar));
        }
        return out;
    }

    /**
     * Where the compiled host lives for this (source, Kotlin version) pair. Beside the other
     * provisioned tools, not under the cache tier: it is a built artifact, and the cache retention
     * sweep reclaims what it finds there.
     */
    static Path jarPath(String kotlinVersion) throws IOException {
        String hash = Hashing.sha256Hex(source()).substring(0, 16);
        return JkDirs.tools()
                .resolve("kts-host")
                .resolve(kotlinVersion + "-" + hash)
                .resolve(JAR_NAME);
    }

    /**
     * The host jar, compiling it if this (source, Kotlin version) pair has not been built yet.
     *
     * <p>Compiled to a temporary name and moved into place, so two builds racing here cannot leave a
     * half-written jar that later runs report as a corrupt classpath entry.
     */
    static Path ensure(Path kotlinHome, String kotlinVersion) throws IOException, InterruptedException {
        Path jar = jarPath(kotlinVersion);
        if (Files.isRegularFile(jar) && Files.size(jar) > 0) return jar;

        Path dir = jar.getParent();
        Files.createDirectories(dir);
        Path src = dir.resolve("JkKtsHost.kt");
        Files.writeString(src, source(), StandardCharsets.UTF_8);

        String kotlincName = Os.isWindows() ? "kotlinc.bat" : "kotlinc";
        Path kotlinc = kotlinHome.resolve("bin").resolve(kotlincName);
        if (!Files.isRegularFile(kotlinc)) {
            throw new IllegalStateException("[build] logic: kotlinc not found at " + kotlinc);
        }
        Path staging = Files.createTempFile(dir, "host-", ".jar");
        Files.deleteIfExists(staging);

        List<String> cmd = new ArrayList<>();
        cmd.add(kotlinc.toString());
        cmd.add("-nowarn");
        cmd.add("-cp");
        cmd.add(Classpaths.join(kotlinClasspath(kotlinHome)));
        cmd.add("-d");
        cmd.add(staging.toString());
        cmd.add(src.toString());
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = JobWorkers.start(pb);
        String log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            Files.deleteIfExists(staging);
            throw new IllegalStateException("[build] logic: compiling the .kts host failed:\n" + log.strip());
        }
        Files.move(staging, jar, StandardCopyOption.REPLACE_EXISTING);
        Files.deleteIfExists(src);
        return jar;
    }
}
