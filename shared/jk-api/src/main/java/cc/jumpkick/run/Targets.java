// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

/**
 * Well-known {@link Target} names for CLI/engine verbs. The BuildPlan is the transitive closure of
 * tasks required to produce the chosen target.
 */
public final class Targets {

    private Targets() {}

    /** Default {@code jk build} terminal — package the main jar (or packager replacement). */
    public static final Target PACKAGE = Target.of(TaskNames.PACKAGE_JAR);

    /** {@code jk test} / build with tests — run unit tests. */
    public static final Target TEST = Target.of(TaskNames.RUN_TESTS);

    /** {@code jk image} terminal. */
    public static final Target IMAGE = Target.of(TaskNames.WRITE_IMAGE);

    /** {@code jk native} terminal. */
    public static final Target NATIVE = Target.of(TaskNames.NATIVE_IMAGE);

    /**
     * Map an engine/CLI kind string to a target. Unknown kinds default to {@link #PACKAGE}.
     */
    public static Target forKind(String kind) {
        if (kind == null || kind.isBlank()) return PACKAGE;
        String k = kind.toLowerCase(java.util.Locale.ROOT);
        if (k.equals("test") || k.startsWith("test:")) return TEST;
        if (k.equals("image") || k.startsWith("image:")) return IMAGE;
        if (k.equals("native") || k.startsWith("native:")) return NATIVE;
        if (k.equals("publish") || k.startsWith("publish:")) return Target.of("publish");
        if (k.equals("run") || k.startsWith("run:")) return PACKAGE;
        return PACKAGE;
    }
}
