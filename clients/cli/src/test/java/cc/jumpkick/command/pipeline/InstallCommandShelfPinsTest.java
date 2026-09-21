// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.ShelfManifest;
import cc.jumpkick.cli.testing.NoAnsi;
import cc.jumpkick.cli.theme.Coords;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.wire.protocol.ProjectInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The shelf pins a workspace install records: plain coordinates whatever the theme paints, and
 * no manifest at all when the home names no engine by sha256.
 */
class InstallCommandShelfPinsTest {

    private static final String ENGINE = "e".repeat(64);

    @Test
    void shelf_keys_are_plain_coordinates_when_colour_is_on(@TempDir Path tmp) throws Exception {
        Path jar = Files.writeString(tmp.resolve("jk-java-compiler-1.0.jar"), "compiler");
        Coordinate coord = Coordinate.of("cc.jumpkick", "jk-java-compiler", "1.0");
        Map<String, String> jars = NoAnsi.forcedAnsi(() -> {
            assertThat(Coords.gav(coord)).as("the theme paints coordinates").contains("\u001b[");
            return InstallCommand.shelfJars(List.of(tmp), Map.of(tmp, info(coord, jar)));
        });
        assertThat(jars).containsExactly(Map.entry("cc.jumpkick:jk-java-compiler:1.0", Hashing.sha256Hex(jar)));
        assertThat(jars.keySet())
                .allSatisfy(key -> assertThat(ShelfManifest.isCoordinate(key)).isTrue());
    }

    @Test
    void a_module_without_a_jar_or_a_coordinator_root_pins_nothing(@TempDir Path tmp) throws Exception {
        Coordinate coord = Coordinate.of("cc.jumpkick", "jk-root", "1.0");
        Map<String, String> jars =
                InstallCommand.shelfJars(List.of(tmp), Map.of(tmp, info(coord, tmp.resolve("never-built.jar"))));
        assertThat(jars).isEmpty();
    }

    @Test
    void a_home_that_names_no_engine_sha_pins_nothing_and_says_so(@TempDir Path tmp) throws Exception {
        Path manifest = tmp.resolve("lib/jk-engine/" + ShelfManifest.FILE_NAME);
        String line = InstallCommand.pinShelf(Optional.empty(), manifest, tmp, Map.of("g:a:1", "1".repeat(64)));
        assertThat(line).isEqualTo(InstallCommand.SHELF_NOT_PINNED).contains("not pinned");
        assertThat(manifest).doesNotExist();

        line = InstallCommand.pinShelf(Optional.of(ENGINE), manifest, tmp, Map.of("g:a:1", "1".repeat(64)));
        assertThat(line).startsWith("Pinned 1 shelf jar to engine " + ENGINE.substring(0, 12));
        assertThat(ShelfManifest.read(manifest).orElseThrow().sha("g:a:1")).contains("1".repeat(64));
    }

    private static ProjectInfo info(Coordinate coord, Path mainJar) {
        ProjectInfo base = ProjectInfo.error(null);
        return new ProjectInfo(
                null,
                coord.group(),
                coord.artifact(),
                coord.version(),
                base.jdk(),
                base.javaRelease(),
                base.kotlin(),
                base.kotlinVersion(),
                base.groovy(),
                base.groovyVersion(),
                base.layoutSimple(),
                base.workspaceRoot(),
                base.workspaceRootDir(),
                base.moduleDirs(),
                base.application(),
                base.mainClass(),
                base.assembly(),
                base.applicationConfig(),
                base.nativeMode(),
                base.graal(),
                base.springBoot(),
                base.springBootVersion(),
                base.formatStyle(),
                base.formatJava(),
                base.formatKotlin(),
                base.formatOptimizeImports(),
                base.formatImportOrder(),
                base.formatRemoveUnusedImports(),
                base.hasLock(),
                base.lockJdk(),
                mainJar.toString(),
                base.assemblyJarPath(),
                base.nativeBinPath(),
                base.nativeLibPath(),
                base.pathDeps(),
                base.sourcesJarPath(),
                base.javadocJarPath(),
                base.envRefs(),
                base.moduleNames(),
                base.sourceCount(),
                base.testCount(),
                base.nativeExplicitlyDisabled(),
                base.classesDir(),
                base.testClassesDir(),
                base.kotlinClassesDir(),
                base.groovyClassesDir(),
                base.testResultsDir(),
                base.testIncludeTags(),
                base.testExcludeTags(),
                base.lockStale(),
                base.scala(),
                base.scalaVersion(),
                base.coordinatorOnly(),
                base.productLib(),
                base.productBin(),
                Map.of());
    }
}
