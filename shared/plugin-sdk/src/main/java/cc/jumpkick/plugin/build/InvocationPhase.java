// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

/**
 * Coarse outer timeline of a whole <em>engine request</em> (one {@code jk build} / {@code jk lock}
 * / …). Orthogonal to the per-module task graph and to {@link cc.jumpkick.run.BuildStage}.
 *
 * <pre>
 *   initialize → resolve → plan → toolchain → build → finalize
 * </pre>
 *
 * <p>While the request is in {@link #BUILD}, each module runs a {@code BuildPlan} whose tasks
 * carry a {@link cc.jumpkick.run.BuildStage} ({@code resolve}/{@code compile}/{@code test}/… for
 * UI fold and ETA). That in-plan {@code resolve} stage is module setup (lock classpath / JDK) —
 * not the same object as request-level {@link #RESOLVE} (lock/graph for the command).
 *
 * <p>Only {@linkplain #userVisible() user-visible} phases appear in CLI/Web progress trees by
 * default.
 *
 * @see cc.jumpkick.run.BuildStage
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
