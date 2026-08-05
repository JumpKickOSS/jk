// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

/**
 * Coarse outer stages of a whole engine request. Orthogonal to the per-module task graph
 * ({@link TaskSpec} / BuildPlan). Only {@linkplain #userVisible() user-visible} phases appear in
 * CLI/Web progress trees by default.
 *
 * <pre>
 *   initialize → resolve → plan → toolchain → build → finalize
 * </pre>
 *
 * @see docs/features/build-plan.md
 */
public enum InvocationPhase {
    INITIALIZE(false),
    RESOLVE(true),
    PLAN(true),
    TOOLCHAIN(false),
    BUILD(true),
    FINALIZE(false);

    private final boolean userVisible;

    InvocationPhase(boolean userVisible) {
        this.userVisible = userVisible;
    }

    /** When true, CLI/Web may show this phase in the progress tree. */
    public boolean userVisible() {
        return userVisible;
    }

    /** Wire / JSON spelling ({@code resolve}, {@code plan}, {@code build}, …). */
    public String wireName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static InvocationPhase fromWire(String name) {
        return valueOf(name.toUpperCase(java.util.Locale.ROOT).replace('-', '_'));
    }

    public static InvocationPhase fromWireOrNull(String name) {
        return (name == null || name.isEmpty()) ? null : fromWire(name);
    }
}
