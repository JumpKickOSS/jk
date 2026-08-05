// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntSupplier;

/**
 * One unit of work inside a {@link BuildPlan}. Tasks declare dependencies by name and run when
 * their prerequisites finish.
 *
 * <p>Tasks are immutable after construction. Use {@link Task#builder} to assemble one.
 */
public final class Task {

    private final String name;
    private final String label;
    private final TaskKind kind;
    private final List<String> requires;
    private final IntSupplier ticks;
    private final IntSupplier weight; // null → weight tracks ticks
    private final boolean interpolated;
    /** Optional UI/group label (e.g. {@code compile}); not a lifecycle slot. */
    private final String group;
    private final Body body;

    Task(
            String name,
            String label,
            TaskKind kind,
            List<String> requires,
            IntSupplier ticks,
            IntSupplier weight,
            boolean interpolated,
            String group,
            Body body) {
        this.name = Objects.requireNonNull(name);
        this.label = label != null ? label : name;
        this.kind = Objects.requireNonNull(kind);
        this.requires = List.copyOf(requires);
        this.ticks = Objects.requireNonNull(ticks);
        this.weight = weight;
        this.interpolated = interpolated;
        this.group = (group == null || group.isBlank()) ? null : group;
        this.body = Objects.requireNonNull(body);
    }

    /**
     * Optional free-form group label for UI folding (e.g. {@code compile}, {@code test}). Not a
     * build lifecycle slot — ordering is solely {@link #requires()}.
     */
    public Optional<String> group() {
        return Optional.ofNullable(group);
    }

    /** @deprecated use {@link #group()}; kept for call-site migration */
    @Deprecated
    public Optional<String> phase() {
        return group();
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

    public int estimateTicks() {
        return Math.max(0, ticks.getAsInt());
    }

    public boolean hasExplicitWeight() {
        return weight != null;
    }

    public int estimateWeight() {
        return Math.max(0, weight != null ? weight.getAsInt() : ticks.getAsInt());
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
        private String label;
        private TaskKind kind = TaskKind.SYNC;
        private final List<String> requires = new ArrayList<>();
        private IntSupplier ticks = () -> 1;
        private IntSupplier weight = null;
        private boolean interpolated = false;
        private String group = null;
        private Body body = ctx -> {};

        Builder(String name) {
            this.name = Objects.requireNonNull(name);
        }

        public Builder label(String label) {
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

        /** Optional free-form UI group label (e.g. {@code compile}). Not a lifecycle slot. */
        public Builder group(String group) {
            this.group = group;
            return this;
        }

        /**
         * Same as {@link #group(String)}; retained so existing call sites and wire adapters keep
         * compiling while the vocabulary settles on "group".
         */
        public Builder phase(String group) {
            return group(group);
        }

        public Builder execute(Body body) {
            this.body = body;
            return this;
        }

        public Task build() {
            return new Task(name, label, kind, requires, ticks, weight, interpolated, group, body);
        }
    }
}
