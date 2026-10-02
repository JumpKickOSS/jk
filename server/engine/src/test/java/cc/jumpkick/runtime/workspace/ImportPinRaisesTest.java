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
