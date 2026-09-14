// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.cli.engine.JvmClient;
import cc.jumpkick.command.toolchain.JkxLink;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.util.JkOwnership;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The JVM client's install shape, owned in one place so the installers and {@code jk self update}
 * lay it out identically: the fat jar under {@code <home>/lib/jk/jk-<version>.jar}, and a launcher
 * on the PATH — {@code bin/jk} (POSIX {@code sh}) or {@code bin/jk.bat} — that starts a JVM on it.
 * The installers place the jar and then run {@code java -jar <jar> self write-launcher}, so the
 * launcher text has one author, and a machine with no bash, no xz and no native client installs
 * from a JDK and a shell alone.
 *
 * <p><b>Which JVM.</b> {@code JK_JAVA_HOME} when set; else the JDK the launcher was installed with
 * (the one that ran {@code write-launcher}, so the one the installer verified is 25 or newer); else
 * {@code JAVA_HOME}; else {@code java} on the PATH. {@code JAVA_HOME} ranks below the installed JDK
 * on purpose: jk's own shell hook points it at the current project's JDK, which may be older than
 * the release this client is compiled for. {@code JK_CLIENT_OPTS} adds JVM flags; {@code
 * JK_JVM_ARGS} is left alone because the engine inherits it for its workers.
 *
 * <p><b>Startup.</b> A native client starts in tens of milliseconds; a JVM one pays a JVM start on
 * every command, so the launcher asks for C1 only and the serial collector — the JVM's own
 * default class-data archive does the rest. An application archive was measured and left out: it
 * bought nothing over the default one here and logged on every launch.
 */
public final class JvmClientInstall {

    /** The directory under the product lib that holds the client jar: {@code <home>/lib/jk/}. */
    public static final String LIB_NAME = "jk";

    /** The flags every launcher passes, before the user's {@code JK_CLIENT_OPTS}. */
    static final String JVM_FLAGS = "--enable-native-access=ALL-UNNAMED -XX:+UseSerialGC -XX:TieredStopAtLevel=1";

    private JvmClientInstall() {}

    /** The shipped name of the client jar for {@code version}: {@code jk-<version>.jar}. */
    public static String jarName(String version) {
        return "jk-" + version + ".jar";
    }

    /** Where the installed client jar lives: {@code <home>/lib/jk/}. */
    public static Path libDir() {
        return JkDirs.productLib().resolve(LIB_NAME);
    }

    /** The launcher's path under {@code binDir}: {@code jk}, or {@code jk.bat} on Windows. */
    public static Path launcher(Path binDir, boolean windows) {
        return binDir.resolve(windows ? "jk.bat" : "jk");
    }

    /** The {@code java} executable of the JVM running this process. */
    public static Path runningJava() {
        return JdkFingerprint.java(Path.of(System.getProperty("java.home")));
    }

    /**
     * Place {@code bytes} as {@code <libDir>/jk-<version>.jar} through a temp file and an atomic
     * move, then retire every other {@code jk-*.jar} in the directory: the
     * launcher names one jar, and a second one there is dead weight or, when an installer globs,
     * the wrong client. A jar a running client still maps cannot be deleted on Windows; it is parked
     * as {@code .old} and swept by the next install.
     */
    public static Path installJar(byte[] bytes, Path libDir, String version) throws IOException {
        Files.createDirectories(libDir);
        Path dest = libDir.resolve(jarName(version));
        Path tmp = dest.resolveSibling("." + dest.getFileName() + "-new");
        Files.write(tmp, bytes);
        AtomicWrites.moveInto(tmp, dest);
        retireOthers(libDir, dest);
        return dest;
    }

    /** As {@link #installJar(byte[], Path, String)} from a file. */
    public static Path installJar(Path source, Path libDir, String version) throws IOException {
        return installJar(Files.readAllBytes(source), libDir, version);
    }

    private static void retireOthers(Path libDir, Path keep) throws IOException {
        List<Path> stale = new ArrayList<>();
        PathUtil.forEachChild(libDir, (p, attrs) -> {
            String n = p.getFileName().toString();
            if (n.startsWith("jk-") && n.endsWith(".jar") && !p.equals(keep)) stale.add(p);
            if (n.endsWith(".old")) stale.add(p);
            return true;
        });
        for (Path p : stale) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException mapped) {
                if (!p.getFileName().toString().endsWith(".old")) EngineInstall.displaceToOld(p);
            }
        }
    }

    /**
     * Write the launcher for {@code jar} on {@code java} under {@code binDir}, parking the PATH
     * client it replaces, and point {@code jkx} at it. On Windows a leftover {@code jk.exe} is
     * parked too: PATHEXT prefers {@code .exe} to {@code .bat}, so one left beside the launcher is
     * what {@code jk} would keep starting.
     */
    public static Path writeLauncher(Path binDir, Path jar, Path java, boolean windows) throws IOException {
        Files.createDirectories(binDir);
        Path launcher = launcher(binDir, windows);
        String text = windows ? renderWindows(jar, java) : renderPosix(jar, java);
        if (windows) {
            EngineInstall.displaceToOld(binDir.resolve("jk.exe"));
            EngineInstall.displaceToOld(binDir.resolve("jkx.exe"));
        }
        EngineInstall.displaceToOld(launcher);
        Path tmp = launcher.resolveSibling("." + launcher.getFileName() + "-new");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        if (!windows && !tmp.toFile().setExecutable(true, false)) {
            throw new IOException("could not mark " + tmp + " executable");
        }
        AtomicWrites.moveInto(tmp, launcher);
        JkxLink.ensure(binDir, launcher);
        return launcher;
    }

    /** The POSIX launcher: plain {@code sh}, so a host without bash runs it. */
    static String renderPosix(Path jar, Path java) {
        String jarPath = jar.toAbsolutePath().toString();
        return "#!/bin/sh\n"
                + "# " + JkOwnership.GENERATED_BY + " — do not edit. The JumpKick client on a JVM;"
                + " `jk self update` rewrites this file.\n"
                + "JK_JAR=" + shellQuote(jarPath) + "\n"
                + "# JK_JAVA_HOME, else the JDK this launcher was installed with, else JAVA_HOME, else the PATH.\n"
                + "# JAVA_HOME ranks below the installed JDK: jk's shell hook points it at a project's JDK, which\n"
                + "# may be older than the release this client needs.\n"
                + "if [ -n \"${JK_JAVA_HOME:-}\" ]; then JAVA=\"$JK_JAVA_HOME/bin/java\"\n"
                + "elif [ -x " + shellQuote(java.toString()) + " ]; then JAVA=" + shellQuote(java.toString()) + "\n"
                + "elif [ -n \"${JAVA_HOME:-}\" ]; then JAVA=\"$JAVA_HOME/bin/java\"\n"
                + "else JAVA=java\n"
                + "fi\n"
                + "# shellcheck disable=SC2086 # JK_CLIENT_OPTS is a list of JVM flags\n"
                + "exec \"$JAVA\" " + JVM_FLAGS + " ${JK_CLIENT_OPTS:-}"
                + " \"-D" + JvmClient.JAR_PROPERTY + "=$JK_JAR\" \"-Djk.argv0=$0\" -jar \"$JK_JAR\" \"$@\"\n";
    }

    /** The Windows launcher: {@code cmd.exe} batch, ASCII, CRLF. */
    static String renderWindows(Path jar, Path java) {
        String jarPath = jar.toAbsolutePath().toString();
        return "@echo off\r\n"
                + "rem " + JkOwnership.GENERATED_BY + " -- do not edit. The JumpKick client on a JVM;"
                + " `jk self update` rewrites this file.\r\n"
                + "setlocal\r\n"
                + "set \"JK_JAR=" + jarPath + "\"\r\n"
                + "rem JK_JAVA_HOME, else the JDK this launcher was installed with, else JAVA_HOME, else the PATH.\r\n"
                + "if defined JK_JAVA_HOME (\r\n"
                + "  set \"JAVA=%JK_JAVA_HOME%\\bin\\java.exe\"\r\n"
                + ") else if exist \"" + java + "\" (\r\n"
                + "  set \"JAVA=" + java + "\"\r\n"
                + ") else if defined JAVA_HOME (\r\n"
                + "  set \"JAVA=%JAVA_HOME%\\bin\\java.exe\"\r\n"
                + ") else (\r\n"
                + "  set \"JAVA=java\"\r\n"
                + ")\r\n"
                + "\"%JAVA%\" " + JVM_FLAGS + " %JK_CLIENT_OPTS%"
                + " \"-D" + JvmClient.JAR_PROPERTY + "=%JK_JAR%\" \"-Djk.argv0=%~n0\" -jar \"%JK_JAR%\" %*\r\n"
                + "exit /b %ERRORLEVEL%\r\n";
    }

    /** Single-quote {@code s} for {@code sh}; an embedded quote becomes {@code '\''}. */
    static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
