// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Host OS predicates, read from {@code os.name}. Lives in the {@code :host} leaf so the native
 * client, the engine and every plugin worker answer "which OS is this?" the same way — a worker
 * cannot reach {@code cc.jumpkick.jdk.HostPlatform}, which is what made the copies multiply.
 *
 * <p>This is the one place production code reads {@code os.name} (guard G16). Each predicate comes
 * in two forms: the no-arg one asks about the running host, and the {@code String} one classifies a
 * name a caller already holds — a test seam, or {@code HostPlatform}'s feed-vocabulary mapping. Both
 * forms share the test, so a host cannot be Windows to one and not the other.
 *
 * <p>The tests are lower-cased {@code contains}, not {@code startsWith}, and they are deliberately
 * asymmetric in length: {@code "windows"} rather than {@code "win"}, because {@code "win"} is a
 * substring of {@code Darwin} and a shortened test silently classifies macOS as Windows. Round 3
 * found that exact defect shipping in {@code BuildTool.binaryName()}, which handed a caller
 * {@code mvn.cmd} on any JVM reporting {@code os.name=Darwin}.
 */
public final class Os {
    private Os() {}

    /**
     * The system property the host is read from. Guard G20 reads the ban list out of this class:
     * every {@code *_PROPERTY} constant here is banned as a {@code System.getProperty} argument
     * everywhere else in production, so a second property this class starts owning is enforced the
     * same minute it is declared.
     */
    public static final String NAME_PROPERTY = "os.name";

    /**
     * The raw {@code os.name}, or {@code ""} when unset — the only read of the property in
     * production. Use it for a diagnostic message, or to hand the host to a pure function as a test
     * seam ({@code JkDirs}, {@code IntellijProbe}); ask a predicate for anything else.
     */
    public static String name() {
        return System.getProperty(NAME_PROPERTY, "");
    }

    /** True on Windows. */
    public static boolean isWindows() {
        return isWindows(name());
    }

    /** {@code osName} lowercased contains {@code "windows"} — not {@code "win"}, which matches Darwin. */
    public static boolean isWindows(@Nullable String osName) {
        return lower(osName).contains("windows");
    }

    /** True on macOS. */
    public static boolean isDarwin() {
        return isDarwin(name());
    }

    /**
     * {@code osName} lowercased contains {@code "mac"} or {@code "darwin"}. Both spellings are
     * needed: HotSpot reports {@code Mac OS X}, and other JVMs report the kernel name {@code Darwin}.
     */
    public static boolean isDarwin(@Nullable String osName) {
        String n = lower(osName);
        return n.contains("mac") || n.contains("darwin");
    }

    /** True on Linux. */
    public static boolean isLinux() {
        return isLinux(name());
    }

    /** {@code osName} lowercased contains {@code "linux"}. */
    public static boolean isLinux(@Nullable String osName) {
        return lower(osName).contains("linux");
    }

    private static String lower(@Nullable String osName) {
        return osName == null ? "" : osName.toLowerCase(Locale.ROOT);
    }
}
