// SPDX-License-Identifier: Apache-2.0

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class TreesTest {

    /** The shape of the warm test home: a stable JDK pointer that is a link to an install jk did not make. */
    private fun homeLinkedTo(tmp: Path, target: Path): Path {
        val home = Files.createDirectories(tmp.resolve("home"))
        Files.writeString(home.resolve(".wiped-at"), "stamp\n")
        val jdks = Files.createDirectories(home.resolve("jdks"))
        Files.createSymbolicLink(jdks.resolve("temurin-25"), target)
        return home
    }

    private fun posixOnly() = assumeFalse(System.getProperty("os.name").startsWith("Windows"))

    @Test
    fun deleting_a_home_unlinks_its_jdk_pointer_and_leaves_the_install_whole(@TempDir tmp: Path) {
        posixOnly()
        val install = Files.createDirectories(tmp.resolve("sdkman").resolve("25.0.4-tem"))
        Files.writeString(install.resolve("release"), "JAVA_VERSION=\"25.0.4\"\n")
        Files.createDirectories(install.resolve("bin"))
        Files.writeString(install.resolve("bin").resolve("java"), "#!/bin/sh\n")
        val home = homeLinkedTo(tmp, install)

        Trees.deleteNoFollow(home.toFile())

        assertThat(home).doesNotExist()
        assertThat(install.resolve("release")).exists()
        assertThat(install.resolve("bin").resolve("java")).exists()
    }

    @Test
    fun a_link_as_the_root_is_unlinked_not_entered(@TempDir tmp: Path) {
        posixOnly()
        val install = Files.createDirectories(tmp.resolve("install"))
        Files.writeString(install.resolve("release"), "x")
        val link = Files.createSymbolicLink(tmp.resolve("pointer"), install)

        Trees.deleteNoFollow(link.toFile())

        assertThat(Files.isSymbolicLink(link)).isFalse()
        assertThat(install.resolve("release")).exists()
    }

    @Test
    fun a_missing_root_is_not_an_error(@TempDir tmp: Path) {
        Trees.deleteNoFollow(tmp.resolve("never-made").toFile())
        assertThat(Trees.exceedsNoFollow(tmp.resolve("never-made").toFile(), 0)).isFalse()
    }

    @Test
    fun size_counts_the_files_reached_directly_and_none_behind_a_link(@TempDir tmp: Path) {
        posixOnly()
        val install = Files.createDirectories(tmp.resolve("install"))
        Files.write(install.resolve("modules"), ByteArray(1_000))
        val home = homeLinkedTo(tmp, install)

        assertThat(Trees.exceedsNoFollow(install.toFile(), 999)).isTrue()
        assertThat(Trees.exceedsNoFollow(home.toFile(), 999))
            .`as`("the kilobyte behind the pointer is the install's, not the home's")
            .isFalse()
        assertThat(Trees.exceedsNoFollow(home.toFile(), 2)).`as`("the stamp itself still counts").isTrue()
    }
}
