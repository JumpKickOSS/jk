// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.ShelfManifest;
import cc.jumpkick.cli.testing.NoAnsi;
import cc.jumpkick.cli.theme.Coords;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.repo.MavenLayout;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The shelf pins a workspace install records: the shas the engine reported on each outcome,
 * under plain coordinates whatever the theme paints, and no manifest at all when the home names
 * no engine by sha256.
 */
class ShelfPinningTest {

    private static final String ENGINE = "e".repeat(64);

    @Test
    void shelf_keys_are_plain_coordinates_when_colour_is_on() throws Exception {
        Coordinate coord = Coordinate.of("cc.jumpkick", "jk-java-compiler", "1.0");
        ModuleOutcome outcome = new ModuleOutcome(coord.toGav(), Path.of("/ws/java"), true, 0, 10)
                .withShelved(new ModuleOutcome.Shelved(coord.toGav(), "1".repeat(64), "2".repeat(64)));
        ShelfPinning.Pins pins = NoAnsi.forcedAnsi(() -> {
            assertThat(Coords.gav(coord)).as("the theme paints coordinates").contains("\u001b[");
            return ShelfPinning.shelved(new WorkspaceResult(true, 0, List.of(outcome), List.of()));
        });
        assertThat(pins.jars()).containsExactly(Map.entry("cc.jumpkick:jk-java-compiler:1.0", "1".repeat(64)));
        assertThat(pins.poms()).containsExactly(Map.entry("cc.jumpkick:jk-java-compiler:1.0", "2".repeat(64)));
        assertThat(pins.jars().keySet())
                .allSatisfy(key -> assertThat(ShelfManifest.isCoordinate(key)).isTrue());
    }

    @Test
    void a_module_whose_outcome_shelved_nothing_pins_nothing() {
        // A coordinator root builds as a unit but carries no cache-install step.
        WorkspaceResult result = new WorkspaceResult(
                true, 0, List.of(new ModuleOutcome("cc.jumpkick:jk-root", Path.of("/ws"), true, 0, 1)), List.of());
        ShelfPinning.Pins pins = ShelfPinning.shelved(result);
        assertThat(pins.jars()).isEmpty();
        assertThat(pins.poms()).isEmpty();
    }

    @Test
    void a_home_that_names_no_engine_sha_pins_nothing_and_says_so(@TempDir Path tmp) throws Exception {
        Path manifest = tmp.resolve("lib/jk-engine/" + ShelfManifest.FILE_NAME);
        ShelfPinning.Pins pins =
                new ShelfPinning.Pins(Map.of("g:a:1", "1".repeat(64)), Map.of("g:a:1", "2".repeat(64)));
        String line = ShelfPinning.record(Optional.empty(), manifest, tmp, pins);
        assertThat(line).isEqualTo(ShelfPinning.SHELF_NOT_PINNED).contains("not pinned");
        assertThat(manifest).doesNotExist();

        line = ShelfPinning.record(Optional.of(ENGINE), manifest, tmp, pins);
        assertThat(line).startsWith("Pinned 1 shelf jar to engine " + ENGINE.substring(0, 12));
        ShelfManifest recorded = ShelfManifest.read(manifest).orElseThrow();
        assertThat(recorded.sha("g:a:1")).contains("1".repeat(64));
        assertThat(recorded.pomSha("g:a:1")).contains("2".repeat(64));
    }

    /**
     * The manifest takes the POM sha the engine published, not a hash of whatever sits on the
     * shelf when the client records: a concurrent install from another checkout that replaced the
     * slot in between must not lend this manifest its POM.
     */
    @Test
    void the_pom_sha_is_the_one_the_engine_published_even_when_the_shelf_copy_changed(@TempDir Path tmp)
            throws Exception {
        Coordinate coord = Coordinate.of("cc.jumpkick", "jk-java-compiler", "1.0");
        Path shelfPom = tmp.resolve("store/repos/jk-local").resolve(MavenLayout.pomPath(coord));
        Files.createDirectories(shelfPom.getParent());
        Files.writeString(shelfPom, "<project>other checkout</project>");
        String publishedPom = Hashing.sha256Hex("<project>this checkout</project>");
        String publishedJar = "1".repeat(64);
        assertThat(publishedPom).isNotEqualTo(Hashing.sha256Hex(shelfPom));

        ModuleOutcome outcome = new ModuleOutcome(coord.toGav(), tmp.resolve("plugins/java"), true, 0, 10)
                .withShelved(new ModuleOutcome.Shelved(coord.toGav(), publishedJar, publishedPom));
        Path manifest = tmp.resolve(ShelfManifest.FILE_NAME);
        ShelfPinning.record(
                Optional.of(ENGINE),
                manifest,
                tmp,
                ShelfPinning.shelved(new WorkspaceResult(true, 0, List.of(outcome), List.of())));

        ShelfManifest pins = ShelfManifest.read(manifest).orElseThrow();
        assertThat(pins.sha(coord.toGav())).contains(publishedJar);
        assertThat(pins.pomSha(coord.toGav())).contains(publishedPom);
    }

    @Test
    void a_scoped_install_pins_the_modules_it_shelved_and_keeps_the_other_pins(@TempDir Path tmp) throws Exception {
        Path manifest = tmp.resolve(ShelfManifest.FILE_NAME);
        Coordinate kotlin = Coordinate.of("cc.jumpkick", "jk-kotlin-compiler", "1.0");
        Coordinate java = Coordinate.of("cc.jumpkick", "jk-java-compiler", "1.0");

        // A full install pinned both at v1; then only the java worker was edited and installed
        // from its own directory: the engine ran (and shelved) that module alone.
        String kotlinV1 = "1".repeat(64);
        String javaV1 = "2".repeat(64);
        Map<String, String> v1 = Map.of(kotlin.toGav(), kotlinV1, java.toGav(), javaV1);
        ShelfPinning.record(Optional.of(ENGINE), manifest, tmp, new ShelfPinning.Pins(v1, v1));
        String javaJarV2 = "3".repeat(64);
        String javaPomV2 = "4".repeat(64);
        Path javaDir = tmp.resolve("plugins/java-compiler");
        WorkspaceResult scoped = new WorkspaceResult(
                true,
                0,
                List.of(new ModuleOutcome(java.toGav(), javaDir, true, 0, 10)
                        .withShelved(new ModuleOutcome.Shelved(java.toGav(), javaJarV2, javaPomV2))),
                List.of());

        ShelfPinning.Pins pins = ShelfPinning.shelved(scoped);
        assertThat(pins.jars()).containsOnlyKeys(java.toGav());
        assertThat(pins.poms()).containsOnlyKeys(java.toGav());
        ShelfPinning.record(Optional.of(ENGINE), manifest, javaDir, pins);

        ShelfManifest recorded = ShelfManifest.read(manifest).orElseThrow();
        assertThat(recorded.sha(java.toGav())).contains(javaJarV2);
        assertThat(recorded.pomSha(java.toGav())).contains(javaPomV2);
        assertThat(recorded.sha(kotlin.toGav()))
                .as("the un-shelved worker keeps the pin of the bytes still on the shelf")
                .contains(kotlinV1);
        assertThat(recorded.pomSha(kotlin.toGav())).contains(kotlinV1);
    }

    @Test
    void a_failed_module_is_not_pinned() {
        Path dir = Path.of("/ws/plugins/x").toAbsolutePath();
        WorkspaceResult result = new WorkspaceResult(
                false,
                1,
                List.of(
                        new ModuleOutcome("g:ok:1", dir.resolve("ok"), true, 0, 1)
                                .withShelved(new ModuleOutcome.Shelved("g:ok:1", "a".repeat(64), "b".repeat(64))),
                        new ModuleOutcome("g:bad:1", dir, false, 1, 1)
                                .withShelved(new ModuleOutcome.Shelved("g:bad:1", "c".repeat(64), "d".repeat(64)))),
                List.of());
        ShelfPinning.Pins pins = ShelfPinning.shelved(result);
        assertThat(pins.jars()).containsOnlyKeys("g:ok:1");
        assertThat(pins.poms()).containsOnlyKeys("g:ok:1");
    }
}
