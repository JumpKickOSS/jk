// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import cc.jumpkick.host.Os;
import java.util.Locale;

/**
 * The host's OS / architecture in the vocabulary the JetBrains JDK feed uses ({@code linux} /
 * {@code macOS} / {@code windows}; {@code x86_64} / {@code aarch64}). Separate from {@link
 * Platform}, which encodes foojay vocabulary for the dormant {@code DiscoClient} path.
 *
 * <p>Hosts the feed doesn't cover (AIX, FreeBSD, 32-bit x86, arm32, Alpine/musl) return {@link
 * #UNSUPPORTED} so callers can surface a clean "set JAVA_HOME explicitly" message instead of
 * silently downloading the wrong binary.
 *
 * <p>This is a <em>naming</em> table, not a host predicate: it answers "what does the feed call
 * this machine?". "Is this Windows?" is {@link Os#isWindows()}, on the {@code :host} leaf every
 * module can reach — {@code :core}, the workers and most plugins cannot see this class, and that
 * unreachability is why fourteen private copies of the predicate existed. {@link #mapOs} classifies
 * through {@code Os} so the two cannot drift.
 */
public final class HostPlatform {

    public static final String UNSUPPORTED = "unsupported";

    private HostPlatform() {}

    public static String currentOs() {
        return mapOs(Os.name());
    }

    public static String currentArch() {
        return mapArch(System.getProperty("os.arch"));
    }

    public static boolean supported() {
        return !UNSUPPORTED.equals(currentOs()) && !UNSUPPORTED.equals(currentArch());
    }

    /** Friendly display name for an OS (feed vocabulary → user-facing). */
    public static String displayOs(String os) {
        return switch (os) {
            case "linux" -> "Linux";
            case "macOS" -> "macOS";
            case "windows" -> "Windows";
            default -> os;
        };
    }

    /** Friendly display name for an architecture. */
    public static String displayArch(String arch) {
        return switch (arch) {
            case "x86_64" -> "64-bit";
            case "aarch64" -> "ARM 64-bit";
            default -> arch;
        };
    }

    static String mapOs(String osName) {
        if (osName == null || osName.isBlank()) return UNSUPPORTED;
        if (Os.isLinux(osName)) return "linux";
        if (Os.isDarwin(osName)) return "macOS";
        if (Os.isWindows(osName)) return "windows";
        return UNSUPPORTED;
    }

    static String mapArch(String osArch) {
        if (osArch == null) return UNSUPPORTED;
        return switch (osArch.toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64", "x64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> UNSUPPORTED;
        };
    }
}
