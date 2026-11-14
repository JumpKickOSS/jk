// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * @param instance what tells this guest from every other guest of its kind on the machine, stable
 *     across restarts and reboots; empty for {@link Kind#NONE} or when nothing tells it apart
 */
public record Guest(Kind kind, String name, boolean interop, String instance) {

    /** What kind of guest. */
    public enum Kind {
        NONE,
        WSL,
        CONTAINER;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static final Guest NONE = new Guest(Kind.NONE, "", false, "");

    static final Path OSRELEASE = Path.of("/proc/sys/kernel/osrelease");
    static final Path INTEROP = Path.of("/proc/sys/fs/binfmt_misc/WSLInterop");
    static final Path INTEROP_LATE = Path.of("/proc/sys/fs/binfmt_misc/WSLInterop-late");
    static final Path OS_RELEASE = Path.of("/etc/os-release");
    static final Path MACHINE_ID = Path.of("/etc/machine-id");
    static final Path DBUS_MACHINE_ID = Path.of("/var/lib/dbus/machine-id");
    static final Path MOUNTINFO = Path.of("/proc/self/mountinfo");

    /** A container's id in the host path its {@code /etc/hostname} (or hosts, resolv.conf) is bound from. */
    private static final Pattern CONTAINER_ID = Pattern.compile("/(?:containers|overlay-containers)/([0-9a-f]{64})/");

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
            return new Guest(Kind.WSL, distro(read, processEnv), interop, wslInstance(read, processEnv));
        }
        String runtime = exists.test(Path.of("/.dockerenv"))
                ? "docker"
                : exists.test(Path.of("/run/.containerenv")) ? "podman" : processEnv.apply("container");
        if (runtime != null && !runtime.isBlank()) {
            return new Guest(Kind.CONTAINER, runtime.strip(), false, containerId(read.apply(MOUNTINFO)));
        }
        return NONE;
    }

    /**
     * What tells one WSL distro from another on the same Windows host: its machine id, which each
     * install generates, and the hardware id of the virtual disk its root is on, which each install
     * or import gets anew, so a distro cloned by export and import differs too. Both are files any
     * process can read, a daemon without {@code WSL_*} variables included. When neither reads, the
     * registered distro name, which only a process {@code wsl.exe} started carries.
     */
    private static String wslInstance(
            Function<Path, @Nullable String> read, Function<String, @Nullable String> processEnv) {
        String machine = firstLine(read.apply(MACHINE_ID));
        if (machine.isEmpty()) machine = firstLine(read.apply(DBUS_MACHINE_ID));
        String disk = rootDiskId(read);
        if (!machine.isEmpty() || !disk.isEmpty()) return "machine-id=" + machine + " disk=" + disk;
        String named = processEnv.apply("WSL_DISTRO_NAME");
        return named == null || named.isBlank() ? "" : "distro=" + named.strip();
    }

    /**
     * The SCSI identifier ({@code wwid}) of the disk the root filesystem is mounted from, found
     * through {@code /proc/self/mountinfo}'s device number; empty when the root is not a whole disk.
     * WSL attaches each distro's virtual disk under the identifier stored in it, whichever device
     * letter it gets.
     */
    private static String rootDiskId(Function<Path, @Nullable String> read) {
        String mountinfo = read.apply(MOUNTINFO);
        if (mountinfo == null) return "";
        String device = null;
        for (String line : mountinfo.split("\n")) {
            String[] f = line.split(" ");
            if (f.length > 4 && f[4].equals("/")) device = f[2];
        }
        if (device == null || !device.matches("\\d+:\\d+")) return "";
        return firstLine(read.apply(Path.of("/sys/dev/block", device, "device", "wwid")));
    }

    /** The container's id from the bind mounts of {@code mountinfo}; empty when none names it. */
    static String containerId(@Nullable String mountinfo) {
        if (mountinfo == null) return "";
        for (String line : mountinfo.split("\n")) {
            String[] f = line.split(" ");
            if (f.length < 5 || !f[4].startsWith("/etc/")) continue;
            Matcher m = CONTAINER_ID.matcher(f[3]);
            if (m.find()) return m.group(1);
        }
        return "";
    }

    private static String firstLine(@Nullable String text) {
        if (text == null) return "";
        String s = text.strip();
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl).strip();
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
