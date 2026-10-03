// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.node;

import cc.jumpkick.host.Os;
import cc.jumpkick.jdk.Platform;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A platform Node publishes an archive for, in its own naming: {@code os} is {@code linux}, {@code
 * darwin} or {@code win}, {@code arch} is {@code x64} or {@code arm64}, and a musl build carries a
 * {@code -musl} suffix ({@link #key()} is {@code linux-x64-musl}).
 */
public record NodePlatform(String os, String arch, boolean musl) {

    /** The platforms a lock records a digest for. */
    public static final List<NodePlatform> LOCKED = List.of(
            new NodePlatform("linux", "x64", false),
            new NodePlatform("linux", "arm64", false),
            new NodePlatform("darwin", "x64", false),
            new NodePlatform("darwin", "arm64", false),
            new NodePlatform("win", "x64", false),
            new NodePlatform("win", "arm64", false));

    public NodePlatform {
        Objects.requireNonNull(os, "os");
        Objects.requireNonNull(arch, "arch");
    }

    /** This machine. */
    public static NodePlatform host() {
        return of(Os.name(), System.getProperty("os.arch"), Platform.currentLibCType());
    }

    /** From {@code os.name}, {@code os.arch} and the C runtime ({@code musl} marks Alpine and kin). */
    static NodePlatform of(@Nullable String osName, @Nullable String osArch, @Nullable String libc) {
        String os = Os.isWindows(osName) ? "win" : Os.isDarwin(osName) ? "darwin" : "linux";
        String a = osArch == null ? "" : osArch.toLowerCase(Locale.ROOT);
        String arch = a.equals("aarch64") || a.equals("arm64") ? "arm64" : "x64";
        return new NodePlatform(os, arch, os.equals("linux") && "musl".equals(libc));
    }

    /** {@code linux-x64}, {@code linux-x64-musl}, {@code darwin-arm64}, {@code win-x64}. */
    public String key() {
        return os + "-" + arch + (musl ? "-musl" : "");
    }

    /** {@code zip} on Windows, {@code tar.gz} elsewhere: both published for every platform. */
    public String archiveType() {
        return os.equals("win") ? "zip" : "tar.gz";
    }

    /** The archive's file name for {@code version}, as {@code SHASUMS256.txt} lists it. */
    public String archiveName(String version) {
        return "node-v" + version + "-" + key() + "." + archiveType();
    }
}
