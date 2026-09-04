// SPDX-License-Identifier: Apache-2.0
// jk: always
//
// The ship layout, assembled from what this build just produced: `target/dist/jk` beside
// `target/dist/lib/jk-engine-<version>.jar`. That is the shape `install.sh <binary>` reads — it
// takes the engine from `<dir-of-binary>/lib/` — so `bash install.sh target/dist/jk` installs the
// jk this build made, engine included.
//
// WHY A SCRIPT AND NOT A FEATURE. Assembling a directory out of two files this repo already
// produces is packaging, not a build-system capability; `build.gradle.kts`'s `dist` task is the
// same six lines on the Gradle side. Keeping it here means the two builds can be compared without
// either one growing a knob for the other's benefit.
//
// WHY THE FAT JAR UNDER A THIN NAME. The engine ships as one self-contained jar. jk names the
// assembly `jk-engine-<version>-all.jar` (the `-all` is the assembly classifier) while the shipped
// name carries no classifier, because a client only ever spawns `jk-engine-<its own version>.jar`.
// Copying under the shipped name is what makes the classifier a build detail rather than something
// every installer has to know about. The thin `jk-engine-<version>.jar` in `target/` is the module
// jar — dependencies not included — and is deliberately NOT what lands here.
//
// Marked `jk: always`: it copies build output, which the action key cannot see, so a cached verdict
// on unchanged sources would leave a stale binary in dist after a rebuild.
//
// Bindings are `projectDir` (the workspace root) and `outDir`. Nothing is written to `outDir`.

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

val target: Path = projectDir.resolve("target")
if (!Files.isDirectory(target)) {
    println("jk dist: no target/ yet — nothing to assemble")
} else {

    /**
     * The version being built, from the workspace manifest.
     *
     * <p>Read rather than inferred from the jars in `target/`: that directory accumulates every
     * version ever built here, so globbing for `jk-engine-*-all.jar` picked whichever the
     * filesystem listed first and shipped an older engine beside the current client. The manifest
     * is the one place that says what this build is.
     */
    fun declaredVersion(): String? {
        val manifest = projectDir.resolve("jk.toml")
        if (!Files.isRegularFile(manifest)) return null
        for (line in Files.readAllLines(manifest)) {
            val t = line.trim()
            if (t.startsWith("[")) break // past the top-level keys
            val m = Regex("""^version\s*=\s*"([^"]+)"""").find(t)
            if (m != null) return m.groupValues[1]
        }
        return null
    }

    /** The assembly jar for [version], or null when this build produced none. */
    fun assembly(version: String): Pair<Path, String>? {
        val jar = target.resolve("jk-engine-$version-all.jar")
        return if (Files.isRegularFile(jar)) jar to version else null
    }

    // The native client is named by [native].name, and carries .exe on Windows.
    val client: Path? = listOf("jk", "jk.exe")
        .map { target.resolve(it) }
        .firstOrNull { Files.isRegularFile(it) }

    val version = declaredVersion()
    val engine = version?.let { assembly(it) }
    when {
        version == null -> println("jk dist: no version in jk.toml — skipped")
        client == null ->
            // A module-scoped or non-native build produced no client; the last dist stays as it is
            // rather than being half-rewritten with a stale binary.
            println("jk dist: no native client in target/ — skipped")
        engine == null -> println("jk dist: no jk-engine-$version-all.jar in target/ — skipped")
        else -> {
            val engineJar = engine.first
            val dist = target.resolve("dist")
            val lib = dist.resolve("lib")
            Files.createDirectories(lib)
            val clientOut = dist.resolve(client.fileName)
            val engineOut = lib.resolve("jk-engine-$version.jar")
            // REPLACE_EXISTING, not delete-then-copy: a running engine may hold the old jar open,
            // and on Linux overwriting a busy binary is ETXTBSY — so the client is replaced through
            // a temp file and an atomic move, which swaps the directory entry instead.
            val tmp = dist.resolve(client.fileName.toString() + ".tmp")
            Files.copy(client, tmp, StandardCopyOption.REPLACE_EXISTING)
            if (Files.isExecutable(client)) tmp.toFile().setExecutable(true, false)
            Files.move(tmp, clientOut, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            Files.copy(engineJar, engineOut, StandardCopyOption.REPLACE_EXISTING)
            // Gradle's `dist` is a Sync, and the pruning half matters as much as the copying:
            // `install.sh` takes the FIRST `lib/jk-engine-*.jar` it globs, so one jar left behind
            // by an earlier version is an installer that pairs today's client with last month's
            // engine. Exactly one engine jar lives here.
            Files.list(lib).use { entries ->
                entries.filter { p ->
                    val n = p.fileName.toString()
                    n.startsWith("jk-engine-") && n.endsWith(".jar") && p != engineOut
                }.forEach { stale ->
                    Files.deleteIfExists(stale)
                    println("jk dist: removed stale ${projectDir.relativize(stale)}")
                }
            }
            println("jk dist: ${projectDir.relativize(clientOut)} + ${projectDir.relativize(engineOut)}")
        }
    }
}
