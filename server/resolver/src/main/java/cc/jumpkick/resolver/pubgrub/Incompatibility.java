// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver.pubgrub;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A PubGrub <i>incompatibility</i>: a non-empty list of {@link Term}s such that at least one term
 * must NOT be satisfied by any solution. Carries a {@link Cause} so we can later render English
 * diagnostics by walking the derivation DAG back to root causes.
 */
public record Incompatibility(List<Term> terms, Cause cause) {

    public Incompatibility {
        Objects.requireNonNull(terms, "terms");
        Objects.requireNonNull(cause, "cause");
        if (terms.isEmpty()) {
            throw new IllegalArgumentException("incompatibility must have at least one term");
        }
        terms = List.copyOf(terms);
    }

    /**
     * Why this incompatibility exists. Each cause carries enough context to be rendered as a sentence
     * in the failure message.
     */
    public sealed interface Cause {

        /** "The root project requires the root package itself." */
        record Root(String rootPkg, String rootVersion) implements Cause {}

        /**
         * "Package {@code from} {@code fromVersions} depends on {@code to}." {@code pinnedBy} names
         * what decided {@code to}'s versions when the edge's own did not: a BOM as {@code
         * group:artifact:version}, a {@code [managed-dependencies]} entry as {@code
         * jk.toml:<handle>}, or {@code [dependencies]} for a test edge held at the main graph's pin;
         * {@code null} otherwise.
         */
        record Dependency(Term from, Term to, @Nullable String pinnedBy) implements Cause {
            /** An edge whose own version decides the target's. */
            public Dependency(Term from, Term to) {
                this(from, to, null);
            }
        }

        /**
         * No versions of {@code pkg} satisfy the requested set. {@code unknownPackage} is {@code true}
         * when the package source returned an empty version list (typically a 404 on the artifact's
         * {@code maven-metadata.xml}), and {@code false} when some versions exist but none satisfy the
         * constraint. {@code available} is a sample of advertised versions (highest-first, capped)
         * for near-miss diagnostics (R6a); {@code notes} are the source's reasons the list lacks what
         * was asked ({@link PackageSource#refusalNotes}).
         */
        record NoVersions(
                String pkg, VersionSet requested, boolean unknownPackage, List<String> available, List<String> notes)
                implements Cause {
            public NoVersions {
                available = available == null ? List.of() : List.copyOf(available);
                notes = notes == null ? List.of() : List.copyOf(notes);
            }

            public NoVersions(String pkg, VersionSet requested, boolean unknownPackage, List<String> available) {
                this(pkg, requested, unknownPackage, available, List.of());
            }

            public NoVersions(String pkg, VersionSet requested) {
                this(pkg, requested, false, List.of());
            }

            public NoVersions(String pkg, VersionSet requested, boolean unknownPackage) {
                this(pkg, requested, unknownPackage, List.of());
            }
        }

        /**
         * The exact version {@code pkg@version} is advertised by metadata but definitively
         * unavailable (its POM 404s in every declared repo — a half-published release). The solver
         * excluded it and retreated to the next candidate.
         */
        record Unavailable(
                String pkg, String version, @Nullable String reason, boolean declaredOnly) implements Cause {
            /** A version the repository advertised. */
            public Unavailable(String pkg, String version, @Nullable String reason) {
                this(pkg, version, reason, false);
            }
        }

        /**
         * Conflict-resolution derived this from two prior incompatibilities. Used by the diagnostic
         * renderer to reconstruct the explanation tree.
         */
        record Derived(Incompatibility a, Incompatibility b) implements Cause {}

        /**
         * The solver hit its decision or step budget, or made no progress for a stall window. Not
         * a logical unsatisfiability: the graph may still resolve with higher limits.
         */
        record BudgetExceeded(String reason) implements Cause {}
    }

    @Override
    public String toString() {
        return terms.toString() + " (because " + cause + ")";
    }
}
