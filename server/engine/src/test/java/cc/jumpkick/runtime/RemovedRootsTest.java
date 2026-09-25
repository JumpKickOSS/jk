// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Scope;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A direct dependency that left a compile scope, not the jars that came with it. */
class RemovedRootsTest {

    @Test
    void the_starter_a_test_starter_still_depends_on_is_the_root_that_left_main() {
        List<Lockfile.Artifact> previous = List.of(
                row(
                        "org.springframework.boot:spring-boot-starter-webmvc-test:jar:",
                        Scope.TEST,
                        List.of("org.springframework.boot:spring-boot-starter-webmvc:jar:@4.0.8")),
                row(
                        "org.springframework.boot:spring-boot-starter-webmvc:jar:",
                        Scope.MAIN,
                        List.of("org.springframework:spring-web:jar:@7.0.9")),
                row("org.springframework:spring-web:jar:", Scope.MAIN, List.of()));
        List<Lockfile.Artifact> current = List.of(
                row(
                        "org.springframework.boot:spring-boot-starter-webmvc-test:jar:",
                        Scope.TEST,
                        List.of("org.springframework.boot:spring-boot-starter-webmvc:jar:@4.0.8")),
                row(
                        "org.springframework.boot:spring-boot-starter-webmvc:jar:",
                        Scope.TEST,
                        List.of("org.springframework:spring-web:jar:@7.0.9")),
                row("org.springframework:spring-web:jar:", Scope.TEST, List.of()));

        assertThat(RemovedRoots.of(previous, current, ClasspathResolver.COMPILE_MAIN))
                .containsExactly("org.springframework.boot:spring-boot-starter-webmvc");
        assertThat(RemovedRoots.of(previous, current, ClasspathResolver.COMPILE_TEST))
                .isEmpty();
    }

    @Test
    void an_aggregator_removed_from_test_is_the_root_and_its_jars_are_not() {
        List<String> junit = List.of(
                "org.junit.jupiter:junit-jupiter-api:jar:@6.1.3", "org.junit.jupiter:junit-jupiter-params:jar:@6.1.3");
        List<Lockfile.Artifact> previous = List.of(
                row("org.junit.jupiter:junit-jupiter:jar:", Scope.TEST, junit),
                row("org.junit.jupiter:junit-jupiter-api:jar:", Scope.TEST, List.of()),
                row("org.junit.jupiter:junit-jupiter-params:jar:", Scope.TEST, List.of()));
        List<Lockfile.Artifact> current =
                List.of(row("org.junit.platform:junit-platform-launcher:jar:", Scope.TEST, List.of()));

        assertThat(RemovedRoots.of(previous, current, ClasspathResolver.COMPILE_TEST))
                .containsExactly("org.junit.jupiter:junit-jupiter");
    }

    private static Lockfile.Artifact row(String name, Scope scope, List<String> deps) {
        return new Lockfile.Artifact(
                name, "1.0", "central+https://repo.maven.apache.org/maven2/", null, null, List.of(scope), deps);
    }
}
