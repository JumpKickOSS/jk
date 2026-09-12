// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.command.pipeline.InstallCommand;
import cc.jumpkick.wire.protocol.ProjectInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code [install] product-bin} — jk's own client replacing the PATH client. The freshness question
 * is the destination's, as with {@code product-lib}: the engine's forecast knows the build output,
 * not whether {@code bin/jk} holds it.
 */
class InstallProductBinTest {

    @Test
    void the_built_binary_replaces_jk_and_jkx_and_parks_the_previous_client(@TempDir Path tmp) throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Files.writeString(bin.resolve("jk"), "old client");
        Files.writeString(bin.resolve("jkx"), "old client");
        Path built =
                Files.writeString(Files.createDirectories(tmp.resolve("target")).resolve("jk"), "new client");

        Path installed = InstallCommand.installProductBin(built, bin, new Cas(tmp.resolve("cas")));

        assertThat(installed).isEqualTo(bin.resolve("jk"));
        assertThat(bin.resolve("jk")).hasContent("new client");
        assertThat(bin.resolve("jkx")).hasContent("new client");
        assertThat(bin.resolve("jk.old")).hasContent("old client");
        // The bin entry is not an alias of target/: rebuilding there leaves the install alone.
        Files.writeString(built, "next build");
        assertThat(bin.resolve("jk")).hasContent("new client");
    }

    @Test
    void the_path_client_is_stale_until_it_holds_the_built_bytes(@TempDir Path tmp) throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path built = Files.writeString(tmp.resolve("jk"), "built");
        ProjectInfo info = info("jk", built.toString());

        assertThat(InstallCommand.productBinStale(info, bin))
                .as("nothing installed yet")
                .isTrue();
        Files.writeString(bin.resolve("jk"), "older");
        assertThat(InstallCommand.productBinStale(info, bin)).as("other bytes").isTrue();
        Files.writeString(bin.resolve("jk"), "built");
        assertThat(InstallCommand.productBinStale(info, bin)).isFalse();
    }

    @Test
    void an_ordinary_module_is_never_product_bin_stale(@TempDir Path tmp) throws Exception {
        assertThat(InstallCommand.productBinStale(info("", tmp.resolve("jk").toString())))
                .isFalse();
        assertThat(InstallCommand.productBinStale((ProjectInfo) null)).isFalse();
    }

    @Test
    void a_client_that_was_never_built_is_not_stale_either(@TempDir Path tmp) throws Exception {
        assertThat(InstallCommand.productBinStale(
                        info("jk", tmp.resolve("missing").toString()), tmp))
                .isFalse();
    }

    private static ProjectInfo info(String productBin, String nativeBinPath) {
        ProjectInfo base = ProjectInfo.error(null);
        return new ProjectInfo(
                null,
                "cc.jumpkick",
                "jk-cli",
                "0.1.0",
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
                true,
                "cc.jumpkick.cli.Jk",
                false,
                base.applicationConfig(),
                "ALWAYS",
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
                base.mainJarPath(),
                base.assemblyJarPath(),
                nativeBinPath,
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
                "",
                productBin);
    }
}
