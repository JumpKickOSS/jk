// SPDX-License-Identifier: Apache-2.0

// Shared conventions for jk's child-JVM worker modules (the "runner" plugins).
// Each worker jar+POM is installed to store/repos/jk-local under cc.jumpkick:jk-<projectName>
// — the same Maven layout `jk install` writes. Launch rebuilds the runtime classpath from
// that POM. What stays in each worker's build.gradle.kts: its `description`, its
// `dependencies`, and optional codec-vendoring.

import java.io.File
import java.security.MessageDigest

plugins {
    id("jk.java-conventions")
}

/** Copy [src] onto [dest]; skip when dest already has the same bytes. */
fun copyReplacing(src: File, dest: File) = CopyReplacing.copy(src, dest)

// Coordinates + version must match cc.jumpkick.model.JkVersion.VERSION and the
// cc.jumpkick.engine.plugin.PluginJar registry (artifactId = jk-<projectName>).
group = "cc.jumpkick"
version = "0.13.3"

val workerArtifact = "jk-${project.name}"

// No `maven-publish` here, deliberately. A worker's POM is the one `writeWorkerPom` builds further
// down — the same shape `jk install` renders for the module from jk.toml: `<dependencies>` names
// what the worker declares (its direct runtime dependencies, first-party rungs included, since
// this jar is thin), and `<dependencyManagement>` pins every coordinate of the runtime closure
// Gradle resolved, so a launch rebuilt from the POM runs on the versions this build compiled and
// tested against whatever a transitive POM asks for. `stageWorkerRepo` / `installLocal` stage a
// jar for every pinned coordinate and a POM for every first-party one — which is what lands in
// store/repos/jk-local and what scripts/publish-maven-repo.sh uploads. A `MavenPublication`
// alongside it was a second producer of a POM for the same GAV that nothing in the tree, the
// scripts or CI ever read, and its answer disagreed: Gradle renders a project dependency from the
// target's own coordinates, so `:core`, `:io` and `:dynamic-surface` — no group, no version, no
// publication — came out as `jk:core:unspecified`, which no repository can serve. One producer,
// the one that ships; PublishedWorkerPomTest in :auditor resolves its closure the way a worker
// launch does.

// Table-owning plugins ship their own jk-plugin.toml (+ scaffold/) at the jar root —
// the same shape as a third-party plugin. Sibling catalogs are not copied here.
val ownManifest = project.file("jk-plugin.toml")
if (ownManifest.isFile) {
    tasks.processResources {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        from(ownManifest)
        val scaffold = project.file("scaffold")
        if (scaffold.isDirectory) {
            from(scaffold) { into("scaffold") }
        }
    }
}

tasks.jar {
    archiveBaseName.set(workerArtifact)
    manifest {
        attributes(
                "Main-Class" to "cc.jumpkick.plugin.process.PluginMain",
                "Implementation-Title" to workerArtifact,
                "Implementation-Version" to project.version)
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/*.EC")
    doLast {
        val jar = archiveFile.get().asFile
        assertJarHasNoFlattenedPluginCatalog(jar)
        if (ownManifest.isFile) {
            assertJarHasRootPluginManifest(jar)
        }
    }
}

fun xmlEsc(s: String): String = buildString {
    for (c in s) {
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }
}

fun publishedArtifactId(gradleName: String): String =
        if (gradleName.startsWith("jk-")) gradleName else "jk-$gradleName"

fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

fun sha256Hex(file: File): String = sha256Hex(file.readBytes())

data class WorkerGav(
        val group: String,
        val artifact: String,
        val version: String,
        val classifier: String?,
        val file: File)

/** The published coordinate of one resolved artifact. First-party projects publish as `jk-<name>`. */
fun gavOf(art: org.gradle.api.artifacts.ResolvedArtifact): WorkerGav {
    val id = art.moduleVersion.id
    val cid = art.id.componentIdentifier
    return if (cid is org.gradle.api.artifacts.component.ProjectComponentIdentifier) {
        val proj = rootProject.findProject(cid.projectPath)
        val artifactId = publishedArtifactId(proj?.name ?: id.name)
        val raw = proj?.version?.toString() ?: id.version
        val ver = if (raw.isBlank() || raw == "unspecified") project.version.toString() else raw
        WorkerGav("cc.jumpkick", artifactId, ver, null, art.file)
    } else {
        // Classifier is part of the artifact identity: dropping it either lost the dep (netty
        // natives, protoc binaries) or overwrote the unclassified jar's store entry with the
        // wrong bytes, iteration-order dependent.
        WorkerGav(id.group, id.name, id.version, art.classifier?.takeIf { it.isNotBlank() }, art.file)
    }
}

fun gavKey(g: WorkerGav) = "${g.group}:${g.artifact}:${g.classifier.orEmpty()}"

/** Resolved runtime jars with Maven coordinates, in classpath order — the closure `<dependencyManagement>` pins. */
fun runtimeGavs(): List<WorkerGav> {
    val runtime = configurations.findByName("runtimeClasspath") ?: return emptyList()
    val artifacts = runCatching { runtime.resolvedConfiguration.resolvedArtifacts }.getOrDefault(emptySet())
    val byFile = artifacts.associateBy { it.file.absoluteFile }
    val out = linkedMapOf<String, WorkerGav>()
    runtime.files.filter { it.isFile && it.name.endsWith(".jar") }.forEach { f ->
        val art = byFile[f.absoluteFile] ?: return@forEach
        val gav = gavOf(art)
        out.putIfAbsent("${gavKey(gav)}:${gav.version}", gav)
    }
    return out.values.toList()
}

/**
 * The resolved runtime graph as Gradle settled it: the worker's direct dependencies in declaration
 * order (`firstLevel`), and for every first-party node the coordinates of its own direct
 * dependencies (`childrenOf`, keyed by [gavKey]) — what that module's staged POM declares, so a
 * launch walking from the worker's declared deps reaches the third-party jars a first-party rung
 * needs (tomlj under jk-core, for one) without the worker POM listing them itself.
 */
data class RuntimeGraph(val firstLevel: List<WorkerGav>, val childrenOf: Map<String, List<WorkerGav>>)

fun runtimeGraph(): RuntimeGraph {
    val runtime = configurations.findByName("runtimeClasspath") ?: return RuntimeGraph(emptyList(), emptyMap())
    val roots = runCatching { runtime.resolvedConfiguration.firstLevelModuleDependencies }.getOrDefault(emptySet())
    fun gavsOf(dep: org.gradle.api.artifacts.ResolvedDependency): List<WorkerGav> =
            runCatching { dep.moduleArtifacts }.getOrDefault(emptySet())
                    .filter { it.file.name.endsWith(".jar") }
                    .map(::gavOf)
                    .distinctBy(::gavKey)
    val childrenOf = linkedMapOf<String, List<WorkerGav>>()
    val visited = hashSetOf<String>()
    fun visit(dep: org.gradle.api.artifacts.ResolvedDependency) {
        if (!visited.add(dep.name)) return
        val self = gavsOf(dep)
        if (self.any { it.group == "cc.jumpkick" }) {
            val kids = dep.children.flatMap(::gavsOf).distinctBy(::gavKey)
            self.forEach { childrenOf.putIfAbsent(gavKey(it), kids) }
        }
        dep.children.forEach(::visit)
    }
    roots.forEach(::visit)
    return RuntimeGraph(roots.flatMap(::gavsOf).distinctBy(::gavKey), childrenOf)
}

fun StringBuilder.appendDependency(g: WorkerGav, indent: String) {
    appendLine("$indent<dependency>")
    appendLine("$indent  <groupId>${xmlEsc(g.group)}</groupId>")
    appendLine("$indent  <artifactId>${xmlEsc(g.artifact)}</artifactId>")
    appendLine("$indent  <version>${xmlEsc(g.version)}</version>")
    if (g.classifier != null) {
        appendLine("$indent  <classifier>${xmlEsc(g.classifier)}</classifier>")
    }
    appendLine("$indent</dependency>")
}

fun workerPomXml(): String {
    val graph = runtimeGraph()
    val sb = StringBuilder()
    sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
    sb.appendLine("""<project xmlns="http://maven.apache.org/POM/4.0.0">""")
    sb.appendLine("  <modelVersion>4.0.0</modelVersion>")
    sb.appendLine("  <groupId>cc.jumpkick</groupId>")
    sb.appendLine("  <artifactId>$workerArtifact</artifactId>")
    sb.appendLine("  <version>${project.version}</version>")
    sb.appendLine("  <packaging>jar</packaging>")
    sb.appendLine("  <name>$workerArtifact</name>")
    val desc = project.description?.trim().orEmpty()
    if (desc.isNotEmpty()) {
        sb.appendLine("  <description>${xmlEsc(desc)}</description>")
    }
    sb.appendLine("  <dependencyManagement>")
    sb.appendLine("    <dependencies>")
    runtimeGavs().forEach { sb.appendDependency(it, "      ") }
    sb.appendLine("    </dependencies>")
    sb.appendLine("  </dependencyManagement>")
    sb.appendLine("  <dependencies>")
    graph.firstLevel.forEach { sb.appendDependency(it, "    ") }
    sb.appendLine("  </dependencies>")
    sb.appendLine("</project>")
    return sb.toString()
}

fun mavenLocalDir(storeRoot: File, group: String, artifact: String, version: String): File =
        storeRoot.resolve("repos/jk-local/${group.replace('.', '/')}/$artifact/$version")

fun jkMemoName(fileName: String): String =
        when {
            fileName.endsWith(".jar") || fileName.endsWith(".aar") || fileName.endsWith(".zip") ->
                    fileName.substring(0, fileName.lastIndexOf('.')) + ".jk"
            else -> fileName + ".jk"
        }

fun writeJkMemo(dest: File, group: String, artifact: String, version: String, hex: String) {
    val memo = dest.resolveSibling(jkMemoName(dest.name))
    memo.writeText("$group:$artifact:$version\n${dest.lastModified()}\n${dest.length()}\n$hex\n")
    File(dest.path + ".sha256").delete()
}

fun installJar(
        storeRoot: File,
        group: String,
        artifact: String,
        version: String,
        jar: File,
        classifier: String? = null) {
    val dir = mavenLocalDir(storeRoot, group, artifact, version)
    dir.mkdirs()
    val suffix = classifier?.let { "-$it" }.orEmpty()
    val dest = dir.resolve("$artifact-$version$suffix.jar")
    copyReplacing(jar, dest)
    writeJkMemo(dest, group, artifact, version, sha256Hex(jar))
    File(dest.path + ".classpath").delete()
    File(dest.path + ".deps").delete()
}

fun installPom(storeRoot: File, group: String, artifact: String, version: String, xml: String) {
    val dir = mavenLocalDir(storeRoot, group, artifact, version)
    dir.mkdirs()
    val dest = dir.resolve("$artifact-$version.pom")
    val bytes = xml.toByteArray(Charsets.UTF_8)
    dest.writeBytes(bytes)
    writeJkMemo(dest, group, artifact, version, sha256Hex(bytes))
}

/**
 * The POM a first-party dependency jar carries in the staged repo: the module's own direct
 * runtime dependencies at the versions Gradle resolved, so a launch walking down from the
 * worker's declared deps reaches everything the rung needs. `jk install` writes a richer POM for
 * the same GAV from the module's jk.toml; this one exists so `cc.jumpkick:<artifact>` resolves for
 * a Maven/Gradle consumer and for scripts/publish-maven-repo.sh, which refuses a first-party jar
 * without a sibling POM.
 */
fun firstPartyPomXml(g: WorkerGav, children: List<WorkerGav>): String = buildString {
    appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
    appendLine("""<project xmlns="http://maven.apache.org/POM/4.0.0">""")
    appendLine("  <modelVersion>4.0.0</modelVersion>")
    appendLine("  <groupId>${xmlEsc(g.group)}</groupId>")
    appendLine("  <artifactId>${xmlEsc(g.artifact)}</artifactId>")
    appendLine("  <version>${xmlEsc(g.version)}</version>")
    appendLine("  <packaging>jar</packaging>")
    appendLine("  <dependencies>")
    children.forEach { appendDependency(it, "    ") }
    appendLine("  </dependencies>")
    appendLine("</project>")
}

/** A POM that declares nothing: the shape an earlier staging left for a first-party dependency. */
fun isDependencyFreePom(pom: File): Boolean = pom.isFile && !pom.readText().contains("<dependencies>")

fun stageWorkerMavenRepo(storeRoot: File, jar: File, pomXml: String) {
    val ver = project.version.toString()
    installJar(storeRoot, "cc.jumpkick", workerArtifact, ver, jar)
    installPom(storeRoot, "cc.jumpkick", workerArtifact, ver, pomXml)
    val graph = runtimeGraph()
    runtimeGavs().forEach { g ->
        installJar(storeRoot, g.group, g.artifact, g.version, g.file, g.classifier)
        // First-party dependency jars need a POM too — the published repo serves them to real
        // Maven resolvers, the publish script hard-refuses a first-party jar without one, and a
        // launch walking down from the worker's declared deps reads it for the rung's own deps.
        // A POM that already declares dependencies is kept: `jk install` writes a richer one for
        // the same GAV from the module's jk.toml. A dependency-free one is a stub an earlier
        // staging left, and a worker launched over it would miss everything under that rung.
        val pomDest = mavenLocalDir(storeRoot, g.group, g.artifact, g.version)
                .resolve("${g.artifact}-${g.version}.pom")
        if (g.group == "cc.jumpkick" && (!pomDest.isFile || isDependencyFreePom(pomDest))) {
            val children = graph.childrenOf[gavKey(g)] ?: emptyList()
            installPom(storeRoot, g.group, g.artifact, g.version, firstPartyPomXml(g, children))
        }
    }
}

/**
 * Every first-party jar in [repoRoot] must have a sibling POM — the contract `jk install` keeps
 * and scripts/publish-maven-repo.sh enforces with a hard exit. Self-failing: a staging that
 * produced no first-party jar at all verified nothing and fails too.
 */
fun assertFirstPartyJarsHavePoms(repoRoot: File) {
    val firstParty = repoRoot.resolve("repos/jk-local/cc/jumpkick")
    val jars = Trees.regularFiles(firstParty).filter { it.extension == "jar" }
    if (jars.isEmpty()) {
        throw GradleException("stageWorkerRepo staged no jar under $firstParty, so the jar+POM"
                + " check verified nothing. The worker jar itself belongs there — fix the staging.")
    }
    val pomless = jars.filter { jar ->
        val ver = jar.parentFile.name
        val art = jar.parentFile.parentFile.name
        !jar.resolveSibling("$art-$ver.pom").isFile
    }
    if (pomless.isNotEmpty()) {
        throw GradleException("A first-party jar without a sibling POM cannot be published or"
                + " resolved — scripts/publish-maven-repo.sh exits 2 on the first one it sees:\n"
                + pomless.joinToString("\n") { "  ${it.relativeTo(repoRoot)}" })
    }
}

fun deleteStaleSidecars(vararg files: File) {
    files.forEach { f ->
        File(f.path + ".classpath").delete()
        File(f.path + ".deps").delete()
    }
}

val jarProvider = tasks.named<Jar>("jar").flatMap { it.archiveFile }
val runtimeCp = configurations.named("runtimeClasspath")

tasks.register("writeWorkerPom") {
    description = "Write the Maven POM next to the worker jar (same GAV as jk install)"
    group = "jk"
    dependsOn(tasks.jar)
    inputs.file(jarProvider)
    inputs.files(runtimeCp)
    doLast {
        val jar = jarProvider.get().asFile
        val pom = File(jar.path.removeSuffix(".jar") + ".pom")
        pom.writeText(workerPomXml())
        deleteStaleSidecars(jar)
    }
}

tasks.named("jar") { finalizedBy("writeWorkerPom") }

val workerRepoDir = layout.buildDirectory.dir("worker-repo")

tasks.register("stageWorkerRepo") {
    description = "Stage $workerArtifact + runtime jars as a Maven repo fragment under build/worker-repo"
    group = "jk"
    dependsOn(tasks.jar, "writeWorkerPom")
    inputs.file(jarProvider)
    inputs.files(runtimeCp)
    outputs.dir(workerRepoDir)
    doLast {
        val dest = workerRepoDir.get().asFile
        Trees.deleteNoFollow(dest)
        stageWorkerMavenRepo(dest, jarProvider.get().asFile, workerPomXml())
        assertFirstPartyJarsHavePoms(dest)
    }
}

// Same destination as `jk install`: store/repos/jk-local/cc/jumpkick/<jk-artifact>/<ver>/.
tasks.register("installLocal") {
    description = "Install $workerArtifact jar+pom into the local Maven store (repos/jk-local)"
    group = "jk"
    dependsOn(tasks.jar, "writeWorkerPom", "stageWorkerRepo")
    inputs.file(jarProvider)
    inputs.files(runtimeCp)
    val artifact = workerArtifact
    val ver = project.version.toString()
    doLast {
        val jar = jarProvider.get().asFile
        val pomXml = workerPomXml()
        val storeRoot: File = JkLayoutPaths.storeRoot()
        val target = mavenLocalDir(storeRoot, "cc.jumpkick", artifact, ver).resolve("$artifact-$ver.jar")
        val pomTarget = mavenLocalDir(storeRoot, "cc.jumpkick", artifact, ver).resolve("$artifact-$ver.pom")
        val hex = sha256Hex(jar)
        deleteStaleSidecars(jar, target)
        val workerUnchanged = target.isFile &&
                pomTarget.isFile &&
                sha256Hex(target) == hex &&
                sha256Hex(pomTarget) == sha256Hex(pomXml.toByteArray(Charsets.UTF_8))
        // The worker's own jar being current says nothing about its closure: a change to a
        // first-party library the worker links (core, model, host) leaves this jar byte-identical
        // while the shelf copy of the library goes stale, and the worker then runs old code. The
        // staged repo is copied on every install; identical bytes are skipped file by file.
        val staged = workerRepoDir.get().asFile
        var replaced = 0
        if (staged.isDirectory) {
            Trees.regularFiles(staged).forEach { src ->
                val dest = storeRoot.resolve(src.relativeTo(staged).path)
                val before = if (dest.isFile) sha256Hex(dest) else ""
                copyReplacing(src, dest)
                if (before != sha256Hex(dest)) replaced++
            }
        } else {
            stageWorkerMavenRepo(storeRoot, jar, pomXml)
            replaced = -1
        }
        if (workerUnchanged && replaced == 0) {
            writeJkMemo(target, "cc.jumpkick", artifact, ver, hex)
            writeJkMemo(pomTarget, "cc.jumpkick", artifact, ver, sha256Hex(pomTarget))
            println("Already installed $artifact $ver (sha256 match, closure current)")
            println("  path:   $target")
            return@doLast
        }
        println("Installed $artifact $ver ${jar.length()} bytes" +
                if (replaced > 0) " ($replaced closure file(s) refreshed)" else "")
        println("  sha256: $hex")
        println("  path:   $target")
    }
}

