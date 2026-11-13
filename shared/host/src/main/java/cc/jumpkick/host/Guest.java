// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Whether this process runs as a guest of another machine's loopback: a WSL distro, whose
 * {@code 127.0.0.1} binds Windows forwards onto its own, or a container. Read from the kernel and
 * the filesystem, never from {@code WSL_*} variables, which a daemon or a service often lacks.
 *
 * @param kind what kind of guest this is, or {@link Kind#NONE}
 * @param name the distro or container runtime, empty when unknown
 * @param interop for WSL, whether Windows programs can run from here ({@code WSLInterop}); false
 *     otherwise
 */
public record Guest(Kind kind, String name, boolean interop) {

    /** What kind of guest. */
    public enum Kind {
        NONE,
        WSL,
        CONTAINER;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static final Guest NONE = new Guest(Kind.NONE, "", false);

    static final Path OSRELEASE = Path.of("/proc/sys/kernel/osrelease");
    static final Path INTEROP = Path.of("/proc/sys/fs/binfmt_misc/WSLInterop");
    static final Path INTEROP_LATE = Path.of("/proc/sys/fs/binfmt_misc/WSLInterop-late");
    static final Path OS_RELEASE = Path.of("/etc/os-release");

    public boolean guest() {
        return kind != Kind.NONE;
    }

    /** This process's answer; {@link #NONE} off Linux. */
    public static Guest detect() {
        if (!Os.isLinux()) return NONE;
        return detect(Files::exists, Guest::read, ProcessEnv::get);
    }

    /** As {@link #detect()}, over the given file checks, file reads and process environment. */
    static Guest detect(
            Predicate<Path> exists,
            Function<Path, @Nullable String> read,
            Function<String, @Nullable String> processEnv) {
        String osrelease = read.apply(OSRELEASE);
        if (osrelease != null && osrelease.toLowerCase(Locale.ROOT).contains("microsoft")) {
            boolean interop = exists.test(INTEROP) || exists.test(INTEROP_LATE);
            return new Guest(Kind.WSL, distro(read, processEnv), interop);
        }
        if (exists.test(Path.of("/.dockerenv"))) return new Guest(Kind.CONTAINER, "docker", false);
        if (exists.test(Path.of("/run/.containerenv"))) return new Guest(Kind.CONTAINER, "podman", false);
        String container = processEnv.apply("container");
        if (container != null && !container.isBlank()) return new Guest(Kind.CONTAINER, container.strip(), false);
        return NONE;
    }

    /** The distro's name: {@code WSL_DISTRO_NAME} when set, else {@code /etc/os-release}'s {@code NAME}. */
    private static String distro(Function<Path, @Nullable String> read, Function<String, @Nullable String> processEnv) {
        String named = processEnv.apply("WSL_DISTRO_NAME");
        if (named != null && !named.isBlank()) return named.strip();
        String osRelease = read.apply(OS_RELEASE);
        if (osRelease == null) return "";
        for (String line : osRelease.split("\n")) {
            if (line.startsWith("NAME="))
                return line.substring(5).replace("\"", "").strip();
        }
        return "";
    }

    private static @Nullable String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }
}
