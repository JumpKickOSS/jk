// SPDX-License-Identifier: Apache-2.0

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

class CopyReplacingTest {

    /**
     * Sum of [CopyReplacing.moveInto]'s seven back-off sleeps. A retrying move cannot finish faster than this, and a
     * single failing rename cannot plausibly take this long — so the two tests below bracket the constant from either
     * side.
     */
    private val backOffSumMs = 5L + 10 + 15 + 20 + 25 + 30 + 35

    @Test
    fun copy_creates_parents_and_round_trips(@TempDir dir: Path) {
        val src = Files.writeString(dir.resolve("src.bin"), "hello").toFile()
        val dest = dir.resolve("a/b/dest.bin").toFile()
        CopyReplacing.copy(src, dest)
        assertThat(dest.readText()).isEqualTo("hello")
        assertNoTmpSiblings(dest.toPath().parent, dest.toPath())
    }

    @Test
    fun copy_skips_when_dest_already_matches(@TempDir dir: Path) {
        val src = Files.writeString(dir.resolve("src.bin"), "same").toFile()
        val dest = Files.writeString(dir.resolve("dest.bin"), "same").toFile()
        dest.setLastModified(1_000_000L)
        val stamp = dest.lastModified()
        CopyReplacing.copy(src, dest)
        assertThat(dest.readText()).isEqualTo("same")
        assertThat(dest.lastModified()).isEqualTo(stamp)
        assertNoTmpSiblings(dir, src.toPath(), dest.toPath())
    }

    @Test
    fun copy_replaces_when_dest_differs(@TempDir dir: Path) {
        val src = Files.writeString(dir.resolve("src.bin"), "fresh").toFile()
        val dest = Files.writeString(dir.resolve("dest.bin"), "stale").toFile()
        CopyReplacing.copy(src, dest)
        assertThat(dest.readText()).isEqualTo("fresh")
        assertNoTmpSiblings(dir, src.toPath(), dest.toPath())
    }

    @Test
    fun moveInto_replaces_an_existing_file(@TempDir dir: Path) {
        val dest = Files.writeString(dir.resolve("c.txt"), "stale")
        val tmp = Files.writeString(dir.resolve("c.txt.tmp"), "fresh")
        CopyReplacing.moveInto(tmp, dest)
        assertThat(Files.readString(dest)).isEqualTo("fresh")
        assertThat(Files.exists(tmp)).isFalse()
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun move_into_does_not_retry_a_posix_permission_denial(@TempDir dir: Path) {
        val tmp = Files.writeString(dir.resolve("fresh.tmp"), "fresh")
        val locked = Files.createDirectory(dir.resolve("locked"))
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-xr-xr-x"))
        try {
            assumeFalse(Files.isWritable(locked), "running as root — the mode bits deny nothing")

            denyMove(tmp, locked.resolve("target"))

            val start = System.nanoTime()
            val thrown = denyMove(tmp, locked.resolve("target"))
            val elapsedMs = (System.nanoTime() - start) / 1_000_000L

            assertThat(thrown).isInstanceOf(AccessDeniedException::class.java)
            assertThat(elapsedMs).`as`("failed without backing off").isLessThan(backOffSumMs)
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun move_into_retries_when_the_host_reports_windows(@TempDir dir: Path) {
        val tmp = Files.writeString(dir.resolve("fresh.tmp"), "fresh")
        val locked = Files.createDirectory(dir.resolve("locked"))
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-xr-xr-x"))
        val realOs = System.getProperty("os.name")
        try {
            assumeFalse(Files.isWritable(locked), "running as root — the mode bits deny nothing")
            System.setProperty("os.name", "Windows 11")

            val start = System.nanoTime()
            val thrown = denyMove(tmp, locked.resolve("target"))
            val elapsedMs = (System.nanoTime() - start) / 1_000_000L

            assertThat(thrown).isInstanceOf(AccessDeniedException::class.java)
            assertThat(elapsedMs).`as`("exhausted the back-off before giving up").isGreaterThanOrEqualTo(backOffSumMs)
        } finally {
            if (realOs == null) System.clearProperty("os.name") else System.setProperty("os.name", realOs)
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    private fun denyMove(tmp: Path, target: Path): IOException? {
        return try {
            CopyReplacing.moveInto(tmp, target)
            null
        } catch (e: IOException) {
            e
        }
    }

    private fun assertNoTmpSiblings(dir: Path, vararg keep: Path) {
        Files.list(dir).use { kids -> assertThat(kids.toList()).containsExactlyInAnyOrder(*keep) }
    }
}
