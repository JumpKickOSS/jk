// SPDX-License-Identifier: Apache-2.0

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * The one way build logic walks a tree to delete it or to size it. Neither walk follows a symbolic link: a link is one
 * entry, unlinked or counted as itself, whatever it points at. Kotlin's `File.deleteRecursively` and `File.walkTopDown`
 * descend through a link to a directory, and the warm test home holds links to JDKs the developer installed with other
 * tools — a sweep that follows them empties those installs and leaves the build's own `java` gone.
 */
object Trees {

    /** Delete `root` and everything under it. A link is unlinked, never entered; a missing root is fine. */
    fun deleteNoFollow(root: File) {
        val path = root.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    runCatching { Files.deleteIfExists(file) }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    runCatching { Files.deleteIfExists(file) }
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    runCatching { Files.deleteIfExists(dir) }
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    /**
     * Every regular file under `root`, in walk order. Links are neither listed nor entered; a missing root is empty.
     */
    fun regularFiles(root: File): List<File> {
        val path = root.toPath()
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        val out = ArrayList<File>()
        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) out.add(file.toFile())
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            },
        )
        return out
    }

    /** True once the regular files under `root` sum past `capBytes`. Links are neither counted nor entered. */
    fun exceedsNoFollow(root: File, capBytes: Long): Boolean {
        val path = root.toPath()
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return false
        var total = 0L
        var exceeded = false
        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) {
                        total += attrs.size()
                        if (total > capBytes) {
                            exceeded = true
                            return FileVisitResult.TERMINATE
                        }
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            },
        )
        return exceeded
    }
}
