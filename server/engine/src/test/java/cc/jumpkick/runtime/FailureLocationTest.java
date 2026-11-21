// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.resolver.pubgrub.InMemoryPackageSource;
import cc.jumpkick.resolver.pubgrub.PubGrubSolver;
import cc.jumpkick.resolver.pubgrub.Term;
import cc.jumpkick.resolver.pubgrub.VersionSet;
import cc.jumpkick.run.SourceLocation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A failure that ended a run points at the manifest line it is about, wrapped or not. */
class FailureLocationTest {

    @Test
    void an_unsatisfiable_resolve_points_at_the_declaration_it_derives_from(@TempDir Path dir) throws Exception {
        Path manifest = Files.writeString(dir.resolve("jk.toml"), """
                group = "x.y"
                name = "app"
                version = "0.1.0"
                java = 25

                [dependencies]
                lib = { group = "com.example", version = "1.0" }
                """);
        Throwable unsat = catchThrowable(
                () -> new PubGrubSolver(InMemoryPackageSource.builder().build())
                        .solve("<root>", "1.0", List.of(Term.positive("com.example:lib", VersionSet.ALL))));

        assertThat(FailureLocation.of(new RuntimeException("lock failed", unsat), dir))
                .isEqualTo(
                        new SourceLocation(manifest.toAbsolutePath().normalize().toString(), 7, 1));
    }

    @Test
    void a_manifest_that_does_not_parse_points_at_the_spot_in_its_file(@TempDir Path dir) throws Exception {
        Path manifest = Files.writeString(
                dir.resolve("jk.toml"), "group = \"x.y\"\nname = \"app\"\nversion = \"0.1.0\"\njava = \"x\"\n");
        Throwable refused = catchThrowable(() -> JkBuildParser.parse(manifest));

        assertThat(FailureLocation.of(refused, dir))
                .isEqualTo(
                        new SourceLocation(manifest.toAbsolutePath().normalize().toString(), 4, 1));
        assertThat(FailureLocation.of(new IllegalStateException("graal missing"), dir))
                .isNull();
    }

    @Test
    void a_cause_chain_that_loops_back_is_read_once(@TempDir Path dir) {
        IllegalStateException outer = new IllegalStateException("outer");
        IllegalStateException inner = new IllegalStateException("inner");
        outer.initCause(inner);
        inner.initCause(outer);

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThat(FailureLocation.of(outer, dir))
                .isNull());
    }

    @Test
    void the_recorded_text_leaves_the_place_to_the_location_beside_it(@TempDir Path dir) throws Exception {
        Path manifest = Files.writeString(
                dir.resolve("jk.toml"), "group = \"x.y\"\nname = \"app\"\nversion = \"0.1.0\"\njava = \"x\"\n");
        Throwable refused = catchThrowable(() -> JkBuildParser.parse(manifest));

        assertThat(refused.getMessage()).startsWith(manifest.toAbsolutePath().normalize() + ":4:1: ");
        assertThat(FailureLocation.text(refused))
                .isEqualTo(((JkBuildParseException) refused).refusal())
                .doesNotContain(manifest.getFileName() + ":4");
        assertThat(FailureLocation.text(new IllegalStateException("graal missing")))
                .isEqualTo("graal missing");
    }
}
