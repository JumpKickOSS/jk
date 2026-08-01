// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver.pubgrub;

import cc.jumpkick.resolver.Versions;
import java.util.BitSet;
import java.util.Objects;

/**
 * A subset of a {@link VersionUniverse}, stored as a bitset over the universe's version indices.
 * Immutable: every algebra op returns a new instance.
 *
 * <p>Index order matches {@link VersionUniverse#versions} — highest-first with soft-prefer pins
 * at the front — so {@link #choosePreferred} walks set bits in that order (same selection policy
 * as the pre-interning scan of {@code PackageSource.versions}).
 */
public final class AllowedSet {

    private final VersionUniverse universe;
    private final BitSet bits;

    private AllowedSet(VersionUniverse universe, BitSet bits) {
        this.universe = universe;
        this.bits = bits;
    }

    static AllowedSet allOf(VersionUniverse universe) {
        BitSet bits = new BitSet(universe.size());
        if (universe.size() > 0) bits.set(0, universe.size());
        return new AllowedSet(universe, bits);
    }

    static AllowedSet noneOf(VersionUniverse universe) {
        return new AllowedSet(universe, new BitSet(universe.size()));
    }

    static AllowedSet projecting(VersionUniverse universe, VersionSet constraint) {
        BitSet bits = new BitSet(universe.size());
        for (int i = 0; i < universe.size(); i++) {
            if (constraint.contains(universe.version(i))) bits.set(i);
        }
        return new AllowedSet(universe, bits);
    }

    public VersionUniverse universe() {
        return universe;
    }

    public boolean isEmpty() {
        return bits.isEmpty();
    }

    public int size() {
        return bits.cardinality();
    }

    public boolean containsVersion(String version) {
        int i = universe.indexOf(version);
        return i >= 0 && bits.get(i);
    }

    public AllowedSet intersect(AllowedSet other) {
        requireSameUniverse(other);
        BitSet next = (BitSet) bits.clone();
        next.and(other.bits);
        return new AllowedSet(universe, next);
    }

    /** True iff every version allowed here is also allowed in {@code other}. */
    public boolean subsetOf(AllowedSet other) {
        requireSameUniverse(other);
        // bits ⊆ other.bits ⇔ bits & ~other.bits = ∅
        BitSet leftover = (BitSet) bits.clone();
        leftover.andNot(other.bits);
        return leftover.isEmpty();
    }

    /**
     * Preferred pick for the decision procedure.
     *
     * <p>Soft-prefer pins (lock/BOM) sit at universe index 0 even when lower than a later stable.
     * When that front slot is still allowed, take it <em>unconditionally</em> — including pre-release
     * pins. Otherwise walk remaining candidates highest-first, preferring the first
     * <em>stable</em> version, then the first pre-release, else {@code null} when empty.
     */
    public String choosePreferred() {
        int first = bits.nextSetBit(0);
        if (first < 0) return null;
        // Soft-prefer front: index 0 is not a strict max of the universe → pin was front-loaded.
        if (first == 0 && isSoftPreferFront()) {
            return universe.version(0);
        }
        String prerelease = null;
        for (int i = first; i >= 0; i = bits.nextSetBit(i + 1)) {
            String v = universe.version(i);
            if (Versions.isStable(v)) return v;
            if (prerelease == null) prerelease = v;
        }
        return prerelease;
    }

    /**
     * True when universe index 0 is a soft-prefer pin rather than the natural highest version:
     * some later advertised version compares greater under Maven order.
     */
    private boolean isSoftPreferFront() {
        if (universe.size() <= 1) return false;
        String front = universe.version(0);
        for (int i = 1; i < universe.size(); i++) {
            if (Versions.compare(universe.version(i), front) > 0) return true;
        }
        return false;
    }

    /**
     * Continuous {@link VersionSet} view for diagnostics / APIs that still speak ranges. Empty →
     * {@link VersionSet#EMPTY}; otherwise a union of exact singles (canonical after R1). Not used
     * on the propagation hot path.
     */
    public VersionSet toVersionSet() {
        if (bits.isEmpty()) return VersionSet.EMPTY;
        VersionSet acc = null;
        for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1)) {
            VersionSet exact = VersionSet.exact(universe.version(i));
            acc = acc == null ? exact : acc.union(exact);
        }
        return acc;
    }

    private void requireSameUniverse(AllowedSet other) {
        Objects.requireNonNull(other, "other");
        if (universe != other.universe) {
            throw new IllegalArgumentException(
                    "AllowedSet universe mismatch: " + universe.pkg() + " vs " + other.universe.pkg());
        }
    }

    @Override
    public String toString() {
        return "AllowedSet(" + universe.pkg() + " #" + size() + "/" + universe.size() + ")";
    }
}
