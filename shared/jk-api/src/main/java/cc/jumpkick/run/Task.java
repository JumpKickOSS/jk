// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntSupplier;
import org.jspecify.annotations.Nullable;

/**
 * One unit of work inside a {@link BuildPlan}. Tasks declare dependencies by name and run when
 * their prerequisites finish.
 *
 * <p>Tasks are immutable after construction. Use {@link Task#builder} to assemble one. The one
 * thing a task remembers is its estimate: {@link #estimateTicks} and {@link #estimateWeight} ask
 * their suppliers once and answer from that thereafter, so the plan's pre-run estimate and the run
 * itself read the same numbers off one evaluation.
 *
 * <p>{@link #stage()} is the product bucket (UI fold / ETA); ordering is solely {@link #requires()}.
 */
public final class Task {

    private final String name;
    private final String label;
    private final TaskKind kind;
    private final List<String> requires;
    private final IntSupplier ticks;
    private final @Nullable IntSupplier weight; // null → weight tracks ticks

    /** A supplier's answer, or {@link #UNESTIMATED} until it has been asked. */
    private volatile int estimatedTicks = UNESTIMATED;

    private volatile int estimatedWeight = UNESTIMATED;
    private static final int UNESTIMATED = -1;
    private final boolean interpolated;
    /** Product taxonomy bucket; never null (defaults via {@link BuildStage#ofTaskName}). */
    private final BuildStage stage;

    private final Body body;

    Task(
            String name,
            @Nullable String label,
            TaskKind kind,
            List<String> requires,
            IntSupplier ticks,
            @Nullable IntSupplier weight,
            boolean interpolated,
            @Nullable BuildStage stage,
            Body body) {
        this.name = Objects.requireNonNull(name);
        this.label = label != null ? label : name;
        this.kind = Objects.requireNonNull(kind);
        this.requires = List.copyOf(requires);
        this.ticks = Objects.requireNonNull(ticks);
        this.weight = weight;
        this.interpolated = interpolated;
        this.stage = stage != null ? stage : BuildStage.ofTaskName(name);
        this.body = Objects.requireNonNull(body);
    }

    /**
     * Product stage for UI fold and ETA ({@link BuildStage#COMPILE}, …). Not a lifecycle slot —
     * ordering is solely {@link #requires()}.
     */
    public BuildStage stage() {
        return stage;
    }

    /**
     * Wire group label for UI folding ({@code compile}, {@code test}). Same as
     * {@link BuildStage#wireName() stage().wireName()} — except {@link BuildStage#OTHER}, which
     * returns empty: OTHER means "not a pipeline task", and folding half of jk's non-build
     * commands into one indistinguishable "Other" row erased their per-step rows.
     * An empty group makes the TUI fall back to the step key, one row per step.
     */
    public Optional<String> group() {
        return stage == BuildStage.OTHER ? Optional.empty() : Optional.of(stage.wireName());
    }

    public String name() {
        return name;
    }

    /** Display label shown in the TUI progress bar. Defaults to {@link #name()}. */
    public String label() {
        return label;
    }

    public TaskKind kind() {
        return kind;
    }

    public List<String> requires() {
        return requires;
    }

    /**
     * The step's internal unit count, asked of its supplier once. A supplier that throws leaves the
     * estimate untaken, so the next caller asks again.
     */
    public int estimateTicks() {
        int t = estimatedTicks;
        if (t == UNESTIMATED) {
            t = Math.max(0, ticks.getAsInt());
            estimatedTicks = t;
        }
        return t;
    }

    public boolean hasExplicitWeight() {
        return weight != null;
    }

    /** The step's bar share, asked of its supplier once; without an explicit weight it is the ticks. */
    public int estimateWeight() {
        if (weight == null) return estimateTicks();
        int w = estimatedWeight;
        if (w == UNESTIMATED) {
            w = Math.max(0, weight.getAsInt());
            estimatedWeight = w;
        }
        return w;
    }

    public boolean interpolated() {
        return interpolated;
    }

    public boolean async() {
        return kind != TaskKind.SYNC;
    }

    public void execute(TaskContext ctx) throws Exception {
        body.run(ctx);
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    @FunctionalInterface
    public interface Body {
        void run(TaskContext ctx) throws Exception;
    }

    public static final class Builder {
        private final String name;
        private @Nullable String label;
        private TaskKind kind = TaskKind.SYNC;
        private final List<String> requires = new ArrayList<>();
        private IntSupplier ticks = () -> 1;
        private @Nullable IntSupplier weight = null;
        private boolean interpolated = false;
        private @Nullable BuildStage stage = null;
        private boolean stageExplicit = false;
        private Body body = ctx -> {};

        Builder(String name) {
            this.name = Objects.requireNonNull(name);
        }

        public Builder label(@Nullable String label) {
            this.label = label;
            return this;
        }

        public Builder kind(TaskKind kind) {
            this.kind = kind;
            return this;
        }

        /** Run after the named task(s) finish successfully. */
        public Builder requires(String... names) {
            for (String n : names) requires.add(n);
            return this;
        }

        public Builder ticks(IntSupplier supplier) {
            this.ticks = supplier;
            return this;
        }

        public Builder ticks(int n) {
            this.ticks = () -> n;
            return this;
        }

        public Builder weight(IntSupplier supplier) {
            this.weight = supplier;
            return this;
        }

        public Builder weight(int n) {
            this.weight = () -> n;
            return this;
        }

        public Builder interpolated() {
            this.interpolated = true;
            return this;
        }

        /**
         * Product stage (UI fold / ETA). Preferred over {@link #group(String)}.
         */
        public Builder stage(BuildStage stage) {
            this.stage = Objects.requireNonNull(stage, "stage");
            this.stageExplicit = true;
            return this;
        }

        /**
         * Wire group label (e.g. {@code compile}). Prefer {@link #stage(BuildStage)}.
         *
         * <p>An unknown name is rejected rather than folded into {@link BuildStage#OTHER}: this
         * value is what the plan's stage-ordering check reads, so a typo would quietly move a task
         * to the one position that has no ordering of its own. A task that genuinely has no stage
         * says so with {@code stage(BuildStage.OTHER)}. The lenient parse stays on
         * {@link BuildStage#fromWire} for UI fold keys.
         */
        public Builder group(@Nullable String group) {
            if (group == null || group.isBlank()) {
                this.stage = null;
                this.stageExplicit = false;
                return this;
            }
            this.stage = BuildStage.fromWireExact(group)
                    .orElseThrow(() -> new IllegalArgumentException("unknown build stage `" + group
                            + "` — expected one of " + BuildStage.wireNames()
                            + ", or stage(BuildStage.OTHER) for a task with no stage"));
            this.stageExplicit = true;
            return this;
        }

        public Builder execute(Body body) {
            this.body = body;
            return this;
        }

        public Task build() {
            BuildStage resolved = stageExplicit && stage != null ? stage : BuildStage.ofTaskName(name);
            return new Task(name, label, kind, requires, ticks, weight, interpolated, resolved, body);
        }
    }
}
