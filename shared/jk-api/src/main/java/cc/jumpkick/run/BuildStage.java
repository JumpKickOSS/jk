// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import java.util.Locale;
import java.util.Optional;

/**
 * Fixed <em>module BuildPlan</em> buckets for UI fold, ETA rollup, and (future) pre/post hooks.
 *
 * <p>Orthogonal to request-level {@code InvocationPhase} ({@code resolve → plan → build → …}).
 * Stages live <strong>inside</strong> a module plan that runs while the request is typically in
 * the build invocation phase.
 *
 * <p>Task <em>ordering</em> remains the DAG ({@link Task#requires()}); a stage is product
 * taxonomy, not a second scheduler. Prefer {@link Task.Builder#stage(BuildStage)} over free-form
 * {@link Task.Builder#group(String)}.
 *
 * <p>Wire / UI spelling is the lowercase name ({@code compile} → label {@code Compile}).
 */
public enum BuildStage {
    /**
     * Early module setup: parse, consume lock / classpath, ensure JDK. Not the same object as
     * request-level InvocationPhase.RESOLVE (lock/graph for the whole command).
     */
    RESOLVE("resolve"),
    /** Codegen / source generation before main language compile. */
    GENERATE("generate"),
    /** Main compile (java/kotlin/groovy), resources, assemble, post-compile logic. */
    COMPILE("compile"),
    /** Test compile and run-tests. */
    TEST("test"),
    /** Jar / assembly packaging. */
    PACKAGE("package"),
    /**
     * Opt-in {@code jk train}: observe a full-app run under a recorder. Not part of default
     * {@code jk build}.
     */
    TRAIN("train"),
    /** Graal native-image and related. */
    NATIVE("native"),
    /** OCI / image packaging. */
    IMAGE("image"),
    /** Escape hatch: unknown or plugin-private; UI parks under Other. */
    OTHER("other");

    private final String wire;

    BuildStage(String wire) {
        this.wire = wire;
    }

    /** Wire / JSON / UI fold key ({@code compile}, {@code test}, …). */
    public String wireName() {
        return wire;
    }

    /** Display label ({@code Compile}, {@code Test}, …). */
    public String displayName() {
        if (wire.isEmpty()) return "";
        return Character.toUpperCase(wire.charAt(0)) + wire.substring(1);
    }

    /**
     * Pipeline order for inter-stage {@code requires} checks. Lower runs earlier.
     *
     * <p>A total order, and it is meant literally: {@code native-image} consumes what packaging
     * produced, and an OCI image consumes either the jar or the binary. {@link #NATIVE} and
     * {@link #IMAGE} are later stages, not siblings of {@link #PACKAGE} — a join over them belongs
     * at the latest stage it joins.
     *
     * <p>{@link #OTHER} returns {@code -1}: it has no position. {@code BuildPlan} derives one for
     * it from the tasks it waits on rather than exempting it.
     */
    public int pipelineOrder() {
        return switch (this) {
            case RESOLVE -> 0;
            case GENERATE -> 1;
            case COMPILE -> 2;
            case TEST -> 3;
            case PACKAGE -> 4;
            case TRAIN -> 5;
            case NATIVE -> 6;
            case IMAGE -> 7;
            case OTHER -> -1;
        };
    }

    /**
     * True when a task in {@code this} stage may {@code require} a task in {@code upstream}.
     * Same stage or earlier is allowed; later stages are not (no backward edges).
     *
     * <p>Pairwise only, and {@link #OTHER} is a wildcard on both sides because a single pair
     * carries no information about where an unpositioned task sits. The graph-wide invariant is
     * enforced by {@code BuildPlan}, which derives OTHER's position from its upstreams; do not
     * read a {@code true} here as "this edge is legal in context".
     */
    public boolean mayRequire(BuildStage upstream) {
        if (this == OTHER || upstream == OTHER) return true;
        return upstream.pipelineOrder() <= this.pipelineOrder();
    }

    /**
     * Parse a wire group/stage name. Unknown non-blank strings map to {@link #OTHER} so plugins
     * never invent a first-class strip slot by accident.
     */
    public static BuildStage fromWire(String name) {
        if (name == null || name.isBlank()) return OTHER;
        String key = name.trim().toLowerCase(Locale.ROOT);
        for (BuildStage s : values()) {
            if (s.wire.equals(key)) return s;
        }
        return OTHER;
    }

    /**
     * Strict parse for surfaces where a typo must not silently become {@link #OTHER} — a
     * plugin-declared stage, say. {@link #fromWire} stays lenient for UI fold keys.
     */
    public static Optional<BuildStage> fromWireExact(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        String key = name.trim().toLowerCase(Locale.ROOT);
        for (BuildStage s : values()) {
            if (s.wire.equals(key)) return Optional.of(s);
        }
        return Optional.empty();
    }

    /** Every wire spelling, comma-joined — for "expected one of …" diagnostics. */
    public static String wireNames() {
        StringBuilder sb = new StringBuilder();
        for (BuildStage s : values()) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append('`').append(s.wire).append('`');
        }
        return sb.toString();
    }

    /**
     * Infer stage from a task name when the builder did not set one. Single source of truth for
     * ETA and fallback UI fold — same mapping historically in {@link TaskPhases}.
     */
    public static BuildStage ofTaskName(String taskName) {
        if (taskName == null || taskName.isBlank()) return OTHER;
        String t = taskName;
        if (t.startsWith("plugin-")) {
            if (t.contains("image") || t.contains("oci") || t.contains("jib")) return IMAGE;
            if (t.contains("native")) return NATIVE;
            if (t.contains("test")) return TEST;
            if (t.contains("package") || t.contains("boot") || t.contains("shadow") || t.contains("assembly"))
                return PACKAGE;
            // Mid-graph codegen plugins (protoc/ksp) stay in COMPILE — same as historical TaskPhases —
            // until plans use stage(GENERATE) explicitly for pre-compile-only work.
            return COMPILE;
        }
        return switch (t) {
            case "parse-build", "resolve-deps", "ensure-jdk", "sync-deps", "read-lock", "parse-lock" -> RESOLVE;
            case "build-logic-before-compile" -> GENERATE;
            case "compile-java",
                    "compile-kotlin",
                    "compile-groovy",
                    "copy-resources",
                    "assemble-classes",
                    "write-stamp",
                    "write-stamp-kotlin",
                    "write-stamp-groovy",
                    "build-logic-after-compile",
                    "ksp",
                    "protoc" -> COMPILE;
            // GENERATE reserved for explicit stage / future before-compile codegen tasks
            case "compile-test", "run-tests" -> TEST;
            case "package-jar", "package-assembly", "embed-sha", "build-logic-before-package" -> PACKAGE;
            case "train", "train-reachability" -> TRAIN;
            case "native-image", "native-shared" -> NATIVE;
            case "write-image", "image-plan" -> IMAGE;
            default -> {
                if (t.startsWith("compile")) yield COMPILE;
                if (t.startsWith("write-stamp")) yield COMPILE;
                if (t.startsWith("package")) yield PACKAGE;
                if (t.startsWith("train")) yield TRAIN;
                if (t.startsWith("native")) yield NATIVE;
                if (t.startsWith("image") || t.startsWith("write-image")) yield IMAGE;
                if (t.contains("generat") || t.contains("codegen")) yield GENERATE;
                if (t.contains("test")) yield TEST;
                yield OTHER;
            }
        };
    }
}
