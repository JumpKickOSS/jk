// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Invocation basename from argv[0] (Windows {@code .exe} stripped). {@link Jk#main} rewrites
 * {@code jkx} → {@code jk tool run}. Native image reads the real argv[0]; JVM falls back to
 * {@link ProcessHandle}.
 */
final class Argv0 {

    /**
     * Overrides the detected program name. A test seam, and what the JVM client's launcher
     * passes ({@code -Djk.argv0=$0}): on a JVM the process command is {@code java}, so a
     * {@code jkx} link to the launcher would otherwise never dispatch.
     */
    static final String OVERRIDE_PROPERTY = "jk.argv0";

    private Argv0() {}

    /** The invocation basename (e.g. {@code "jk"}, {@code "jkx"}), or null when undeterminable. */
    static @Nullable String programName() {
        String override = System.getProperty(OVERRIDE_PROPERTY);
        if (override != null) return baseName(override);
        try {
            if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
                return baseName(NativeArgv0.get());
            }
            return ProcessHandle.current().info().command().map(Argv0::baseName).orElse(null);
        } catch (RuntimeException | LinkageError e) {
            // Never let program-name sniffing break the CLI — no name, no dispatch.
            return null;
        }
    }

    /** Basename of {@code path}, lowercased for comparison, {@code .exe} stripped. */
    static @Nullable String baseName(String path) {
        if (path == null || path.isBlank()) return null;
        String name = path;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.toLowerCase(Locale.ROOT);
        if (name.endsWith(".exe")) name = name.substring(0, name.length() - ".exe".length());
        return name.isEmpty() ? null : name;
    }

    /** Holder so the GraalVM SDK class only loads inside the image (compileOnly on the JVM). */
    private static final class NativeArgv0 {
        static String get() {
            return org.graalvm.nativeimage.ProcessProperties.getArgumentVectorProgramName();
        }
    }
}
