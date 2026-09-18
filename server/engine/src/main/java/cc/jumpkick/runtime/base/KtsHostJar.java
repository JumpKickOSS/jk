// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.engine.plugin.JobWorkers;
import cc.jumpkick.host.Classpaths;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
 * <p>Compilation goes through {@code java -cp kotlin-compiler.jar K2JVMCompiler}, not {@code
 * kotlinc}/{@code kotlinc.bat}. On Windows, {@code ProcessBuilder} hands {@code .bat} files to
 * {@code cmd.exe}, which splits a {@code -cp} value on {@code ;} before the bat sees it; kotlinc
 * then reports every jar after the first as {@code source entry is not a Kotlin file}.
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

    /** The host jar, compiling it if this (source, Kotlin version) pair has not been built yet. */
    static Path ensure(Path kotlinHome, String kotlinVersion) throws IOException, InterruptedException {
        return ensure(kotlinHome, jarPath(kotlinVersion));
    }

    /**
     * The host jar at {@code jar}, compiling it there if it is absent.
     *
     * <p>Each call compiles inside its own staging directory beside the jar — the source it writes
     * and the jar it produces — and moves only the finished jar into place. Two JVMs sharing a home
     * can both find the jar missing at once (two engines, a sharded test run), and a source both
     * wrote to one path was the first finisher's to delete while the second's compiler was still
     * reading it. Staging per call, nothing is shared until the move, and a half-written jar never
     * sits where a later run would load it as a corrupt classpath entry.
     */
    static Path ensure(Path kotlinHome, Path jar) throws IOException, InterruptedException {
        if (Files.isRegularFile(jar) && Files.size(jar) > 0) return jar;

        Path compilerJar = kotlinHome.resolve("lib").resolve("kotlin-compiler.jar");
        if (!Files.isRegularFile(compilerJar)) {
            throw new IllegalStateException("[build] logic: kotlin-compiler.jar not found at " + compilerJar);
        }
        Path dir = Objects.requireNonNull(jar.getParent(), "kts host jar dir");
        Files.createDirectories(dir);
        Path work = Files.createTempDirectory(dir, "compile-");
        try {
            Path src = work.resolve("JkKtsHost.kt");
            Files.writeString(src, source(), StandardCharsets.UTF_8);
            Path staging = work.resolve(JAR_NAME);

            List<String> cmd = new ArrayList<>();
            cmd.add(JdkFingerprint.java(JavaHomes.runningJavaHome()).toString());
            cmd.add("-cp");
            cmd.add(compilerJar.toAbsolutePath().toString());
            cmd.add("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler");
            cmd.add("-nowarn");
            cmd.add("-cp");
            cmd.add(Classpaths.join(kotlinClasspath(kotlinHome)));
            cmd.add("-d");
            cmd.add(staging.toString());
            cmd.add(src.toString());
            ProcessBuilder pb = JavaHomes.underJdk(new ProcessBuilder(cmd), JavaHomes.runningJavaHome());
            pb.redirectErrorStream(true);
            Process p = JobWorkers.start(pb);
            String log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.waitFor() != 0) {
                throw new IllegalStateException("[build] logic: compiling the .kts host failed:\n" + log.strip());
            }
            // A racing call that finished first left the same bytes; its jar is as good as this one.
            if (!(Files.isRegularFile(jar) && Files.size(jar) > 0)) {
                Files.move(staging, jar, StandardCopyOption.REPLACE_EXISTING);
            }
            return jar;
        } finally {
            PathUtil.deleteRecursively(work);
        }
    }
}
