// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

/**
 * The bounds on {@code java}, the {@code --release} a module compiles for. jk's floor is {@link
 * #FLOOR}, the oldest level it builds and tests for by default; a level below it is not refused,
 * since the toolchain JDK cross-compiles for it, but it is a module the build warns about once
 * ({@link #floorNote}) — the place for it is a module whose sources or dependencies still need an
 * API a later JDK removed. Below {@link #OLDEST} no current javac has a {@code --release}, so the
 * manifest refuses it.
 */
public final class JavaRelease {

    /** The lowest {@code java} jk builds for without a word; {@code jk import} raises a declared level to it. */
    public static final int FLOOR = 17;

    /** The oldest {@code --release} a current javac accepts. */
    public static final int OLDEST = 8;

    private JavaRelease() {}

    /** True for a declared level ({@code 0} is inherited or unset) below {@link #FLOOR}. */
    public static boolean belowFloor(int java) {
        return java > 0 && java < FLOOR;
    }

    /** The one sentence a module compiling below the floor gets, once per build. */
    public static String floorNote(int java) {
        return "java " + java + " is below jk's floor " + FLOOR
                + ": the toolchain JDK cross-compiles the module with --release "
                + java
                + " — keep it while the module's sources or dependencies need an API a later JDK removed, and write java = "
                + FLOOR + " once they do not";
    }
}
