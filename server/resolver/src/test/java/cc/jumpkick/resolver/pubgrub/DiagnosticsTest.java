// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver.pubgrub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.util.List;
import org.junit.jupiter.api.Test;

class DiagnosticsTest {

    @Test
    void renders_dependency_conflict() {
        PackageSource src = InMemoryPackageSource.builder()
                .version("shared", "1.0")
                .version("shared", "2.0")
                .version("a", "1.0", deps -> deps.require("shared", VersionSet.exact("1.0")))
                .version("b", "1.0", deps -> deps.require("shared", VersionSet.exact("2.0")))
                .build();

        PubGrubSolver solver = new PubGrubSolver(src);
        try {
            solver.solve(
                    "root",
                    "1.0",
                    List.of(Term.positive("a", VersionSet.exact("1.0")), Term.positive("b", VersionSet.exact("1.0"))));
            fail("expected UnsatisfiableException");
        } catch (UnsatisfiableException e) {
            String rendered = Diagnostics.render(e.rootCause());
            assertThat(rendered).contains("Cannot resolve dependencies");
            assertThat(rendered).contains("A 1.0 depends on shared 1.0");
            assertThat(rendered).contains("B 1.0 depends on shared 2.0");
            assertThat(rendered).contains("cannot be resolved");
        } catch (Exception e) {
            fail("expected UnsatisfiableException, got: " + e);
        }
    }

    @Test
    void renders_missing_version() {
        PackageSource src =
                InMemoryPackageSource.builder().version("widget", "1.0").build();

        PubGrubSolver solver = new PubGrubSolver(src);
        try {
            solver.solve("root", "1.0", List.of(Term.positive("widget", VersionSet.exact("9.9.9"))));
            fail("expected UnsatisfiableException");
        } catch (UnsatisfiableException e) {
            String rendered = Diagnostics.render(e.rootCause());
            assertThat(rendered).contains("No versions of widget");
            // R6a: near-miss sample of advertised versions.
            assertThat(rendered).contains("available:");
            assertThat(rendered).contains("1.0");
            // ticket-1005: actionable suggestions from available samples
            assertThat(rendered).contains("Suggestions:");
            assertThat(rendered).contains("Pin widget to 1.0");
        } catch (Exception e) {
            fail("expected UnsatisfiableException, got: " + e);
        }
    }

    @Test
    void renders_unknown_package() {
        PackageSource src = InMemoryPackageSource.builder().build();
        try {
            new PubGrubSolver(src)
                    .solve("root", "1.0", List.of(Term.positive("missing:lib", VersionSet.exact("1.0"))));
            fail("expected UnsatisfiableException");
        } catch (UnsatisfiableException e) {
            String rendered = Diagnostics.render(e.rootCause());
            assertThat(rendered).contains("was not found in any repository");
            assertThat(rendered).contains("missing:lib");
            // Honest: no fabricated pin for a package that was not found.
            assertThat(rendered).doesNotContain("Suggestions:");
            assertThat(rendered).doesNotContain("Pin missing");
        } catch (Exception e) {
            fail("expected UnsatisfiableException, got: " + e);
        }
    }

    @Test
    void renders_budget_exceeded() {
        Incompatibility inco = new Incompatibility(
                List.of(Term.positive("<root>", VersionSet.EMPTY)),
                new Incompatibility.Cause.BudgetExceeded("exceeded max decisions (5)"));
        String rendered = Diagnostics.render(inco);
        assertThat(rendered).contains("budget exceeded").contains("max decisions");
    }

    @Test
    void leaf_sentences_are_deduplicated() {
        // Two derived nodes whose subtrees share a dependency leaf should
        // not print the same sentence twice.
        Term root = Term.positive("root", VersionSet.exact("1.0"));
        Term dep = Term.positive("widget", VersionSet.exact("1.0"));
        Incompatibility shared =
                new Incompatibility(List.of(root, dep.invert()), new Incompatibility.Cause.Dependency(root, dep));

        Incompatibility a = shared;
        Incompatibility b = shared;
        Incompatibility derived =
                new Incompatibility(List.of(root, dep.invert()), new Incompatibility.Cause.Derived(a, b));

        String rendered = Diagnostics.render(derived);
        // The shared dependency fact should appear once as a numbered sentence
        // and be referenced by "#" on subsequent mentions — not re-emitted verbatim.
        long dependsOnCount =
                rendered.lines().filter(l -> l.contains("depends on")).count();
        assertThat(dependsOnCount).isEqualTo(1);
    }

    @Test
    void renders_therefore_chain_for_derived_incompatibilities() {
        // A conflict between two declared deps produces a Derived inco at
        // some point in resolution. The diagnostic should emit a
        // "therefore:" sentence after stating the inputs.
        PackageSource src = InMemoryPackageSource.builder()
                .version("shared", "1.0")
                .version("shared", "2.0")
                .version("a", "1.0", deps -> deps.require("shared", VersionSet.exact("1.0")))
                .version("b", "1.0", deps -> deps.require("shared", VersionSet.exact("2.0")))
                .build();

        try {
            new PubGrubSolver(src)
                    .solve(
                            "root",
                            "1.0",
                            List.of(
                                    Term.positive("a", VersionSet.exact("1.0")),
                                    Term.positive("b", VersionSet.exact("1.0"))));
            fail("expected UnsatisfiableException");
        } catch (UnsatisfiableException e) {
            String rendered = Diagnostics.render(e.rootCause());
            assertThat(rendered).contains("Therefore,");
            assertThat(rendered).contains("cannot be resolved");
        } catch (Exception e) {
            fail("expected UnsatisfiableException, got: " + e);
        }
    }

    @Test
    void shared_incompatibilities_get_back_references() {
        // A diamond: root inco = Derived(D1, D2) where D1 and D2 both
        // reference the same leaf. The leaf appears once and is numbered.
        Term root = Term.positive("root", VersionSet.exact("1.0"));
        Term dep = Term.positive("widget", VersionSet.exact("1.0"));
        Incompatibility leaf =
                new Incompatibility(List.of(root, dep.invert()), new Incompatibility.Cause.Dependency(root, dep));

        // Two distinct Derived nodes that both reference the leaf.
        Incompatibility branchA =
                new Incompatibility(List.of(root, dep.invert()), new Incompatibility.Cause.Derived(leaf, leaf));
        Incompatibility branchB =
                new Incompatibility(List.of(root, dep.invert()), new Incompatibility.Cause.Derived(leaf, leaf));
        Incompatibility top =
                new Incompatibility(List.of(root, dep.invert()), new Incompatibility.Cause.Derived(branchA, branchB));

        String rendered = Diagnostics.render(top);
        // The shared leaf should be numbered (it has >1 incoming edges) and
        // referenced by number on its second mention.
        assertThat(rendered).contains("(see #");
    }
}
