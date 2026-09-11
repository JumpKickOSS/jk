// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver.pubgrub;

import cc.jumpkick.resolver.Versions;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * A set of version strings, represented as a disjoint union of ranges. Closed under intersection,
 * union, and complement — the algebra PubGrub needs to reason about positive and negative terms.
 *
 * <p>Total order on versions comes from {@link Versions#compare(String, String)} (Maven {@code
 * ComparableVersion} semantics).
 *
 * <p>Invariant: every {@link Union} is a <em>canonical</em> form — two or more {@link Range}s,
 * sorted by lower bound, pairwise disjoint and non-adjacent (overlapping or touching ranges are
 * always merged). Nested unions never appear; algebra operations re-canonicalize through {@link
 * Union#of}.
 */
public sealed interface VersionSet permits VersionSet.Empty, VersionSet.All, VersionSet.Range, VersionSet.Union {

    /** The empty set. */
    Empty EMPTY = Empty.INSTANCE;

    /** The universe. */
    All ALL = All.INSTANCE;

    boolean contains(String version);

    VersionSet intersect(VersionSet other);

    /** Union of two sets, producing a {@link Union} when ranges don't merge. */
    VersionSet union(VersionSet other);

    /** Set complement (with respect to the universe). */
    VersionSet complement();

    boolean isEmpty();

    /** True iff this is the universe set. */
    default boolean isAll() {
        return false;
    }

    /**
     * When this set is a single concrete version (closed point range {@code [v,v]}), that version;
     * otherwise empty. Used by the solver to seed a singleton {@link VersionUniverse} without
     * fetching maven-metadata.
     */
    default Optional<String> asExactSingleton() {
        return Optional.empty();
    }

    /** True iff this set is a (non-strict) subset of {@code other}. */
    default boolean subsetOf(VersionSet other) {
        // Cheap structural short-circuits before the full a ∩ ¬b = ∅ check.
        if (this.isEmpty() || other.isAll()) return true;
        if (other.isEmpty()) return false;
        if (this.isAll()) return false;
        // a ⊆ b iff a ∩ ¬b = ∅
        return intersect(other.complement()).isEmpty();
    }

    // --- constructors ------------------------------------------------------

    static VersionSet exact(String version) {
        Objects.requireNonNull(version, "version");
        return new Range(version, true, version, true);
    }

    static VersionSet atLeast(String min, boolean inclusive) {
        Objects.requireNonNull(min, "min");
        return new Range(min, inclusive, null, false);
    }

    static VersionSet lessThan(String max, boolean inclusive) {
        Objects.requireNonNull(max, "max");
        return new Range(null, false, max, inclusive);
    }

    static VersionSet between(String min, boolean minInclusive, String max, boolean maxInclusive) {
        return new Range(min, minInclusive, max, maxInclusive);
    }

    // --- variants ----------------------------------------------------------

    final class Empty implements VersionSet {
        static final Empty INSTANCE = new Empty();

        private Empty() {}

        @Override
        public boolean contains(String version) {
            return false;
        }

        @Override
        public VersionSet intersect(VersionSet other) {
            return this;
        }

        @Override
        public VersionSet union(VersionSet other) {
            return other;
        }

        @Override
        public VersionSet complement() {
            return ALL;
        }

        @Override
        public boolean isEmpty() {
            return true;
        }

        @Override
        public String toString() {
            return "∅";
        }
    }

    final class All implements VersionSet {
        static final All INSTANCE = new All();

        private All() {}

        @Override
        public boolean contains(String version) {
            return true;
        }

        @Override
        public VersionSet intersect(VersionSet other) {
            return other;
        }

        @Override
        public VersionSet union(VersionSet other) {
            return this;
        }

        @Override
        public VersionSet complement() {
            return EMPTY;
        }

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public boolean isAll() {
            return true;
        }

        @Override
        public String toString() {
            return "*";
        }
    }

    /**
     * A single range. {@code null} bounds mean unbounded on that side. Inverted (empty) bounds are
     * rejected at construction; callers that need the empty set use {@link #EMPTY}.
     */
    record Range(
            @Nullable String min,
            boolean minInclusive,
            @Nullable String max,
            boolean maxInclusive) implements VersionSet {

        public Range {
            // Reject inverted bounds at construction time so callers don't
            // have to re-check; ALL / EMPTY are the singletons for
            // unbounded / empty.
            if (min != null && max != null) {
                int cmp = Versions.compare(min, max);
                if (cmp > 0 || (cmp == 0 && (!minInclusive || !maxInclusive))) {
                    throw new IllegalArgumentException("empty range: "
                            + bracket(minInclusive, '[', '(')
                            + min
                            + ","
                            + max
                            + bracket(maxInclusive, ']', ')'));
                }
            }
        }

        @Override
        public boolean contains(String version) {
            if (min != null) {
                int cmp = Versions.compare(version, min);
                if (cmp < 0 || (cmp == 0 && !minInclusive)) return false;
            }
            if (max != null) {
                int cmp = Versions.compare(version, max);
                if (cmp > 0 || (cmp == 0 && !maxInclusive)) return false;
            }
            return true;
        }

        @Override
        public Optional<String> asExactSingleton() {
            if (min != null && max != null && minInclusive && maxInclusive && min.equals(max)) {
                return Optional.of(min);
            }
            return Optional.empty();
        }

        @Override
        public VersionSet intersect(VersionSet other) {
            return switch (other) {
                case Empty ignored -> EMPTY;
                case All ignored -> this;
                case Range r -> intersectRange(r);
                case Union u -> u.intersect(this);
            };
        }

        private VersionSet intersectRange(Range other) {
            String lo;
            boolean loInc;
            if (min == null) {
                lo = other.min;
                loInc = other.minInclusive;
            } else if (other.min == null) {
                lo = min;
                loInc = minInclusive;
            } else {
                int cmp = Versions.compare(min, other.min);
                if (cmp > 0) {
                    lo = min;
                    loInc = minInclusive;
                } else if (cmp < 0) {
                    lo = other.min;
                    loInc = other.minInclusive;
                } else {
                    lo = min;
                    loInc = minInclusive && other.minInclusive;
                }
            }
            String hi;
            boolean hiInc;
            if (max == null) {
                hi = other.max;
                hiInc = other.maxInclusive;
            } else if (other.max == null) {
                hi = max;
                hiInc = maxInclusive;
            } else {
                int cmp = Versions.compare(max, other.max);
                if (cmp < 0) {
                    hi = max;
                    hiInc = maxInclusive;
                } else if (cmp > 0) {
                    hi = other.max;
                    hiInc = other.maxInclusive;
                } else {
                    hi = max;
                    hiInc = maxInclusive && other.maxInclusive;
                }
            }
            // Empty if lo > hi, or lo == hi with either side exclusive.
            if (lo != null && hi != null) {
                int cmp = Versions.compare(lo, hi);
                if (cmp > 0) return EMPTY;
                if (cmp == 0 && (!loInc || !hiInc)) return EMPTY;
            }
            return new Range(lo, loInc, hi, hiInc);
        }

        @Override
        public VersionSet union(VersionSet other) {
            return switch (other) {
                case Empty ignored -> this;
                case All ignored -> ALL;
                case Range r -> unionRange(r);
                case Union u -> u.union(this);
            };
        }

        private VersionSet unionRange(Range other) {
            // If they overlap or touch, merge into one range.
            if (overlapsOrTouches(other)) {
                return mergeTouching(this, other);
            }
            // Disjoint: canonical Union (sorted, two parts).
            return compareLow(this, other) < 0 ? Union.of(List.of(this, other)) : Union.of(List.of(other, this));
        }

        /** Merge two ranges that {@link #overlapsOrTouches} already established. */
        static Range mergeTouching(Range a, Range b) {
            String lo;
            boolean loInc;
            if (a.min == null || b.min == null) {
                lo = null;
                loInc = false;
            } else {
                int cmp = Versions.compare(a.min, b.min);
                if (cmp < 0) {
                    lo = a.min;
                    loInc = a.minInclusive;
                } else if (cmp > 0) {
                    lo = b.min;
                    loInc = b.minInclusive;
                } else {
                    lo = a.min;
                    loInc = a.minInclusive || b.minInclusive;
                }
            }
            String hi;
            boolean hiInc;
            if (a.max == null || b.max == null) {
                hi = null;
                hiInc = false;
            } else {
                int cmp = Versions.compare(a.max, b.max);
                if (cmp > 0) {
                    hi = a.max;
                    hiInc = a.maxInclusive;
                } else if (cmp < 0) {
                    hi = b.max;
                    hiInc = b.maxInclusive;
                } else {
                    hi = a.max;
                    hiInc = a.maxInclusive || b.maxInclusive;
                }
            }
            return new Range(lo, loInc, hi, hiInc);
        }

        @Override
        public VersionSet complement() {
            // Universe minus [min..max].
            List<Range> parts = new ArrayList<>(2);
            if (min != null) {
                parts.add(new Range(null, false, min, !minInclusive));
            }
            if (max != null) {
                parts.add(new Range(max, !maxInclusive, null, false));
            }
            if (parts.isEmpty()) return EMPTY; // we were ALL
            if (parts.size() == 1) return parts.getFirst();
            return Union.of(parts);
        }

        @Override
        public boolean isEmpty() {
            return false;
        }

        /**
         * True when the two ranges share any version, or abut at an endpoint that at least one side
         * includes (so the union is a single contiguous interval over the version order).
         */
        boolean overlapsOrTouches(Range other) {
            // Overlaps: intersection is non-empty
            if (!intersectRange(other).isEmpty()) return true;
            // Touches: this.max == other.min (and at least one is inclusive)
            if (max != null
                    && other.min != null
                    && Versions.compare(max, other.min) == 0
                    && (maxInclusive || other.minInclusive)) {
                return true;
            }
            return min != null
                    && other.max != null
                    && Versions.compare(min, other.max) == 0
                    && (minInclusive || other.maxInclusive);
        }

        static int compareLow(Range a, Range b) {
            if (a.min == null && b.min == null) {
                // Both unbounded low: sort by high bound so merge is deterministic.
                if (a.max == null && b.max == null) return 0;
                if (a.max == null) return 1;
                if (b.max == null) return -1;
                int cmp = Versions.compare(a.max, b.max);
                if (cmp != 0) return cmp;
                // Prefer exclusive max first so [..x) then [x..] can merge via touch.
                if (a.maxInclusive != b.maxInclusive) return a.maxInclusive ? 1 : -1;
                return 0;
            }
            if (a.min == null) return -1;
            if (b.min == null) return 1;
            int cmp = Versions.compare(a.min, b.min);
            if (cmp != 0) return cmp;
            // Inclusive lower bound sorts before exclusive (smaller set starts later).
            if (a.minInclusive != b.minInclusive) return a.minInclusive ? -1 : 1;
            return 0;
        }

        @Override
        public String toString() {
            if (min != null && max != null && minInclusive && maxInclusive && Versions.compare(min, max) == 0) {
                return "{" + min + "}";
            }
            return (minInclusive ? "[" : "(")
                    + (min == null ? "-∞" : min)
                    + ","
                    + (max == null ? "+∞" : max)
                    + (maxInclusive ? "]" : ")");
        }

        private static char bracket(boolean inclusive, char inc, char exc) {
            return inclusive ? inc : exc;
        }
    }

    /**
     * Canonical disjoint union of two or more {@link Range}s, sorted by lower bound and pairwise
     * non-adjacent. Construct only via {@link #of} so the invariant always holds.
     */
    record Union(List<Range> parts) implements VersionSet {

        public Union {
            Objects.requireNonNull(parts, "parts");
            if (parts.size() < 2) {
                throw new IllegalArgumentException("Union must have at least two parts; got: " + parts);
            }
            parts = List.copyOf(parts);
        }

        /**
         * Build a canonical set from arbitrary parts: flattens nested unions, drops empties, absorbs
         * {@link #ALL}, sorts ranges, and merges overlapping/adjacent ones. Never returns a nested
         * {@code Union}.
         */
        static VersionSet of(List<? extends VersionSet> parts) {
            if (parts.isEmpty()) return EMPTY;
            List<Range> ranges = new ArrayList<>();
            for (VersionSet part : parts) {
                switch (part) {
                    case Empty ignored -> {
                        // skip
                    }
                    case All ignored -> {
                        return ALL;
                    }
                    case Range r -> ranges.add(r);
                    case Union u -> ranges.addAll(u.parts);
                }
            }
            return fromRanges(ranges);
        }

        /** Sort + merge a bag of ranges into EMPTY / single Range / canonical Union. */
        static VersionSet fromRanges(List<Range> ranges) {
            if (ranges.isEmpty()) return EMPTY;
            ranges.sort(Range::compareLow);
            List<Range> merged = new ArrayList<>(ranges.size());
            Range current = ranges.getFirst();
            for (int i = 1; i < ranges.size(); i++) {
                Range next = ranges.get(i);
                if (current.overlapsOrTouches(next)) {
                    current = Range.mergeTouching(current, next);
                } else {
                    merged.add(current);
                    current = next;
                }
            }
            merged.add(current);
            if (merged.size() == 1) return merged.getFirst();
            return new Union(merged);
        }

        @Override
        public boolean contains(String version) {
            for (Range part : parts) {
                if (part.contains(version)) return true;
            }
            return false;
        }

        @Override
        public VersionSet intersect(VersionSet other) {
            return switch (other) {
                case Empty ignored -> EMPTY;
                case All ignored -> this;
                case Range r -> {
                    // Intersect each part with r; re-canonicalize (parts stay disjoint).
                    List<Range> hits = new ArrayList<>();
                    for (Range part : parts) {
                        VersionSet hit = part.intersect(r);
                        if (hit instanceof Range hr) hits.add(hr);
                        // Empty skipped; intersect(Range) never returns Union/All.
                    }
                    yield fromRanges(hits);
                }
                case Union u -> {
                    // Pairwise part ∩ part'; re-canonicalize flattens and merges.
                    List<Range> hits = new ArrayList<>();
                    for (Range a : parts) {
                        for (Range b : u.parts) {
                            VersionSet hit = a.intersect(b);
                            if (hit instanceof Range hr) hits.add(hr);
                        }
                    }
                    yield fromRanges(hits);
                }
            };
        }

        @Override
        public VersionSet union(VersionSet other) {
            return switch (other) {
                case Empty ignored -> this;
                case All ignored -> ALL;
                case Range r -> of(concat(parts, List.of(r)));
                case Union u -> of(concat(parts, u.parts));
            };
        }

        private static List<Range> concat(List<Range> a, List<Range> b) {
            List<Range> out = new ArrayList<>(a.size() + b.size());
            out.addAll(a);
            out.addAll(b);
            return out;
        }

        @Override
        public VersionSet complement() {
            // ¬(A ∪ B ∪ …) = ¬A ∩ ¬B ∩ … — each complement is Range or Union of Ranges;
            // successive intersect re-canonicalizes.
            VersionSet result = ALL;
            for (Range part : parts) {
                result = result.intersect(part.complement());
            }
            return result;
        }

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public String toString() {
            return parts.stream().map(Object::toString).collect(Collectors.joining(" ∪ "));
        }
    }
}
