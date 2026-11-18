// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.host.Os;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import org.jspecify.annotations.Nullable;

/**
 * A JDK as every key names it: {@code toolchain:jdk:<vendor>/<version>/<compiler>}, the vendor
 * ({@code IMPLEMENTOR}), the full runtime version with its build ({@code JAVA_RUNTIME_VERSION},
 * {@code 25.0.4.1+1-LTS}) and the content of its {@code jdk.compiler} module ({@link
 * CompilerIdentity}). That is what makes two JDKs the same compiler and the same runtime, so the
 * javac, kotlinc, groovyc, KSP, javadoc, script, plugin-step, packager and run-tests keys all
 * carry this one token. It never holds the {@code release} file's {@code OS_NAME}, {@code OS_ARCH},
 * {@code LIBC} or {@code BUILD_INFO} lines, nor the install path: two machines on the same JDK build
 * share every one of those keys, and a patch, a rebuild or another vendor does not.
 *
 * <p>The platform the JDK is for is {@link #target}, {@code <os>-<arch>[-<libc>]}. Only steps whose
 * outputs are bound to a platform carry it as a key token of its own (jlink, native-image); a test
 * run records its host in the record and does not key it.
 */
public final class JdkToolchain {

    /** The toolchain kind of a JDK in {@code toolchain:<kind>:<identity>}. */
    public static final String PREFIX = "toolchain:jdk:";

    private JdkToolchain() {}

    /**
     * The toolchain token of {@code javaHome}. A home with no readable release file is named by the
     * content of its module image ({@code modules:<sha256>}), else of its whole tree ({@code
     * home:<digest>}); a home with no module image leaves the compiler out. A null home is {@code
     * toolchain:jdk:none}, which no real home produces.
     */
    public static String token(@Nullable Path javaHome) throws IOException {
        if (javaHome == null) return PREFIX + "none";
        Path abs = javaHome.toAbsolutePath().normalize();
        Path release = abs.resolve("release");
        if (!Files.isRegularFile(release)) return PREFIX + installDirToken(abs);
        Properties props = read(release);
        String vendor = slug(value(props, "IMPLEMENTOR"));
        String version = value(props, "JAVA_RUNTIME_VERSION");
        if (version == null) version = value(props, "FULL_VERSION");
        if (version == null) version = value(props, "JAVA_VERSION");
        String compiler;
        try {
            compiler = CompilerIdentity.digest(abs, FileHashMemo.contentHash(release));
        } catch (IOException unreadable) {
            compiler = null;
        }
        if (version == null && compiler == null) return PREFIX + "release:" + FileHashMemo.contentHash(release);
        return PREFIX
                + (vendor == null ? "unknown" : vendor)
                + "/" + (version == null ? "unknown" : version.replace(' ', '_'))
                + (compiler == null ? "" : "/" + compiler.substring(0, 16));
    }

    /**
     * The platform {@code javaHome} is built for, {@code <os>-<arch>} with {@code -<libc>} when its
     * release file names one ({@code linux-x86_64-gnu}, {@code linux-aarch64-musl}, {@code
     * macos-aarch64}, {@code windows-x86_64}); this host's {@link CachePlatform} when it names no OS
     * or architecture. What a platform-bound step's key carries beside {@link #token}, and what its
     * record names as its {@code TARGET}.
     */
    public static String target(@Nullable Path javaHome) throws IOException {
        Path release =
                javaHome == null ? null : javaHome.toAbsolutePath().normalize().resolve("release");
        if (release == null || !Files.isRegularFile(release)) return CachePlatform.current();
        Properties props = read(release);
        String os = os(value(props, "OS_NAME"));
        String arch = arch(value(props, "OS_ARCH"));
        if (os == null || arch == null) return CachePlatform.current();
        String libc = value(props, "LIBC");
        boolean namesLibc = libc != null && !libc.equalsIgnoreCase("default");
        return os + "-" + arch + (namesLibc ? "-" + slug(libc) : "");
    }

    /**
     * A toolchain token as {@code jk explain} reads it: {@code eclipse-adoptium 25.0.4.1+1-LTS}, the
     * token itself when it is not one this class wrote.
     */
    public static String describe(@Nullable String token) {
        if (token == null || !token.startsWith(PREFIX)) return String.valueOf(token);
        String[] parts = token.substring(PREFIX.length()).split("/", -1);
        return parts.length >= 2 ? parts[0] + " " + parts[1] : token.substring(PREFIX.length());
    }

    static @Nullable String os(@Nullable String osName) {
        if (osName == null) return null;
        if (Os.isLinux(osName)) return "linux";
        if (Os.isDarwin(osName) || osName.toLowerCase(Locale.ROOT).startsWith("mac")) return "macos";
        if (Os.isWindows(osName)) return "windows";
        return slug(osName);
    }

    static @Nullable String arch(@Nullable String osArch) {
        if (osArch == null) return null;
        return switch (osArch.toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64", "x64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> slug(osArch);
        };
    }

    private static Properties read(Path release) throws IOException {
        Properties props = new Properties();
        try (Reader in = Files.newBufferedReader(release, StandardCharsets.UTF_8)) {
            props.load(in);
        }
        return props;
    }

    private static @Nullable String value(Properties props, String name) {
        String raw = props.getProperty(name);
        if (raw == null) return null;
        String v = raw.strip();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\""))
            v = v.substring(1, v.length() - 1).strip();
        return v.isEmpty() ? null : v;
    }

    /** {@code Eclipse Adoptium} as {@code eclipse-adoptium}: lower case, one word. */
    private static @Nullable String slug(@Nullable String s) {
        if (s == null) return null;
        String out =
                s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.+_-]+", "-").replaceAll("^-+|-+$", "");
        return out.isEmpty() ? null : out;
    }

    /**
     * A release-less home by the content of its {@code lib/modules} image, which holds every
     * platform class the JDK compiles against (through the hash memo, so it is read once per
     * identity). A home with no image either is keyed by its whole tree: nothing cheaper names its
     * content.
     */
    private static String installDirToken(Path home) throws IOException {
        Path modules = home.resolve("lib").resolve("modules");
        if (Files.isRegularFile(modules)) return "modules:" + FileHashMemo.contentHash(modules);
        return "home:" + ClasspathFingerprint.entry(home);
    }
}
