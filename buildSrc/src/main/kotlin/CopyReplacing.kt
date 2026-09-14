// SPDX-License-Identifier: Apache-2.0

import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale

/**
 * Copy a file onto a shared dest via temp sibling + move. Gradle-side twin of [cc.jumpkick.util.AtomicWrites] plus an
 * identical-bytes skip: concurrent `installLocal` tasks stage the same project jar (plugin-sdk) into one store path,
 * and Windows denies REPLACE_EXISTING while another handle still holds dest.
 */
object CopyReplacing {

    /**
     * Attempts before a denied replace is final. Same budget as [cc.jumpkick.util.AtomicWrites]: seven back-off sleeps,
     * ~140 ms.
     */
    internal const val MOVE_ATTEMPTS = 8

    fun copy(src: File, dest: File) {
        dest.parentFile?.mkdirs()
        val srcPath = src.toPath()
        val destPath = dest.toPath()
        if (alreadyCopied(srcPath, dest)) return
        val parent = dest.parentFile?.toPath() ?: destPath.parent
        val tmp = Files.createTempFile(parent, ".${dest.name}.", ".tmp")
        try {
            Files.copy(srcPath, tmp, StandardCopyOption.REPLACE_EXISTING)
            moveInto(tmp, destPath)
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(tmp) }
            throw e
        }
    }

    /**
     * Move a fully-written temp file over [dest]. The temp file must live in [dest]'s directory. On Windows only, a
     * denied replace is retried; a POSIX denial throws on the first attempt.
     */
    internal fun moveInto(tmp: Path, dest: Path) {
        for (attempt in 1..Int.MAX_VALUE) {
            try {
                try {
                    Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING)
                }
                return
            } catch (e: AccessDeniedException) {
                if (!isWindows() || attempt == MOVE_ATTEMPTS) throw e
                sleepBriefly(attempt)
            }
        }
    }

    private fun alreadyCopied(src: Path, dest: File): Boolean {
        if (!dest.isFile) return false
        return try {
            Files.mismatch(src, dest.toPath()) == -1L
        } catch (_: IOException) {
            false
        }
    }

    /** `os.name` read live so a test can spoof it. */
    private fun isWindows(): Boolean = System.getProperty("os.name", "").lowercase(Locale.ROOT).contains("win")

    private fun sleepBriefly(attempt: Int) {
        try {
            Thread.sleep(5L * attempt)
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
