// SPDX-License-Identifier: Apache-2.0

import java.io.File
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

/**
 * The client `:engine:installLocal` materializes through. The case that matters is a stale `build/dist/jk` from an
 * earlier version sitting beside a freshly built one: it is runnable, so position alone would pick it, and the engine
 * it would then be handed is not its own.
 */
@EnabledOnOs(OS.LINUX, OS.MAC)
class JkLayoutPathsTest {

    @Test
    fun stale_dist_client_loses_to_the_build_that_matches_the_engine_jar(@TempDir root: Path) {
        val stale = fakeClient(root, "build/dist/jk", "0.12.0")
        val fresh = fakeClient(root, "clients/cli/build/native/nativeCompile/jk", "0.13.0")

        val probes = JkLayoutPaths.probeClients(root.toFile())

        assertThat(probes)
            .containsExactly(JkLayoutPaths.ClientProbe(fresh, "0.13.0"), JkLayoutPaths.ClientProbe(stale, "0.12.0"))
        assertThat(JkLayoutPaths.pickClient(probes, "0.13.0")).isEqualTo(fresh)
        assertThat(JkLayoutPaths.pickClient(probes, "0.12.0")).isEqualTo(stale)
    }

    /** Nothing to run the materialize through — the task must fail, not fall back to the stale one. */
    @Test
    fun no_client_of_the_engine_jars_version_is_no_client(@TempDir root: Path) {
        fakeClient(root, "build/dist/jk", "0.12.0")

        val probes = JkLayoutPaths.probeClients(root.toFile())

        assertThat(JkLayoutPaths.pickClient(probes, "0.13.0")).isNull()
        assertThat(JkLayoutPaths.describeProbes(probes)).contains("build/dist/jk -> 0.12.0")
    }

    /** A candidate that will not identify itself is never picked, whatever version is wanted. */
    @Test
    fun a_client_that_does_not_print_a_version_is_not_a_match(@TempDir root: Path) {
        val mute = script(root, "clients/cli/build/install/jk/bin/jk", "echo 'not a version line'")
        val broken = script(root, "clients/cli/build/native/nativeCompile/jk", "exit 3")

        val probes = JkLayoutPaths.probeClients(root.toFile())

        assertThat(probes)
            .containsExactly(JkLayoutPaths.ClientProbe(broken, null), JkLayoutPaths.ClientProbe(mute, null))
        assertThat(JkLayoutPaths.pickClient(probes, "0.13.0")).isNull()
        assertThat(JkLayoutPaths.describeProbes(probes)).contains("no version")
    }

    @Test
    fun describes_an_empty_tree_as_no_client(@TempDir root: Path) {
        assertThat(JkLayoutPaths.probeClients(root.toFile())).isEmpty()
        assertThat(JkLayoutPaths.describeProbes(emptyList())).contains("no runnable client")
    }

    @Test
    fun engine_jar_version_comes_from_the_shadow_jar_name(@TempDir root: Path) {
        val dir = root.toFile()
        assertThat(JkLayoutPaths.engineJarVersion(File(dir, "jk-engine-0.13.0.jar"))).isEqualTo("0.13.0")
        assertThat(JkLayoutPaths.engineJarVersion(File(dir, "jk-engine-1.0.0-rc1.jar"))).isEqualTo("1.0.0-rc1")
        assertThat(JkLayoutPaths.engineJarVersion(File(dir, "jk-engine-.jar"))).isNull()
        assertThat(JkLayoutPaths.engineJarVersion(File(dir, "jk-engine.jar"))).isNull()
        assertThat(JkLayoutPaths.engineJarVersion(File(dir, "engine-0.13.0.jar"))).isNull()
    }

    private fun fakeClient(root: Path, relPath: String, version: String): File =
        script(root, relPath, "[ \"\$1\" = --version ] || exit 64", "echo 'jk $version'")

    private fun script(root: Path, relPath: String, vararg body: String): File {
        val f = root.resolve(relPath).toFile()
        f.parentFile.mkdirs()
        f.writeText((listOf("#!/bin/sh") + body).joinToString("\n", postfix = "\n"))
        check(f.setExecutable(true)) { "cannot make $f executable" }
        return f
    }

    /**
     * The home a test's `JK_HOME` points at must be outside the checkout. A sandbox carrying jk's whole layout inside
     * the source tree is what let a git command walk up into the developer's repository and reset it; the path shape is
     * the mitigation, so it is pinned rather than eyeballed.
     */
    @Test
    fun the_test_home_is_outside_the_checkout(@TempDir root: Path) {
        val home = JkLayoutPaths.testHomeFor(root.toFile(), ":server:engine")

        assertThat(home.absoluteFile.normalize().path).doesNotStartWith(root.toFile().absoluteFile.normalize().path)
        assertThat(home.path).endsWith("server-engine")
    }

    /** Sibling worktrees check out under the same name, so the name alone is not an identity. */
    @Test
    fun sibling_checkouts_of_one_name_do_not_share_a_home(@TempDir tmp: Path) {
        val a = tmp.resolve("a/jk").toFile().apply { mkdirs() }
        val b = tmp.resolve("b/jk").toFile().apply { mkdirs() }

        assertThat(JkLayoutPaths.testHomeFor(a, ":cli")).isNotEqualTo(JkLayoutPaths.testHomeFor(b, ":cli"))
        assertThat(JkLayoutPaths.checkoutKey(a)).startsWith("jk-").isNotEqualTo(JkLayoutPaths.checkoutKey(b))
    }

    /** One checkout reached two ways is one checkout, so it gets one home. */
    @Test
    fun a_checkout_reached_through_a_link_keys_as_the_directory_itself(@TempDir tmp: Path) {
        val real = tmp.resolve("real/jk").toFile().apply { mkdirs() }
        val link = java.nio.file.Files.createSymbolicLink(tmp.resolve("link"), real.parentFile.toPath())

        assertThat(JkLayoutPaths.checkoutKey(link.resolve("jk").toFile())).isEqualTo(JkLayoutPaths.checkoutKey(real))
    }

    /** Warm across runs and across a module's tiers is deliberate; the key is the module, not the task. */
    @Test
    fun one_module_has_one_home_and_the_root_project_is_named() {
        assertThat(JkLayoutPaths.moduleKey(":server:engine")).isEqualTo("server-engine")
        assertThat(JkLayoutPaths.moduleKey(":cli")).isEqualTo("cli")
        assertThat(JkLayoutPaths.moduleKey(":")).isEqualTo("root")
        assertThat(JkLayoutPaths.moduleKey("")).isEqualTo("root")
    }
}
