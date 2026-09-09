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
version = "0.13.1"

val workerArtifact = "jk-${project.name}"

// No `maven-publish` here, deliberately. A worker's POM is the flattened one
// `writeWorkerPom` builds further down: it resolves the entire runtime classpath itself, names
// every coordinate in it, and `stageWorkerRepo` / `installLocal` stage a jar for each — which is
// what lands in store/repos/jk-local and what scripts/publish-maven-repo.sh uploads. A
// `MavenPublication` alongside it was a second producer of a POM for the same GAV that nothing in
// the tree, the scripts or CI ever read, and its answer disagreed: Gradle renders a project
// dependency from the target's own coordinates, so `:core`, `:io` and `:dynamic-surface` — no
// group, no version, no publication — came out as `jk:core:unspecified`, which no repository can
// serve. The `pom.withXml` block meant to repair that never matched a node in its life:
// `asNode()` parses namespace-aware, so `node.name()` is a `groovy.namespace.QName` whose
// `toString()` is `{http://maven.apache.org/POM/4.0.0}artifactId`, never the bare `"artifactId"`
// it was compared against. Had it matched it would have written `jk:jk-core:unspecified`, no more
// resolvable than before. One producer, the one that ships; PublishedWorkerPomTest in :auditor
// resolves its closure the way a worker launch does.

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

/** Resolved runtime jars with Maven coordinates. First-party projects publish as {@code jk-<name>}. */
fun runtimeGavs(): List<WorkerGav> {
    val runtime = configurations.findByName("runtimeClasspath") ?: return emptyList()
    val artifacts = runCatching { runtime.resolvedConfiguration.resolvedArtifacts }.getOrDefault(emptySet())
    val byFile = artifacts.associateBy { it.file.absoluteFile }
    val out = linkedMapOf<String, WorkerGav>()
    runtime.files.filter { it.isFile && it.name.endsWith(".jar") }.forEach { f ->
        val art = byFile[f.absoluteFile] ?: return@forEach
        val id = art.moduleVersion.id
        val cid = art.id.componentIdentifier
        val gav =
                if (cid is org.gradle.api.artifacts.component.ProjectComponentIdentifier) {
                    val proj = rootProject.findProject(cid.projectPath)
                    val artifactId = publishedArtifactId(proj?.name ?: id.name)
                    val raw = proj?.version?.toString() ?: id.version
                    val ver = if (raw.isBlank() || raw == "unspecified") project.version.toString() else raw
                    WorkerGav("cc.jumpkick", artifactId, ver, null, f)
                } else {
                    // Classifier is part of the artifact identity: dropping it either lost the
                    // dep (netty natives, protoc binaries) or overwrote the unclassified jar's
                    // store entry with the wrong bytes, iteration-order dependent.
                    WorkerGav(id.group, id.name, id.version, art.classifier?.takeIf { it.isNotBlank() }, f)
                }
        out.putIfAbsent("${gav.group}:${gav.artifact}:${gav.version}:${gav.classifier.orEmpty()}", gav)
    }
    return out.values.toList()
}

fun workerPomXml(): String {
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
    sb.appendLine("  <dependencies>")
    runtimeGavs().forEach { g ->
        sb.appendLine("    <dependency>")
        sb.appendLine("      <groupId>${xmlEsc(g.group)}</groupId>")
        sb.appendLine("      <artifactId>${xmlEsc(g.artifact)}</artifactId>")
        sb.appendLine("      <version>${xmlEsc(g.version)}</version>")
        if (g.classifier != null) {
            sb.appendLine("      <classifier>${xmlEsc(g.classifier)}</classifier>")
        }
        sb.appendLine("    </dependency>")
    }
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
 * The POM a first-party dependency jar carries in the staged repo. Minimal on purpose: the
 * worker's flattened POM is the closure of record and already names every coordinate, so this one
 * only has to make `cc.jumpkick:<artifact>` resolvable to a Maven/Gradle consumer — and to
 * scripts/publish-maven-repo.sh, which refuses to upload a first-party jar without a sibling POM.
 */
fun minimalPomXml(group: String, artifact: String, version: String): String = buildString {
    appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
    appendLine("""<project xmlns="http://maven.apache.org/POM/4.0.0">""")
    appendLine("  <modelVersion>4.0.0</modelVersion>")
    appendLine("  <groupId>${xmlEsc(group)}</groupId>")
    appendLine("  <artifactId>${xmlEsc(artifact)}</artifactId>")
    appendLine("  <version>${xmlEsc(version)}</version>")
    appendLine("  <packaging>jar</packaging>")
    appendLine("</project>")
}

fun stageWorkerMavenRepo(storeRoot: File, jar: File, pomXml: String) {
    val ver = project.version.toString()
    installJar(storeRoot, "cc.jumpkick", workerArtifact, ver, jar)
    installPom(storeRoot, "cc.jumpkick", workerArtifact, ver, pomXml)
    runtimeGavs().forEach { g ->
        installJar(storeRoot, g.group, g.artifact, g.version, g.file, g.classifier)
        // First-party dependency jars need a POM too — the published repo serves them to real
        // Maven resolvers, and the publish script hard-refuses a first-party jar without one.
        // Never overwrite: `jk install` writes a richer POM for the same GAV, and clobbering it
        // with this stub would degrade transitive resolution for consumers of that module.
        val pomDest = mavenLocalDir(storeRoot, g.group, g.artifact, g.version)
                .resolve("${g.artifact}-${g.version}.pom")
        if (g.group == "cc.jumpkick" && !pomDest.isFile) {
            installPom(storeRoot, g.group, g.artifact, g.version, minimalPomXml(g.group, g.artifact, g.version))
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
        if (target.isFile &&
                pomTarget.isFile &&
                sha256Hex(target) == hex &&
                sha256Hex(pomTarget) == sha256Hex(pomXml.toByteArray(Charsets.UTF_8))) {
            writeJkMemo(target, "cc.jumpkick", artifact, ver, hex)
            writeJkMemo(pomTarget, "cc.jumpkick", artifact, ver, sha256Hex(pomTarget))
            println("Already installed $artifact $ver (sha256 match)")
            println("  path:   $target")
            return@doLast
        }
        val staged = workerRepoDir.get().asFile
        if (staged.isDirectory) {
            Trees.regularFiles(staged).forEach { src ->
                copyReplacing(src, storeRoot.resolve(src.relativeTo(staged).path))
            }
        } else {
            stageWorkerMavenRepo(storeRoot, jar, pomXml)
        }
        println("Installed $artifact $ver ${jar.length()} bytes")
        println("  sha256: $hex")
        println("  path:   $target")
    }
}

