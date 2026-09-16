// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.host.Os;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The properties Maven builds spell a platform classifier with, valued for one host. OpenJFX's
 * parent sets {@code javafx.platform} from an OS-activated profile ({@code linux}, {@code
 * linux-aarch64}, {@code mac}, {@code mac-aarch64}, {@code win}); os-maven-plugin exports {@code
 * os.detected.name}, {@code os.detected.arch} and {@code os.detected.classifier} ({@code
 * linux-x86_64}, {@code osx-aarch_64}, {@code windows-x86_64}, …). Neither exists in a POM jk
 * reads on its own, so an effective model fills them from the running host, and a POM that
 * defines one itself keeps its own value.
 */
public final class HostClassifiers {

    private HostClassifiers() {}

    /** The table for the running host; empty when its OS or architecture has no Maven spelling. */
    public static Map<String, String> properties() {
        return properties(Os.name(), System.getProperty("os.arch"));
    }

    /** The table for the named host; package-visible so the mapping is testable off the host. */
    static Map<String, String> properties(@Nullable String osName, @Nullable String osArch) {
        String os = detectedName(osName);
        String arch = detectedArch(osArch);
        if (os == null || arch == null) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        out.put("os.detected.name", os);
        out.put("os.detected.arch", arch);
        out.put("os.detected.classifier", os + "-" + arch);
        String javafx = javafxPlatform(os, arch);
        if (javafx != null) out.put("javafx.platform", javafx);
        return Map.copyOf(out);
    }

    /** True when {@code name} is one of the properties this table values. */
    public static boolean names(String name) {
        return switch (name) {
            case "javafx.platform", "os.detected.name", "os.detected.arch", "os.detected.classifier" -> true;
            default -> false;
        };
    }

    /** os-maven-plugin's OS word. */
    private static @Nullable String detectedName(@Nullable String osName) {
        if (Os.isLinux(osName)) return "linux";
        if (Os.isDarwin(osName)) return "osx";
        if (Os.isWindows(osName)) return "windows";
        return null;
    }

    /** os-maven-plugin's architecture word. */
    private static @Nullable String detectedArch(@Nullable String osArch) {
        if (osArch == null) return null;
        return switch (osArch.toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64", "x64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch_64";
            default -> null;
        };
    }

    /** OpenJFX's word for the pair; {@code null} for a pair OpenJFX does not publish. */
    private static @Nullable String javafxPlatform(String os, String arch) {
        boolean arm = "aarch_64".equals(arch);
        return switch (os) {
            case "linux" -> arm ? "linux-aarch64" : "linux";
            case "osx" -> arm ? "mac-aarch64" : "mac";
            case "windows" -> arm ? null : "win";
            default -> null;
        };
    }
}
