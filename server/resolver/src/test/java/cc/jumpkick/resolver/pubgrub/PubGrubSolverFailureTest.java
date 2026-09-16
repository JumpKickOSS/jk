// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver.pubgrub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

/**
 * A solver that cannot finish says which package it was on. A repository that advertises nothing
 * for a package a POM names is a conflict on that package, and an exception the solver did not
 * expect is rethrown with the package and the operation in its message.
 */
class PubGrubSolverFailureTest {

    @Test
    void a_declared_version_of_a_package_nothing_advertises_is_a_conflict_on_that_package() {
        // mid's POM names widget 1.0; widget has no maven-metadata at all, so its universe is empty
        // before the declared version is admitted.
        PackageSource src = InMemoryPackageSource.builder()
                .version("mid", "1.0", deps -> deps.requirePlain("widget", "1.0"))
                .build();

        UnsatisfiableException unsat = catchThrowableOfType(UnsatisfiableException.class, () -> new PubGrubSolver(src)
                .solve("root", "1.0", List.of(Term.positive("mid", VersionSet.exact("1.0")))));

        assertThat(unsat).isNotNull();
        assertThat(Diagnostics.render(unsat.rootCause(), Diagnostics.Palette.PLAIN))
                .contains("widget")
                .contains("no declared repository has it");
    }

    @Test
    void an_unexpected_exception_names_the_package_and_the_operation() {
        PackageSource src = new PackageSource() {
            @Override
            public List<String> versions(String pkg) {
                throw new NoSuchElementException();
            }

            @Override
            public List<Term> dependencies(String pkg, String version) {
                return List.of();
            }
        };

        assertThatThrownBy(() -> new PubGrubSolver(src)
                        .solve("root", "1.0", List.of(Term.positive("widget", VersionSet.atLeast("1.0", true)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("choosing a version for widget")
                .hasMessageContaining("java.util.NoSuchElementException")
                .hasCauseInstanceOf(NoSuchElementException.class);
    }

    @Test
    void an_unexpected_exception_while_reading_dependencies_names_the_coordinate() {
        PackageSource src = new PackageSource() {
            @Override
            public List<String> versions(String pkg) {
                return List.of("1.0");
            }

            @Override
            public List<Term> dependencies(String pkg, String version) {
                throw new NoSuchElementException("No value present");
            }
        };

        assertThatThrownBy(() -> new PubGrubSolver(src)
                        .solve("root", "1.0", List.of(Term.positive("widget", VersionSet.exact("1.0")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reading the dependencies of widget@1.0")
                .hasMessageContaining("No value present");
    }
}
