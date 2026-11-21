// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver.pubgrub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
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
            assertThat(rendered).contains("a 1.0 depends on shared 1.0");
            assertThat(rendered).contains("b 1.0 depends on shared 2.0");
            assertThat(rendered).contains("cannot be resolved");
        } catch (Exception e) {
            fail("expected UnsatisfiableException, got: " + e);
        }
    }

    /** A coordinate opening a sentence keeps its case: a group id is a name, not prose. */
    @Test
    void a_coordinate_at_sentence_start_keeps_its_case() {
        PackageSource src = InMemoryPackageSource.builder()
                .version("org.slf4j:slf4j-api", "1.7.36")
                .version("org.slf4j:slf4j-api", "2.0.13")
                .version(
                        "ch.qos.logback:logback-classic",
                        "1.5.6",
                        deps -> deps.require("org.slf4j:slf4j-api", VersionSet.exact("2.0.13")))
                .version(
                        "org.apache.zookeeper:zookeeper",
                        "3.9.2",
                        deps -> deps.require("org.slf4j:slf4j-api", VersionSet.exact("1.7.36")))
                .build();
        try {
            new PubGrubSolver(src)
                    .solve(
                            "<root>",
                            "1.0",
                            List.of(
                                    Term.positive("ch.qos.logback:logback-classic", VersionSet.exact("1.5.6")),
                                    Term.positive("org.apache.zookeeper:zookeeper", VersionSet.exact("3.9.2"))));
            fail("expected UnsatisfiableException");
        } catch (UnsatisfiableException e) {
            String rendered = Diagnostics.render(e.rootCause());
            assertThat(rendered).contains("ch.qos.logback:logback-classic 1.5.6 depends on org.slf4j:slf4j-api 2.0.13");
            assertThat(rendered).contains("org.apache.zookeeper:zookeeper 3.9.2 depends on org.slf4j:slf4j-api 1.7.36");
            assertThat(rendered).doesNotContain("Ch.qos").doesNotContain("Org.apache");
            assertThat(rendered).contains("The project depends on");
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
            // : actionable suggestions from available samples
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
            new PubGrubSolver(src).solve("root", "1.0", List.of(Term.positive("missing:lib", VersionSet.exact("1.0"))));
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
                new Incompatibility.Cause.BudgetExceeded(
                        "decision limit 5 reached; raise it with JK_RESOLVE_MAX_DECISIONS"));
        String rendered = Diagnostics.render(inco);
        assertThat(rendered).contains("budget exceeded").contains("decision limit 5");
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

    /** A derivation chain as deep as a long solve's resolution steps renders without a stack of its own depth. */
    @Test
    void a_derivation_chain_thousands_deep_renders() {
        Term root = Term.positive("<root>", VersionSet.exact("1.0"));
        Term dep = Term.positive("widget", VersionSet.exact("1.0"));
        Incompatibility leaf =
                new Incompatibility(List.of(root, dep.invert()), new Incompatibility.Cause.Dependency(root, dep));
        Incompatibility chain = leaf;
        int depth = 20_000;
        for (int i = 0; i < depth; i++) {
            chain = new Incompatibility(List.of(root), new Incompatibility.Cause.Derived(chain, leaf));
        }

        String rendered = Diagnostics.render(chain);

        assertThat(rendered.lines().filter(l -> l.contains("Therefore,")).count())
                .as("a linear chain prints one conclusion")
                .isEqualTo(1);
        assertThat(rendered.lines().filter(l -> l.contains("depends on")).count())
                .as("the shared leaf renders once and is referenced after that")
                .isEqualTo(1);
        // Every derived node names the leaf as its second input, the bottom one as both inputs.
        assertThat(rendered.lines().filter(l -> l.contains("(see #1)")).count()).isEqualTo(depth);
        assertThat(rendered)
                .as("the suggestion walk reaches the leaf at the bottom of the chain")
                .contains("Relax or remove the project constraint on widget");
    }

    /** The project's own declarations a failure derives from, which is where an editor marks it. */
    @Test
    void root_constraints_name_the_projects_own_declarations_behind_a_conflict() throws Exception {
        PackageSource src = InMemoryPackageSource.builder()
                .version("org.slf4j:slf4j-api", "1.7.36")
                .version("org.slf4j:slf4j-api", "2.0.13")
                .version(
                        "ch.qos.logback:logback-classic",
                        "1.5.6",
                        deps -> deps.require("org.slf4j:slf4j-api", VersionSet.exact("2.0.13")))
                .version(
                        "org.apache.zookeeper:zookeeper",
                        "3.9.2",
                        deps -> deps.require("org.slf4j:slf4j-api", VersionSet.exact("1.7.36")))
                .version("unrelated:lib", "1.0")
                .build();
        try {
            new PubGrubSolver(src)
                    .solve(
                            "<root>",
                            "1.0",
                            List.of(
                                    Term.positive("unrelated:lib", VersionSet.exact("1.0")),
                                    Term.positive("ch.qos.logback:logback-classic", VersionSet.exact("1.5.6")),
                                    Term.positive("org.apache.zookeeper:zookeeper", VersionSet.exact("3.9.2"))));
            fail("expected UnsatisfiableException");
        } catch (UnsatisfiableException e) {
            assertThat(Diagnostics.rootConstraints(e.rootCause()))
                    .containsExactlyInAnyOrder("ch.qos.logback:logback-classic", "org.apache.zookeeper:zookeeper");
        }
    }

    @Test
    void root_constraints_name_a_missing_package_the_project_declares() throws Exception {
        PackageSource src = InMemoryPackageSource.builder().build();
        try {
            new PubGrubSolver(src)
                    .solve("<root>", "1.0", List.of(Term.positive("missing:lib", VersionSet.exact("1.0"))));
            fail("expected UnsatisfiableException");
        } catch (UnsatisfiableException e) {
            assertThat(Diagnostics.rootConstraints(e.rootCause())).containsExactly("missing:lib");
        }
    }

    /** Each link of a linear chain concludes from the one before and one fact: only the last conclusion prints. */
    @Test
    void a_linear_chain_prints_its_facts_and_one_conclusion() {
        Incompatibility chain = fact("a");
        for (String pkg : List.of("b", "c", "d")) {
            chain = new Incompatibility(List.of(ROOT), new Incompatibility.Cause.Derived(chain, fact(pkg)));
        }

        String rendered = Diagnostics.render(chain);

        assertThat(rendered.lines().filter(l -> l.contains("depends on")).count())
                .isEqualTo(4);
        assertThat(rendered.lines().filter(l -> l.contains("Therefore,")).toList())
                .containsExactly("  │ Therefore, the project's requirements cannot be resolved");
    }

    /** A node concluding from two derived nodes is no chain: each branch keeps its conclusion. */
    @Test
    void two_derived_branches_keep_their_conclusions() {
        Incompatibility left = new Incompatibility(
                List.of(ROOT, Term.negative("x", VersionSet.ALL)),
                new Incompatibility.Cause.Derived(fact("a"), fact("b")));
        Incompatibility right = new Incompatibility(
                List.of(ROOT, Term.negative("y", VersionSet.ALL)),
                new Incompatibility.Cause.Derived(fact("c"), fact("d")));
        Incompatibility top = new Incompatibility(List.of(ROOT), new Incompatibility.Cause.Derived(left, right));

        String rendered = Diagnostics.render(top);

        assertThat(rendered.lines().filter(l -> l.contains("Therefore,")).count())
                .isEqualTo(3);
    }

    /** A term a BOM or managed entry decided names it beside the dependency, in the text and as data. */
    @Test
    void a_conflicting_term_names_the_bom_that_pins_it() {
        PackageSource versions = InMemoryPackageSource.builder()
                .version("org.slf4j:slf4j-api", "1.7.36")
                .version("org.slf4j:slf4j-api", "2.0.13")
                .version(
                        "ch.qos.logback:logback-classic",
                        "1.5.6",
                        deps -> deps.require("org.slf4j:slf4j-api", VersionSet.exact("2.0.13")))
                .version(
                        "org.apache.zookeeper:zookeeper",
                        "3.9.2",
                        deps -> deps.require("org.slf4j:slf4j-api", VersionSet.exact("1.7.36")))
                .build();
        PackageSource pinned = new PackageSource() {
            @Override
            public List<String> versions(String pkg) throws IOException, InterruptedException {
                return versions.versions(pkg);
            }

            @Override
            public List<Term> dependencies(String pkg, String version) throws IOException, InterruptedException {
                return versions.dependencies(pkg, version);
            }

            @Override
            public @Nullable String pinnedBy(String pkg, VersionSet constraint) {
                return pkg.equals("org.slf4j:slf4j-api") && constraint.equals(VersionSet.exact("1.7.36"))
                        ? "org.apache.zookeeper:zookeeper-bom:3.9.2"
                        : null;
            }
        };
        try {
            new PubGrubSolver(pinned)
                    .solve(
                            "<root>",
                            "1.0",
                            List.of(
                                    Term.positive("ch.qos.logback:logback-classic", VersionSet.exact("1.5.6")),
                                    Term.positive("org.apache.zookeeper:zookeeper", VersionSet.exact("3.9.2"))));
            fail("expected UnsatisfiableException");
        } catch (UnsatisfiableException e) {
            assertThat(Diagnostics.render(e.rootCause()))
                    .contains("org.apache.zookeeper:zookeeper 3.9.2 depends on org.slf4j:slf4j-api 1.7.36"
                            + " (pinned by org.apache.zookeeper:zookeeper-bom:3.9.2)")
                    .contains("ch.qos.logback:logback-classic 1.5.6 depends on org.slf4j:slf4j-api 2.0.13\n");

            Conflict conflict = e.conflict();
            assertThat(conflict.facts())
                    .filteredOn(f -> f.from() != null && f.from().equals("org.apache.zookeeper:zookeeper"))
                    .singleElement()
                    .satisfies(f -> {
                        assertThat(f.kind()).isEqualTo("dependency");
                        assertThat(f.pkg()).isEqualTo("org.slf4j:slf4j-api");
                        assertThat(f.versions()).isEqualTo("1.7.36");
                        assertThat(f.fromVersions()).isEqualTo("3.9.2");
                        assertThat(f.pinnedBy()).isEqualTo("org.apache.zookeeper:zookeeper-bom:3.9.2");
                    });
        } catch (Exception e) {
            fail("expected UnsatisfiableException, got: " + e);
        }
    }

    /** The typed view carries what the text explains: facts, the project's declarations, the conclusion. */
    @Test
    void the_conflict_is_typed_and_serialises_to_plain_json_values() {
        PackageSource src = InMemoryPackageSource.builder()
                .version("shared", "1.0")
                .version("shared", "2.0")
                .version("a", "1.0", deps -> deps.require("shared", VersionSet.exact("1.0")))
                .version("b", "1.0", deps -> deps.require("shared", VersionSet.exact("2.0")))
                .build();
        try {
            new PubGrubSolver(src)
                    .solve(
                            "<root>",
                            "1.0",
                            List.of(
                                    Term.positive("a", VersionSet.exact("1.0")),
                                    Term.positive("b", VersionSet.exact("1.0"))));
            fail("expected UnsatisfiableException");
        } catch (UnsatisfiableException e) {
            Conflict conflict = e.conflict();
            assertThat(conflict.declared()).containsExactlyInAnyOrder("a", "b");
            assertThat(conflict.conclusion()).endsWith("cannot be resolved");
            assertThat(conflict.facts())
                    .extracting(Conflict.Fact::kind, Conflict.Fact::from, Conflict.Fact::pkg, Conflict.Fact::versions)
                    .contains(
                            tuple("dependency", "a", "shared", "1.0"),
                            tuple("dependency", "b", "shared", "2.0"),
                            tuple("dependency", null, "a", "1.0"));

            Map<String, Object> json = conflict.toMap();
            assertThat(json).containsOnlyKeys("conclusion", "packages", "facts", "declared");
            assertThat(json.get("facts"))
                    .asList()
                    .contains(Map.of(
                            "kind",
                            "dependency",
                            "package",
                            "shared",
                            "versions",
                            "2.0",
                            "from",
                            "b",
                            "fromVersions",
                            "1.0"));
        } catch (Exception e) {
            fail("expected UnsatisfiableException, got: " + e);
        }
    }

    @Test
    void a_missing_package_is_a_not_found_fact() {
        PackageSource src = InMemoryPackageSource.builder().build();
        try {
            new PubGrubSolver(src)
                    .solve("<root>", "1.0", List.of(Term.positive("missing:lib", VersionSet.exact("1.0"))));
            fail("expected UnsatisfiableException");
        } catch (UnsatisfiableException e) {
            assertThat(e.conflict().facts())
                    .extracting(Conflict.Fact::kind, Conflict.Fact::pkg)
                    .contains(tuple("not-found", "missing:lib"));
        } catch (Exception e) {
            fail("expected UnsatisfiableException, got: " + e);
        }
    }

    private static final Term ROOT = Term.positive("<root>", VersionSet.exact("1.0"));

    /** The project's dependency on {@code pkg} 1.0, as its own leaf. */
    private static Incompatibility fact(String pkg) {
        Term dep = Term.positive(pkg, VersionSet.exact("1.0"));
        return new Incompatibility(List.of(ROOT, dep.invert()), new Incompatibility.Cause.Dependency(ROOT, dep));
    }
}
