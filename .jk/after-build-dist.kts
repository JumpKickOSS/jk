// SPDX-License-Identifier: Apache-2.0
// jk: always
//
// The ship layout, assembled from what this build just produced: `target/dist/jk` beside
// `target/dist/lib/jk-engine-<version>.jar`, `target/dist/lib/jk-<version>.jar` and the shelf
// `target/dist/repos/jk-local/` — every workspace module's thin jar and POM in Maven layout, the
// same entries `jk install` puts under `<home>/store/repos/jk-local/`. That is the shape
// `install.sh <binary>` reads — it takes the engine from `<dir-of-binary>/lib/` and shelves
// `<dir-of-binary>/repos/` — so `bash install.sh target/dist/jk` installs the jk this build made,
// engine and workers included: the engine launches the workers built beside it, never the
// published ones of the same version. `bash install.sh target/dist/lib/jk-<version>.jar` installs
// the JVM client the same way, for a host with no native binary.
//
// WHY A SCRIPT AND NOT A FEATURE. Assembling a directory out of files this repo already
// produces is packaging, not a build-system capability, so jk grows no knob for its own ship
// layout.
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
import java.util.Comparator

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

    /**
     * The workspace members, from the manifest's `[workspace] modules` array: the set `jk install`
     * shelves, read from the one place that lists it rather than guessed from what `target/` holds
     * (that tree also holds test sandboxes with shelves of their own).
     */
    fun workspaceModules(): List<String> {
        val manifest = projectDir.resolve("jk.toml")
        if (!Files.isRegularFile(manifest)) return emptyList()
        val out = ArrayList<String>()
        var inModules = false
        for (line in Files.readAllLines(manifest)) {
            val t = line.substringBefore('#').trim()
            if (!inModules) {
                if (Regex("""^modules\s*=\s*\[""").containsMatchIn(t)) inModules = true
                continue
            }
            if (t.startsWith("]")) break
            Regex(""""([^"]+)"""").findAll(t).forEach { out.add(it.groupValues[1]) }
        }
        return out
    }

    /** A module's shelf entry: its thin jar and POM as the build wrote them. */
    data class ModuleArtifacts(val group: String, val artifact: String, val jar: Path, val pom: Path)

    /**
     * The thin jar and POM of the module at [rel] for [version], or null when this build produced
     * none. A worker writes them at its output root, a library under `lib/`; the coordinate comes
     * from the POM, the one file that states it.
     */
    fun moduleArtifacts(rel: String, version: String): ModuleArtifacts? {
        val root = target.resolve(rel)
        for (dir in listOf(root, root.resolve("lib"))) {
            if (!Files.isDirectory(dir)) continue
            val pom = Files.list(dir).use { entries ->
                entries.filter { it.fileName.toString().endsWith("-$version.pom") }
                    .sorted()
                    .findFirst()
                    .orElse(null)
            } ?: continue
            val jar = pom.resolveSibling(pom.fileName.toString().removeSuffix(".pom") + ".jar")
            if (!Files.isRegularFile(jar)) continue
            val text = Files.readString(pom)
            val group = Regex("""<groupId>\s*([^<\s]+)\s*</groupId>""").find(text)?.groupValues?.get(1) ?: continue
            val artifact = Regex("""<artifactId>\s*([^<\s]+)\s*</artifactId>""").find(text)?.groupValues?.get(1) ?: continue
            return ModuleArtifacts(group, artifact, jar, pom)
        }
        return null
    }

    /**
     * Rewrite `dist/repos/jk-local/` from the workspace's module jars and POMs: Maven layout under
     * the store id `jk install` shelves to, and nothing else, so an installer copies the directory
     * as one shelf. Rewritten whole: a version bump leaves no shelf entry of the version before.
     */
    fun writeShelf(dist: Path, version: String): Int {
        val repos = dist.resolve("repos")
        if (Files.isDirectory(repos)) {
            Files.walk(repos).use { walk ->
                walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
        val shelf = repos.resolve("jk-local")
        var shelved = 0
        for (rel in workspaceModules()) {
            val m = moduleArtifacts(rel, version) ?: continue
            val dir = shelf.resolve(m.group.replace('.', '/')).resolve(m.artifact).resolve(version)
            Files.createDirectories(dir)
            Files.copy(m.jar, dir.resolve(m.jar.fileName), StandardCopyOption.REPLACE_EXISTING)
            Files.copy(m.pom, dir.resolve(m.pom.fileName), StandardCopyOption.REPLACE_EXISTING)
            shelved++
        }
        return shelved
    }

    /** The assembly jar for [version], or null when this build produced none. */
    fun assembly(version: String): Pair<Path, String>? {
        val jar = target.resolve("jk-engine-$version-all.jar")
        return if (Files.isRegularFile(jar)) jar to version else null
    }

    /**
     * The CLI module's assembly — the JVM client — for [version], or null when this build produced
     * none. Shipped as `jk-<version>.jar`, the classifier dropped for the same reason as the
     * engine's: a launcher and an installer name one jar.
     */
    fun clientAssembly(version: String): Path? {
        val jar = target.resolve("jk-cli-$version-all.jar")
        return if (Files.isRegularFile(jar)) jar else null
    }

    /**
     * The Maven event spy's module jar for [version], or null when this build produced none. A
     * thin jar on purpose: Maven supplies its API, so there is nothing to bundle. A library
     * module's jar lands under its own `target/<module>/lib/`, not at the workspace root where
     * the assemblies do.
     */
    fun mavenSpy(version: String): Path? {
        val jar = target.resolve("clients/maven-spy/lib/jk-maven-spy-$version.jar")
        return if (Files.isRegularFile(jar)) jar else null
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
            val clientJar = clientAssembly(version)
            val clientJarOut = lib.resolve("jk-$version.jar")
            if (clientJar != null) Files.copy(clientJar, clientJarOut, StandardCopyOption.REPLACE_EXISTING)
            val spyJar = mavenSpy(version)
            val spyJarOut = lib.resolve("jk-maven-spy-$version.jar")
            if (spyJar != null) Files.copy(spyJar, spyJarOut, StandardCopyOption.REPLACE_EXISTING)
            // The pruning half matters as much as the copying:
            // `install.sh` takes the FIRST `lib/jk-engine-*.jar` it globs, so one jar left behind
            // by an earlier version is an installer that pairs today's client with last month's
            // engine. Exactly one engine jar and one client jar live here.
            Files.list(lib).use { entries ->
                entries.filter { p ->
                    val n = p.fileName.toString()
                    n.startsWith("jk-") && n.endsWith(".jar") && p != engineOut && p != clientJarOut && p != spyJarOut
                }.forEach { stale ->
                    Files.deleteIfExists(stale)
                    println("jk dist: removed stale ${projectDir.relativize(stale)}")
                }
            }
            val shelved = writeShelf(dist, version)
            val shipped = listOf(clientOut, engineOut) +
                listOfNotNull(clientJar?.let { clientJarOut }, spyJar?.let { spyJarOut })
            println("jk dist: " + shipped.joinToString(" + ") { projectDir.relativize(it).toString() }
                + " + $shelved module jars under " + projectDir.relativize(dist.resolve("repos")))
            if (clientJar == null) println("jk dist: no jk-cli-$version-all.jar in target/ — no JVM client shipped")
        }
    }
}
