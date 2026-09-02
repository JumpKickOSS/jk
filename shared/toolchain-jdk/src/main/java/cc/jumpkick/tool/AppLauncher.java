// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.tool;

import cc.jumpkick.host.Classpaths;
import cc.jumpkick.host.Os;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.util.JkOwnership;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Writes a launcher script for an application installed by {@code jk install}. Thin apps use
 * {@code java -cp} over repo/CAS jars; fat and minified apps use {@code java -jar} of the copy
 * under {@code <home>/lib/&lt;bin&gt;/}.
 */
public final class AppLauncher {

    private AppLauncher() {}

    /**
     * Write {@code ~/.jk/bin/<binName>} (POSIX) or {@code <binName>.cmd} (Windows) launching {@code
     * mainClass} with {@code classpathJars} on the classpath. Returns the launcher path.
     */
    public static Path install(Path binDir, Path javaHome, String binName, String mainClass, List<Path> classpathJars)
            throws IOException {
        Path launcher = LauncherName.resolveChild(binDir, launcherFileName(binName));
        Files.createDirectories(binDir);
        String script = renderScript(javaHome, mainClass, classpathJars);
        Files.writeString(
                launcher,
                script,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        markExecutable(launcher);
        return launcher;
    }

    /**
     * As {@link #install}, but launching a self-contained executable jar with {@code java -jar}
     * (assembly jars, Spring Boot jars — anything whose manifest carries its own entry point).
     */
    public static Path installJar(Path binDir, Path javaHome, String binName, Path jar) throws IOException {
        Path launcher = LauncherName.resolveChild(binDir, launcherFileName(binName));
        Files.createDirectories(binDir);
        String script = renderJarScript(javaHome, jar);
        Files.writeString(
                launcher,
                script,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        markExecutable(launcher);
        return launcher;
    }

    /** The launcher file name for {@code binName} on this platform. */
    public static String launcherFileName(String binName) {
        LauncherName.requireValid(binName);
        return binName + (Os.isWindows() ? ".cmd" : "");
    }

    /** Script content for a classpath launcher — what {@link #install} writes. */
    public static String renderScript(Path javaHome, String mainClass, List<Path> classpathJars) {
        return Os.isWindows()
                ? renderWindows(javaHome, mainClass, classpathJars)
                : renderPosix(javaHome, mainClass, classpathJars);
    }

    /**
     * Every launcher jk writes says who wrote it, on line two. For a human opening the file, not
     * for a delete check: these live in {@code <home>/bin}, which jk owns, so what may be removed
     * is settled by where the file is rather than by what it says.
     */
    private static final String POSIX_PREAMBLE =
            "#!/usr/bin/env bash\n# " + JkOwnership.GENERATED_BY + " — do not edit.\n";

    /** {@link #POSIX_PREAMBLE} for {@code cmd.exe}; ASCII only, CRLF. */
    private static final String WINDOWS_PREAMBLE =
            "@echo off\r\nREM " + JkOwnership.GENERATED_BY + " -- do not edit.\r\n";

    /** Script content for a self-contained-jar launcher — what {@link #installJar} writes. */
    public static String renderJarScript(Path javaHome, Path jar) {
        String java = JdkFingerprint.java(javaHome).toString();
        return Os.isWindows()
                ? WINDOWS_PREAMBLE + "\"" + java + "\" -jar \"" + jar.toAbsolutePath() + "\" %*\r\n"
                : POSIX_PREAMBLE + "exec "
                        + shellQuote(java)
                        + " -jar "
                        + shellQuote(jar.toAbsolutePath().toString())
                        + " \"$@\"\n";
    }

    private static String renderPosix(Path javaHome, String mainClass, List<Path> cp) {
        return POSIX_PREAMBLE + "exec "
                + shellQuote(JdkFingerprint.java(javaHome).toString())
                + " -cp "
                + shellQuote(Classpaths.join(cp))
                + " "
                + mainClass
                + " \"$@\"\n";
    }

    private static String renderWindows(Path javaHome, String mainClass, List<Path> cp) {
        return WINDOWS_PREAMBLE + "\""
                + JdkFingerprint.java(javaHome)
                + "\" -cp \""
                + Classpaths.join(cp)
                + "\" "
                + mainClass
                + " %*\r\n";
    }

    private static String shellQuote(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == '/' || c == ':')) {
                return "'" + value.replace("'", "'\\''") + "'";
            }
        }
        return value;
    }

    private static void markExecutable(Path file) {
        if (Os.isWindows()) return;
        try {
            Set<PosixFilePermission> perms = EnumSet.copyOf(Files.getPosixFilePermissions(file));
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystem.
        }
    }
}
