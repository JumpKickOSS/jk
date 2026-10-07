// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.runtime.LockFlow;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * An imported member pins {@code jakarta.inject-api 2.0.1}, below the {@code 2.0.1.MR} its
 * dependency {@code cryptofs 2.10.0} declares. The raise lifts that pin to what highest-wins
 * resolves, reports it, leaves every other pin where the POM put it, and the project then locks.
 */
class ImportPinRaisesTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    private final MavenStub upstream = new MavenStub(http);

    @TempDir
    Path isolatedStore;

    @BeforeEach
    void publish() {
        System.setProperty("jk.env.JK_STORE_DIR", isolatedStore.resolve("store").toString());
        upstream.leaf("org.junit.jupiter", "junit-jupiter", "6.1.0");
        upstream.leaf("org.junit.platform", "junit-platform-launcher", "6.1.0");
        upstream.metadata("jakarta.inject", "jakarta.inject-api", "2.0.1", "2.0.1.MR");
        for (String v : List.of("2.0.1", "2.0.1.MR")) {
            upstream.pom(
                    "jakarta.inject",
                    "jakarta.inject-api",
                    v,
                    MavenStub.emptyPom("jakarta.inject", "jakarta.inject-api", v));
            upstream.jar("jakarta.inject", "jakarta.inject-api", v);
        }
        upstream.metadata("org.cryptomator", "cryptofs", "2.10.0", "2.11.0");
        for (String v : List.of("2.10.0", "2.11.0")) {
            upstream.pom("org.cryptomator", "cryptofs", v, """
                    <project>
                      <groupId>org.cryptomator</groupId><artifactId>cryptofs</artifactId><version>%s</version>
                      <dependencies>
                        <dependency>
                          <groupId>jakarta.inject</groupId><artifactId>jakarta.inject-api</artifactId><version>2.0.1.MR</version>
                        </dependency>
                      </dependencies>
                    </project>
                    """.formatted(v));
            upstream.jar("org.cryptomator", "cryptofs", v);
        }
    }

    @AfterEach
    void releaseStoreOverride() {
        System.clearProperty("jk.env.JK_STORE_DIR");
    }

    @Test
    void a_pin_below_a_dependencys_floor_is_raised_and_reported(@TempDir Path ws) throws Exception {
        Files.writeString(ws.resolve("jk.toml"), """
                group = "org.cryptomator"
                name = "parent"
                version = "1.0"
                java = 25

                [workspace]
                modules = ["app"]
                """);
        Files.createDirectories(ws.resolve("app"));
        Files.writeString(ws.resolve("app").resolve("jk.toml"), """
                group = "org.cryptomator"
                name = "app"
                version = "1.0"

                [dependencies]
                cryptofs = "org.cryptomator:cryptofs:2.10.0"
                jakarta-inject-api = "jakarta.inject:jakarta.inject-api:2.0.1"
                """);

        long probes = ImportPinRaises.PROBES.sum();
        List<String> lines = ImportPinRaises.apply(ws, ws.resolve("cache-probe"), http.base());

        assertThat(ImportPinRaises.PROBES.sum() - probes)
                .as("a raise no other member carries needs no second probe")
                .isEqualTo(1);
        assertThat(lines)
                .singleElement()
                .asString()
                .startsWith(
                        "`jakarta.inject:jakarta.inject-api` 2.0.1 → 2.0.1.MR ([dependencies] in org.cryptomator:app)")
                .contains("depended on by org.cryptomator:cryptofs 2.10.0")
                .contains("highest wins");
        assertThat(Files.readString(ws.resolve("app").resolve("jk.toml")))
                .contains("jakarta-inject-api = \"jakarta.inject:jakarta.inject-api:2.0.1.MR\"")
                .as("a pin nothing asks above stays where the POM put it")
                .contains("cryptofs = \"org.cryptomator:cryptofs:2.10.0\"");
        assertThat(Files.exists(ws.resolve("jk-lock.toml")))
                .as("the probe writes no lock")
                .isFalse();

        LockFlow.Result lock = LockFlow.run(ws, ws.resolve("cache-lock"), List.of(), false, http.base());
        assertThat(lock.status()).as(String.valueOf(lock.error())).isZero();
        assertThat(requireNonNull(lock.lockfile()).artifacts())
                .filteredOn(a -> a.name().startsWith("jakarta.inject:jakarta.inject-api:"))
                .extracting(Lockfile.Artifact::version)
                .containsExactly("2.0.1.MR");
    }

    /**
     * A raise in one member can put another member's pin below a floor: lib's optional shell needs
     * core 2.0, so lib's core pin rises; app depends on lib, so app now carries core 2.0, whose POM
     * needs api 2.0 above app's own pin. The second round raises it and the workspace locks.
     */
    @Test
    void a_raise_that_moves_a_dependent_members_floor_raises_that_members_pin_too(@TempDir Path ws) throws Exception {
        publishChain();
        writeChain(ws, false);

        long probes = ImportPinRaises.PROBES.sum();
        List<String> lines = ImportPinRaises.apply(ws, ws.resolve("cache-probe"), http.base());

        assertThat(ImportPinRaises.PROBES.sum() - probes)
                .as("lib's raise moves what app carries, app's raise moves nothing another member carries")
                .isEqualTo(2);
        assertThat(lines).anyMatch(l -> l.startsWith("`org.ex:core` 1.0 → 2.0 ([dependencies] in org.ex:lib)"));
        assertThat(lines).anyMatch(l -> l.startsWith("`org.ex:api` 1.0 → 2.0 ([dependencies] in org.ex:app)"));
        LockFlow.Result lock = LockFlow.run(ws, ws.resolve("cache-lock"), List.of(), false, http.base());
        assertThat(lock.status()).as(String.valueOf(lock.error())).isZero();
    }

    /**
     * The second round of the chain above, solved two ways: re-solving only the members whose inputs
     * moved, and re-solving everything. solo pins api 1.0 and nothing it reaches asks higher, so it
     * has a solve of its own that no raise touches; lib's raise lifts core to what the merged graph
     * already holds. Both ways raise the same pins to the same versions; the second round reuses
     * solo's answer and the merged solve instead of running them again.
     */
    @Test
    void a_later_round_re_solves_only_what_moved_and_raises_what_a_full_round_does(@TempDir Path tmp) throws Exception {
        publishChain();
        Path subset = Files.createDirectories(tmp.resolve("subset"));
        Path full = Files.createDirectories(tmp.resolve("full"));
        writeChain(subset, true);
        writeChain(full, true);

        ImportPinRaises.Outcome reused = ImportPinRaises.rounds(subset, tmp.resolve("cache-subset"), http.base(), true);
        ImportPinRaises.Outcome everything =
                ImportPinRaises.rounds(full, tmp.resolve("cache-full"), http.base(), false);

        assertThat(reused.lines()).isEqualTo(everything.lines());
        for (String manifest : List.of("jk.toml", "lib/jk.toml", "app/jk.toml", "solo/jk.toml")) {
            assertThat(Files.readString(subset.resolve(manifest)))
                    .as(manifest)
                    .isEqualTo(Files.readString(full.resolve(manifest)));
        }
        assertThat(Files.readString(subset.resolve("solo/jk.toml"))).contains("api = \"org.ex:api:1.0\"");
        assertThat(reused.rounds()).hasSize(2);
        ImportPinRaises.Round second = reused.rounds().get(1);
        assertThat(second.mergedSolves())
                .as("lib's raise is a floor the merged graph already holds")
                .isZero();
        assertThat(second.memberReuses()).as("solo's inputs did not move").isEqualTo(1);
        assertThat(second.memberSolves())
                .as("one fewer than the full round's")
                .isEqualTo(everything.rounds().get(1).memberSolves() - 1);
        assertThat(everything.rounds().get(1).mergedSolves()).isEqualTo(1);
        assertThat(everything.rounds().get(1).memberReuses()).isZero();
    }

    /** api 1.0 and 2.0; core 2.0 needs api 2.0; shell needs core 2.0. */
    private void publishChain() {
        for (String v : List.of("1.0", "2.0")) {
            upstream.metadata("org.ex", "api", "1.0", "2.0");
            upstream.pom("org.ex", "api", v, MavenStub.emptyPom("org.ex", "api", v));
            upstream.jar("org.ex", "api", v);
        }
        upstream.metadata("org.ex", "core", "1.0", "2.0");
        upstream.pom("org.ex", "core", "1.0", MavenStub.emptyPom("org.ex", "core", "1.0"));
        upstream.jar("org.ex", "core", "1.0");
        upstream.pom("org.ex", "core", "2.0", """
                <project>
                  <groupId>org.ex</groupId><artifactId>core</artifactId><version>2.0</version>
                  <dependencies>
                    <dependency><groupId>org.ex</groupId><artifactId>api</artifactId><version>[2.0,)</version></dependency>
                  </dependencies>
                </project>
                """);
        upstream.jar("org.ex", "core", "2.0");
        upstream.metadata("org.ex", "shell", "1.0");
        upstream.pom("org.ex", "shell", "1.0", """
                <project>
                  <groupId>org.ex</groupId><artifactId>shell</artifactId><version>1.0</version>
                  <dependencies>
                    <dependency><groupId>org.ex</groupId><artifactId>core</artifactId><version>[2.0,)</version></dependency>
                  </dependencies>
                </project>
                """);
        upstream.jar("org.ex", "shell", "1.0");
    }

    /** lib pins core 1.0 beside an optional shell; app depends on lib and pins api 1.0; solo, when asked, pins api 1.0 alone. */
    private static void writeChain(Path ws, boolean solo) throws IOException {
        Files.writeString(
                ws.resolve("jk.toml"), """
                group = "org.ex"
                name = "parent"
                version = "1.0"
                java = 25

                [workspace]
                modules = [%s]
                """.formatted(solo ? "\"lib\", \"app\", \"solo\"" : "\"lib\", \"app\""));
        Files.createDirectories(ws.resolve("lib"));
        Files.writeString(ws.resolve("lib").resolve("jk.toml"), """
                group = "org.ex"
                name = "lib"
                version = "1.0"

                [dependencies]
                core = "org.ex:core:1.0"
                shell = { group = "org.ex", version = "1.0", optional = true }
                """);
        Files.createDirectories(ws.resolve("app"));
        Files.writeString(ws.resolve("app").resolve("jk.toml"), """
                group = "org.ex"
                name = "app"
                version = "1.0"

                [dependencies]
                lib.workspace = true
                api = "org.ex:api:1.0"
                """);
        if (!solo) return;
        Files.createDirectories(ws.resolve("solo"));
        Files.writeString(ws.resolve("solo").resolve("jk.toml"), """
                group = "org.ex"
                name = "solo"
                version = "1.0"

                [dependencies]
                api = "org.ex:api:1.0"
                """);
    }

    @Test
    void a_project_whose_pins_already_meet_every_floor_is_left_alone(@TempDir Path project) throws Exception {
        String manifest = """
                group = "org.cryptomator"
                name = "app"
                version = "1.0"
                java = 25

                [dependencies]
                cryptofs = "org.cryptomator:cryptofs:2.10.0"
                jakarta-inject-api = "jakarta.inject:jakarta.inject-api:2.0.1.MR"
                """;
        Files.writeString(project.resolve("jk.toml"), manifest);

        assertThat(ImportPinRaises.apply(project, project.resolve("cache"), http.base()))
                .isEmpty();
        assertThat(Files.readString(project.resolve("jk.toml"))).isEqualTo(manifest);
    }
}
