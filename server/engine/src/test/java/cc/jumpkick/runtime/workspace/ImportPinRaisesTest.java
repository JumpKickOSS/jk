// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.runtime.LockFlow;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
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

        List<String> lines = ImportPinRaises.apply(ws, ws.resolve("cache-probe"), http.base());

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
        Files.writeString(ws.resolve("jk.toml"), """
                group = "org.ex"
                name = "parent"
                version = "1.0"
                java = 25

                [workspace]
                modules = ["lib", "app"]
                """);
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

        List<String> lines = ImportPinRaises.apply(ws, ws.resolve("cache-probe"), http.base());

        assertThat(lines).anyMatch(l -> l.startsWith("`org.ex:core` 1.0 → 2.0 ([dependencies] in org.ex:lib)"));
        assertThat(lines).anyMatch(l -> l.startsWith("`org.ex:api` 1.0 → 2.0 ([dependencies] in org.ex:app)"));
        LockFlow.Result lock = LockFlow.run(ws, ws.resolve("cache-lock"), List.of(), false, http.base());
        assertThat(lock.status()).as(String.valueOf(lock.error())).isZero();
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
