// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver.pubgrub;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.version.Versions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A negative dependency term is a constraint with Gradle's semantics: it bounds a package some
 * positive edge brings in and never adds one.
 */
class PubGrubConstraintTest {

    @Test
    void a_constraint_raises_a_module_another_edge_brings_in() throws Exception {
        PackageSource src = InMemoryPackageSource.builder()
                .version("ktx", "2.0")
                .version("ktx", "1.5")
                .version("ktx", "1.0")
                .version("core", "1.5", deps -> deps.constrainPlain("ktx", "1.5"))
                .version("core", "1.0")
                .version("fragment", "1.0", deps -> deps.requirePlain("ktx", "1.0"))
                .version("activity", "1.0", deps -> deps.requirePlain("core", "1.5"))
                .build();

        Map<String, String> picked = new PubGrubSolver(src)
                .solve(
                        "root",
                        "1.0",
                        List.of(
                                Term.positive("fragment", VersionSet.exact("1.0")),
                                Term.positive("activity", VersionSet.exact("1.0"))));

        // The constraint's version is a declared version too, so the pair aligns on 1.5 rather
        // than floating ktx to the newest release.
        assertThat(picked).containsEntry("core", "1.5").containsEntry("ktx", "1.5");
    }

    @Test
    void a_constraint_on_a_module_already_decided_below_it_backtracks() throws Exception {
        // fragment is expanded first and settles ktx at its declared 1.0; core's constraint then
        // arrives and rules that out, so the solver has to revisit ktx.
        PackageSource src = InMemoryPackageSource.builder()
                .version("ktx", "2.0")
                .version("ktx", "1.5")
                .version("ktx", "1.0")
                .version("core", "1.5", deps -> deps.constrain("ktx", VersionSet.atLeast("1.5", true)))
                .version("fragment", "1.0", deps -> deps.requirePlain("ktx", "1.0"))
                .build();

        Map<String, String> picked = new PubGrubSolver(src)
                .solve(
                        "root",
                        "1.0",
                        List.of(
                                Term.positive("fragment", VersionSet.exact("1.0")),
                                Term.positive("core", VersionSet.exact("1.5"))));

        assertThat(Versions.compare(requireNonNull(picked.get("ktx")), "1.5")).isGreaterThanOrEqualTo(0);
    }

    @Test
    void a_constraint_alone_never_adds_the_module() throws Exception {
        PackageSource src = InMemoryPackageSource.builder()
                .version("ghost", "1.0")
                .version("core", "1.0", deps -> deps.constrainPlain("ghost", "1.0"))
                .build();

        Map<String, String> picked =
                new PubGrubSolver(src).solve("root", "1.0", List.of(Term.positive("core", VersionSet.exact("1.0"))));

        assertThat(picked).containsEntry("core", "1.0").doesNotContainKey("ghost");
    }

    @Test
    void a_constraint_that_cannot_be_met_reads_as_a_constraint_in_the_diagnostics() {
        PackageSource src = InMemoryPackageSource.builder()
                .version("ktx", "1.5")
                .version("ktx", "1.0")
                .version("core", "1.5", deps -> deps.constrain("ktx", VersionSet.atLeast("1.5", true)))
                .version("fragment", "1.0", deps -> deps.require("ktx", VersionSet.exact("1.0")))
                .build();

        assertThatThrownBy(() -> new PubGrubSolver(src)
                        .solve(
                                "root",
                                "1.0",
                                List.of(
                                        Term.positive("fragment", VersionSet.exact("1.0")),
                                        Term.positive("core", VersionSet.exact("1.5")))))
                .isInstanceOf(UnsatisfiableException.class)
                .satisfies(e -> {
                    String rendered = Diagnostics.render(((UnsatisfiableException) e).rootCause());
                    assertThat(rendered).contains("fragment 1.0 depends on ktx 1.0");
                    assertThat(rendered).contains("core 1.5 constrains ktx");
                });
    }
}
