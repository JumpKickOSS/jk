// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.protocol.ProjectInfo;
import org.junit.jupiter.api.Test;

/**
 * {@code [install] product-lib} — the freshness question the ordinary install shapes do not ask.
 *
 * <p>A module that declares one installs into jk's own product layout, which the CLI owns; the
 * engine's forecast reasons about build outputs and the coordinate in {@code repos/jk-local} and
 * would answer "already installed" while that layout held a stale artifact, or none. The client
 * therefore asks about the destination itself (JK-1069).
 */
class InstallProductLibTest {

    private static ProjectInfo info(String productLib, String mainJarPath) {
        ProjectInfo base = ProjectInfo.error(null);
        return new ProjectInfo(
                null,
                "cc.jumpkick",
                "jk-engine",
                "0.12.0",
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
                false,
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
                mainJarPath,
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
                productLib);
    }

    @Test
    void an_ordinary_module_is_never_product_lib_stale() {
        assertThat(InstallCommand.productLibStale(info("", "/nowhere/app-1.0.jar")))
                .as("a library, an executable, a native binary, a script — none declare one")
                .isFalse();
    }

    @Test
    void a_declared_module_with_nothing_built_is_not_stale() {
        // Nothing to install yet; packaging schedules the module on its own account.
        assertThat(InstallCommand.productLibStale(info("jk-engine", "/nowhere/jk-engine-0.12.0.jar")))
                .isFalse();
    }

    @Test
    void a_parse_error_never_reads_as_stale() {
        assertThat(InstallCommand.productLibStale(ProjectInfo.error("no jk.toml")))
                .isFalse();
    }
}
