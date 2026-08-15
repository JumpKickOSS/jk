// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver.pubgrub;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * a mandatory dependency whose entire subtree is unresolvable (a transitive package with
 * an empty version universe) must fail the solve — the sigstore-java lock silently dropped
 * grpc-netty-shaded's whole closure instead while keeping the parent, an incomplete
 * solution that violated the parent's dependency incompatibility.
 */
class PubGrubUnresolvableSubtreeTest {

    /** root → a (range); a@1.0 → b (range, several versions); every b → c; c has NO versions. */
    private static PackageSource chainWithEmptyLeaf() {
        var builder = InMemoryPackageSource.builder()
                .version("a", "1.0", d -> d.require("b", VersionSet.atLeast("1.0", true)));
        for (String bv : List.of("1.0", "1.1", "1.2", "1.3", "1.4", "1.5")) {
            builder.version("b", bv, d -> d.require("c", VersionSet.atLeast("1.0", true)));
        }
        // "c" is never registered — empty universe, like an exclusively-claimed group whose
        // repo does not host the artifact.
        return builder.build();
    }

    @Test
    void transitive_empty_universe_fails_the_solve() {
        PackageSource src = chainWithEmptyLeaf();
        assertThatThrownBy(() -> new PubGrubSolver(src)
                        .solve("root", "1.0", List.of(Term.positive("a", VersionSet.atLeast("1.0", true)))))
                .isInstanceOf(UnsatisfiableException.class);
    }

    @Test
    void transitive_empty_universe_fails_the_solve_with_exact_root_pin() {
        PackageSource src = chainWithEmptyLeaf();
        assertThatThrownBy(() -> new PubGrubSolver(src)
                        .solve("root", "1.0", List.of(Term.positive("a", VersionSet.exact("1.0")))))
                .isInstanceOf(UnsatisfiableException.class);
    }

    @Test
    void deeper_diamond_with_empty_leaf_still_fails() {
        // root → a; a → b + d; d fine; b's subtree dead — the solve must not "succeed" by
        // silently omitting b.
        var builder = InMemoryPackageSource.builder()
                .version("a", "1.0", d -> d.require("b", VersionSet.atLeast("1.0", true))
                        .require("d", VersionSet.atLeast("1.0", true)))
                .version("d", "1.0");
        for (String bv : List.of("1.0", "1.1", "1.2")) {
            builder.version("b", bv, d -> d.require("c", VersionSet.atLeast("1.0", true)));
        }
        PackageSource src = builder.build();
        assertThatThrownBy(() -> new PubGrubSolver(src)
                        .solve("root", "1.0", List.of(Term.positive("a", VersionSet.atLeast("1.0", true)))))
                .isInstanceOf(UnsatisfiableException.class);
    }
}
