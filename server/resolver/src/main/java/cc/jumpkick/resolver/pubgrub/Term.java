// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver.pubgrub;

import java.util.Objects;

/**
 * A statement about a package: it must (or must NOT) be at one of the versions in {@link
 * #versions()}. Pure data; algebra is implemented as operations that return new {@link Term}s.
 *
 * <p>Following the PubGrub paper:
 *
 * <ul>
 *   <li>Positive term {@code [p, V]}: package {@code p} is at some version in {@code V}.
 *   <li>Negative term {@code [p, ¬V]}: package {@code p} is at some version <i>not</i> in {@code V}
 *       (or absent — encoded by the empty version set on the inverted view).
 * </ul>
 */
public record Term(String pkg, VersionSet versions, boolean positive) {

    public Term {
        Objects.requireNonNull(pkg, "pkg");
        Objects.requireNonNull(versions, "versions");
    }

    public static Term positive(String pkg, VersionSet versions) {
        return new Term(pkg, versions, true);
    }

    public static Term negative(String pkg, VersionSet versions) {
        return new Term(pkg, versions, false);
    }

    public Term invert() {
        return new Term(pkg, versions, !positive);
    }

    /** The version-set view of this term ({@code V} if positive, {@code ¬V} if negative). */
    public VersionSet effectiveVersions() {
        return positive ? versions : versions.complement();
    }

    /**
     * {@code this ∩ other} per PubGrub §3: two negative terms intersect to the negative of their
     * union ({@code ¬A ∩ ¬B = ¬(A ∪ B)}) — a term that still permits the package to be absent;
     * any intersection involving a positive term asserts presence and is packaged positive.
     */
    public Term intersect(Term other) {
        if (!pkg.equals(other.pkg)) {
            throw new IllegalArgumentException(
                    "cannot intersect terms about different packages: " + pkg + " vs " + other.pkg);
        }
        if (!positive && !other.positive) {
            return new Term(pkg, versions.union(other.versions), false);
        }
        VersionSet vs = effectiveVersions().intersect(other.effectiveVersions());
        return new Term(pkg, vs, true);
    }

    /**
     * True iff every assignment satisfies this term: {@code ¬p ∅}. A negative term over every
     * version is not one: it says the package is absent.
     */
    public boolean alwaysHolds() {
        return !positive && versions.isEmpty();
    }

    /**
     * Per PubGrub paper §3: relationship between this term and another. A negative term also holds
     * when the package is absent, so it never satisfies a positive term and never contradicts
     * another negative one. A positive term over no versions holds for no assignment and answers
     * {@link Relation#SATISFIES} against every term.
     */
    public Relation relation(Term other) {
        if (!pkg.equals(other.pkg)) {
            throw new IllegalArgumentException(
                    "cannot relate terms about different packages: " + pkg + " vs " + other.pkg);
        }
        VersionSet a = versions;
        VersionSet b = other.versions;
        if (positive && other.positive) {
            if (a.subsetOf(b)) return Relation.SATISFIES;
            return disjoint(a, b) ? Relation.CONTRADICTS : Relation.OVERLAPS;
        }
        if (positive) {
            if (disjoint(a, b)) return Relation.SATISFIES;
            return a.subsetOf(b) ? Relation.CONTRADICTS : Relation.OVERLAPS;
        }
        if (other.positive) {
            return b.subsetOf(a) ? Relation.CONTRADICTS : Relation.OVERLAPS;
        }
        return b.subsetOf(a) ? Relation.SATISFIES : Relation.OVERLAPS;
    }

    private static boolean disjoint(VersionSet a, VersionSet b) {
        return a.intersect(b).isEmpty();
    }

    /** Result of {@link #relation(Term)}. */
    public enum Relation {
        /** This term implies the other ({@code this ⇒ other}). */
        SATISFIES,
        /** This term contradicts the other (no shared satisfying version). */
        CONTRADICTS,
        /** Neither — there's overlap but neither implies the other. */
        OVERLAPS
    }

    @Override
    public String toString() {
        return (positive ? "" : "¬") + pkg + " " + versions;
    }
}
