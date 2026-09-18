// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.PluginSpec;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import cc.jumpkick.plugin.protocol.SpecWriter;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A step may declare the module's remote repositories as an input; the engine writes them to the
 * spec as jk routes them, in resolve order, and both spec readers hand the body the same list — so
 * a worker whose own resolver fetches outside the lock asks what jk asks and nothing else. A
 * packager declares the same input and reads the same list off {@link PackageIo#repositories()}.
 */
class RepositoriesInputTest {

    private static final URI MIRROR = URI.create("https://maven-central.storage-download.googleapis.com/maven2/");
    private static final URI INTERNAL = URI.create("https://repo.acme.test/maven/");
    private static final URI NEXUS = URI.create("https://nexus.example.test/repository/all/");

    @Test
    void repositories_is_a_declared_input_with_its_own_wire_spelling() {
        assertThat(In.repositories().wireName()).isEqualTo("repositories");
        assertThat(In.fromWire("repositories")).isEqualTo(In.repositories());
        assertThat(In.repositories().kind()).isEqualTo(In.Kind.REPOSITORIES);
    }

    @Test
    void both_spec_readers_hand_the_routes_back_in_order_with_their_credentials(@TempDir Path tmp) throws Exception {
        Path spec = new SpecWriter()
                .op(PluginProtocol.OP_RUN_STEP, "quarkus-augment", "jk-quarkus")
                .repository(new RepositoryRoute("jumpkick", NEXUS, "deploy", "s3cret"))
                .repository(new RepositoryRoute("internal", INTERNAL, null, "t0k3n"))
                .repository(RepositoryRoute.anonymous("central", MIRROR))
                .writeTempSpec();

        BuildPluginHarness.Spec harness = BuildPluginHarness.Spec.read(spec);
        PluginSpec typed = PluginSpec.read(spec);

        assertThat(harness.repositories())
                .containsExactly(
                        new RepositoryRoute("jumpkick", NEXUS, "deploy", "s3cret"),
                        new RepositoryRoute("internal", INTERNAL, null, "t0k3n"),
                        RepositoryRoute.anonymous("central", MIRROR));
        assertThat(typed.repositories()).isEqualTo(harness.repositories());
    }

    @Test
    void a_packager_reads_the_routes_off_its_io(@TempDir Path tmp) throws Exception {
        Path spec = new SpecWriter()
                .op(PluginProtocol.OP_PACKAGE, null, "fx")
                .project(new ProjectFacts("g", "n", "1", 25, null, false, false, Map.of()))
                .repository(new RepositoryRoute("internal", INTERNAL, null, "t0k3n"))
                .repository(RepositoryRoute.anonymous("central", MIRROR))
                .writeTempSpec();
        BuildPlugin fixture = ctx -> ctx.packaging(PackagerSpec.replacingMainArtifact("probe-package")
                .produce(io -> io.label("routes="
                        + io.repositories().stream()
                                .map(r -> r.id() + "=" + r.url())
                                .toList())));
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ProtocolWriter out = new ProtocolWriter(new PrintStream(buffer, true, StandardCharsets.UTF_8), "##FX:");

        int exit = BuildPluginHarness.run(fixture, List.of(spec.toString()), out);

        assertThat(exit).isZero();
        assertThat(buffer.toString(StandardCharsets.UTF_8))
                .contains("routes=[internal=" + INTERNAL + ", central=" + MIRROR + "]");
    }

    @Test
    void a_spec_without_the_lines_reads_as_no_repositories(@TempDir Path tmp) throws Exception {
        Path spec = new SpecWriter()
                .op(PluginProtocol.OP_RUN_STEP, "step", "plugin")
                .writeTempSpec();

        assertThat(BuildPluginHarness.Spec.read(spec).repositories()).isEmpty();
        assertThat(PluginSpec.read(spec).repositories()).isEmpty();
    }

    @Test
    void a_route_names_its_credential_kind() {
        assertThat(RepositoryRoute.anonymous("central", MIRROR).anonymous()).isTrue();
        assertThat(new RepositoryRoute("internal", INTERNAL, null, "t0k3n").bearer())
                .isTrue();
        assertThat(new RepositoryRoute("nexus", NEXUS, "deploy", "s3cret").bearer())
                .isFalse();
        assertThatThrownBy(() -> new RepositoryRoute("nexus", NEXUS, "deploy", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nexus");
    }
}
