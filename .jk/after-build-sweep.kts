// SPDX-License-Identifier: Apache-2.0
// jk: always
//
// Housekeeping for the test sandboxes, at the workspace root's `after-build` anchor. Marked
// `always` because the question it answers — how big are the sandboxes now — is about build output,
// which the action key (sources, manifests, lock) cannot see: a cached verdict on unchanged sources
// would let `jk test -t A`, `-t B`, … fill a sandbox past its cap with every build a cache hit.
//
// A suffixed stem (`after-build-sweep` resolves to the `after-build` anchor), so this runs at the
// same point as the house-rule gate without living inside it: `after-build.kts` checks rules, this
// reclaims disk, and neither has to be read to understand the other.
//
// WHY THIS EXISTS. Every forked test JVM gets a sandbox product home and local m2 from
// `TestEnv.forModule`, under this workspace's build output. Nothing bounds them: `CacheTier`'s
// exhaustive bounds table covers what jk writes under the *cache root*, and these are under
// `target/`. `jk clean` reclaims them, which is the same manual bound Gradle's `clean` is — and
// Gradle added an automatic wipe on top for the obvious reason.
//
// WHY IT IS HERE AND NOT IN THE ENGINE. The engine creates these directories for every jk user, but
// only fills them for tests that spawn nested engines and exercise the real fetch pipeline — jk
// testing itself. Measured across this repo's 31 modules: 26 sit at 4–12 KB, and `clients/cli`
// alone is ~90% of the total at 1.9 GB. The directory is everyone's; the problem is this repo's.
//
// WHY A WIPE AND NOT AN LRU. The window below is a *cadence*, not a use clock — the stamp records
// when the sandbox was last reset, not when anything in it was last read. That is deliberate. A
// sliding window's whole benefit is keeping recent work warm across evictions, and a periodic wipe
// keeps a window's worth warm by construction, so ranking by age buys nothing already present. It
// would also need a touch-on-read to be correct at all: an artifact is written once and read many
// times without being rewritten, so its mtime is a creation clock and a naive window evicts the
// dependency used daily. See `CacheTier` (the rule) and `BASE_JRE` (the one tier that pays for a
// real use clock, with an `.extracted` marker its read path touches).
//
// Bindings are `projectDir` (the workspace root) and `outDir`. Nothing is written to `outDir`.

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration

/** Reset cadence and byte ceiling for one kind of sandbox. Either trigger fires a wipe. */
data class Cap(val label: String, val window: Duration, val bytes: Long)

// Independent caps, judged independently, because the two have different failure modes and
// different refill costs — and because they are no longer the same shape. The m2 became one shared
// directory for the whole workspace, so coupling the decision would let `clients/cli`'s home going
// over its ceiling drop the artifact cache all 31 modules share.
val homeCap = Cap("test-jk-home", Duration.ofDays(7), 1L shl 30)

// Longer and larger: it is one directory rather than one per module, and refilling it costs the
// network rather than local work. Sized against the 467 MB that nine per-module copies reached
// before consolidation — the shared union is smaller than that sum, and 2 GiB is headroom over it
// rather than a target.
val m2Cap = Cap("test-m2", Duration.ofDays(30), 2L shl 30)

// Directories that never contain a sandbox, and one that would cost a walk of the whole tree.
//
// `tmp` is here for a second reason as well as its size. It is the forked test JVMs' temp root
// (`TestEnv` points `TMPDIR` at `<module-target>/tmp/`), so while a suite runs it churns
// `junit-*` directories in and out of existence — and this sweep runs at `after-build`, with
// other modules' tests still going. Walking into it raced them: a directory listed and then
// deleted before it was visited threw `NoSuchFileException` out of the walk and failed the build
// with a bare path for a message. Sandboxes are siblings of `tmp`, never inside it, so pruning
// costs nothing and closes the window rather than catching it after the fact.
val skipDirs =
    setOf("src", "build", ".git", ".gradle", "node_modules", ".board", ".kotlin", ".firebase", "tmp")

/**
 * Sandbox directories, found by name rather than by reconstructing the layout.
 *
 * <p>Two reasons not to compute the paths: modules do not agree on where their target dir sits
 * (`server/engine` sandboxes under `target/server/engine/`, `clients/cli` under
 * `clients/cli/target/`), and a sweep that models the layout goes stale the moment the layout
 * moves — silently, which for a reclaim job means unbounded growth with a green build.
 *
 * <p>`build/` is pruned on purpose: the Gradle side has its own wipe for its own copies, and two
 * sweeps over one directory is how a double delete gets debugged at 2 a.m.
 */
fun sandboxes(): Pair<List<Path>, List<Path>> {
    val homes = mutableListOf<Path>()
    val m2s = mutableListOf<Path>()
    Files.walkFileTree(projectDir, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(d: Path, a: BasicFileAttributes): FileVisitResult {
            if (d == projectDir) return FileVisitResult.CONTINUE
            return when (d.fileName.toString()) {
                homeCap.label -> {
                    homes.add(d)
                    FileVisitResult.SKIP_SUBTREE
                }
                m2Cap.label -> {
                    m2s.add(d)
                    FileVisitResult.SKIP_SUBTREE
                }
                in skipDirs -> FileVisitResult.SKIP_SUBTREE
                else -> FileVisitResult.CONTINUE
            }
        }

        // Same reason `bytesUpTo` has one: a build output tree is live while other modules' tests
        // run, and a directory that vanished between listing and visiting is not a reason to fail
        // a build this script is only measuring. Pruning `tmp` closes the window this actually
        // hit; this keeps the next one from being a red build either.
        override fun visitFileFailed(f: Path, e: java.io.IOException): FileVisitResult = FileVisitResult.CONTINUE
    })
    return homes to m2s
}

/**
 * Bytes under [dir], stopping as soon as [cap] is exceeded.
 *
 * <p>Returns the running total, which is the cap plus a little when it trips — enough to report
 * "over 1 GiB" honestly without walking a 2 GB tree to four significant figures.
 */
fun bytesUpTo(dir: Path, cap: Long): Long {
    var total = 0L
    Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
        override fun visitFile(f: Path, a: BasicFileAttributes): FileVisitResult {
            if (a.isRegularFile) total += a.size()
            return if (total > cap) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
        }

        // A sandbox is live while a nested engine or a test JVM uses it; a file that vanished
        // between listing and stat is not a reason to fail the build that is only measuring.
        override fun visitFileFailed(f: Path, e: java.io.IOException): FileVisitResult = FileVisitResult.CONTINUE
    })
    return total
}

fun deleteTree(dir: Path) {
    if (!Files.exists(dir)) return
    Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
        override fun visitFile(f: Path, a: BasicFileAttributes): FileVisitResult {
            Files.deleteIfExists(f)
            return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(f: Path, e: java.io.IOException): FileVisitResult = FileVisitResult.CONTINUE

        override fun postVisitDirectory(d: Path, e: java.io.IOException?): FileVisitResult {
            Files.deleteIfExists(d)
            return FileVisitResult.CONTINUE
        }
    })
}

/** Human-readable size for the one line this script prints. */
fun mib(bytes: Long): String = "${bytes / (1024 * 1024)} MiB"

val notes = mutableListOf<String>()

fun sweep(dir: Path, cap: Cap) {
    val stamp = dir.resolve(".wiped-at")
    val rel = projectDir.relativize(dir)
    val hasStamp = Files.isRegularFile(stamp)
    val age = if (hasStamp) Duration.ofMillis(System.currentTimeMillis() - Files.getLastModifiedTime(stamp).toMillis())
    else Duration.ZERO
    val stale = hasStamp && age > cap.window
    val size = bytesUpTo(dir, cap.bytes)
    val why = when {
        stale -> "${age.toDays()}d since last reset, window ${cap.window.toDays()}d"
        size > cap.bytes -> "${mib(size)} over the ${mib(cap.bytes)} cap"
        else -> null
    }
    if (why != null) {
        deleteTree(dir)
        notes.add("reset $rel ($why)")
    }
    // The stamp is the audit trail as well as the clock: the line this script prints reaches the
    // terminal (under `-v`, or when piped) but is not journaled, and a job that can delete a 2 GB
    // directory should leave a record a human can find afterwards. `cat .wiped-at` is that record.
    //
    // A sandbox with no stamp is never "stale": it has no reset to be measured from, so the first
    // build after this script lands starts the clock rather than wiping a warm cache on sight.
    if (why != null || !hasStamp) {
        Files.createDirectories(dir)
        Files.writeString(
            stamp,
            "Test sandbox reset clock — see .jk/after-build-sweep.kts\n"
                + "reset-at: ${java.time.Instant.now()}\n"
                + "reason:   ${why ?: "first build since the sweep landed; nothing reclaimed"}\n")
    }
}

val (homes, m2s) = sandboxes()
homes.forEach { sweep(it, homeCap) }
m2s.forEach { sweep(it, m2Cap) }

if (notes.isEmpty()) {
    println("jk sweep: ${homes.size} sandbox home(s) and ${m2s.size} local m2 within caps")
} else {
    notes.forEach { println("jk sweep: $it") }
}
