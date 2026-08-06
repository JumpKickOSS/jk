// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

/**
 * Closed phase taxonomy for ETA rollup. Tasks map into a phase; a module ETA is the sum of its
 * phase ETAs, and each phase ETA is the sum of that phase's in-plan dirty tasks.
 *
 * <p>Phases are a calibration dimension only — not a lifecycle scheduler.
 */
public final class TaskPhases {

    public static final String RESOLVE = "resolve";
    public static final String COMPILE = "compile";
    public static final String TEST = "test";
    public static final String PACKAGE = "package";
    public static final String NATIVE = "native";
    public static final String IMAGE = "image";
    public static final String OTHER = "other";

    private TaskPhases() {}

    /** Phase key for a task name (empty/null → {@link #OTHER}). */
    public static String of(String taskName) {
        if (taskName == null || taskName.isBlank()) return OTHER;
        String t = taskName;
        if (t.startsWith("plugin-")) {
            // Plugin tasks: mid-graph class/source contributions → compile; packagers often package.
            if (t.contains("image") || t.contains("oci") || t.contains("jib")) return IMAGE;
            if (t.contains("native")) return NATIVE;
            if (t.contains("test")) return TEST;
            if (t.contains("package") || t.contains("boot") || t.contains("shadow") || t.contains("assembly"))
                return PACKAGE;
            return COMPILE;
        }
        return switch (t) {
            case "parse-build", "resolve-deps", "ensure-jdk", "sync-deps" -> RESOLVE;
            case "compile-java",
                    "compile-kotlin",
                    "compile-groovy",
                    "copy-resources",
                    "assemble-classes",
                    "write-stamp",
                    "write-stamp-kotlin",
                    "write-stamp-groovy",
                    "build-logic-after-compile",
                    "build-logic-before-package",
                    "ksp",
                    "protoc" -> COMPILE;
            case "compile-test", "run-tests" -> TEST;
            case "package-jar", "package-assembly", "embed-sha" -> PACKAGE;
            case "native-image", "native-shared" -> NATIVE;
            case "write-image", "image-plan" -> IMAGE;
            default -> {
                if (t.startsWith("compile")) yield COMPILE;
                if (t.startsWith("package") || t.startsWith("write-stamp")) yield PACKAGE;
                if (t.startsWith("native")) yield NATIVE;
                if (t.startsWith("image") || t.startsWith("write-image")) yield IMAGE;
                if (t.contains("test")) yield TEST;
                yield OTHER;
            }
        };
    }
}
