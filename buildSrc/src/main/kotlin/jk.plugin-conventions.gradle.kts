// SPDX-License-Identifier: Apache-2.0

// Shared conventions for jk's child-JVM worker modules (the "runner" plugins).
// Each worker jar+POM is installed to store/repos/local under cc.jumpkick:jk-<projectName>
// — the same Maven layout `jk install` writes. Launch rebuilds the runtime classpath from
// that POM. What stays in each worker's build.gradle.kts: its `description`, its
// `dependencies`, and optional codec-vendoring.

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

plugins {
    id("jk.java-conventions")
    `maven-publish`
}

/**
 * Copy [src] onto [dest], replacing any existing file. Uses temp + move so concurrent
 * [installLocal] tasks staging the same project jar (e.g. plugin-sdk) do not race on
 * Kotlin [File.copyTo] overwrite (delete-then-create fails when another task already
 * unlinked the target).
 */
fun copyReplacing(src: File, dest: File) {
    dest.parentFile?.mkdirs()
    val parent = dest.parentFile?.toPath() ?: dest.toPath().parent
    val tmp = Files.createTempFile(parent, ".${dest.name}.", ".tmp")
    try {
        Files.copy(src.toPath(), tmp, StandardCopyOption.REPLACE_EXISTING)
        try {
            Files.move(
                    tmp,
                    dest.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } catch (e: Exception) {
        runCatching { Files.deleteIfExists(tmp) }
        throw e
    }
}

// Coordinates + version must match cc.jumpkick.model.JkVersion.VERSION and the
// cc.jumpkick.engine.plugin.PluginJar registry (artifactId = jk-<projectName>).
group = "cc.jumpkick"
version = "0.12.0"

val workerArtifact = "jk-${project.name}"

publishing {
    publications {
        create<MavenPublication>("worker") {
            artifactId = workerArtifact
            from(components["java"])
            pom.withXml {
                val firstParty = rootProject.subprojects.map { it.name }.toSet()
                fun remap(node: groovy.util.Node) {
                    if (node.name().toString() == "artifactId") {
                        val v = node.text()
                        if (v != null && !v.startsWith("jk-") && v in firstParty) {
                            node.setValue("jk-$v")
                        }
                    }
                    node.children().filterIsInstance<groovy.util.Node>().forEach { remap(it) }
                }
                remap(asNode())
            }
        }
    }
}

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

data class WorkerGav(val group: String, val artifact: String, val version: String, val file: File)

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
                    WorkerGav("cc.jumpkick", artifactId, ver, f)
                } else {
                    WorkerGav(id.group, id.name, id.version, f)
                }
        out.putIfAbsent("${gav.group}:${gav.artifact}:${gav.version}", gav)
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
        sb.appendLine("    </dependency>")
    }
    sb.appendLine("  </dependencies>")
    sb.appendLine("</project>")
    return sb.toString()
}

fun mavenLocalDir(storeRoot: File, group: String, artifact: String, version: String): File =
        storeRoot.resolve("repos/local/${group.replace('.', '/')}/$artifact/$version")

fun installJar(storeRoot: File, group: String, artifact: String, version: String, jar: File) {
    val dir = mavenLocalDir(storeRoot, group, artifact, version)
    dir.mkdirs()
    val dest = dir.resolve("$artifact-$version.jar")
    copyReplacing(jar, dest)
    File(dest.path + ".sha256").writeText(sha256Hex(jar))
    File(dest.path + ".classpath").delete()
    File(dest.path + ".deps").delete()
}

fun installPom(storeRoot: File, group: String, artifact: String, version: String, xml: String) {
    val dir = mavenLocalDir(storeRoot, group, artifact, version)
    dir.mkdirs()
    val dest = dir.resolve("$artifact-$version.pom")
    val bytes = xml.toByteArray(Charsets.UTF_8)
    dest.writeBytes(bytes)
    File(dest.path + ".sha256").writeText(sha256Hex(bytes))
}

fun stageWorkerMavenRepo(storeRoot: File, jar: File, pomXml: String) {
    val ver = project.version.toString()
    installJar(storeRoot, "cc.jumpkick", workerArtifact, ver, jar)
    installPom(storeRoot, "cc.jumpkick", workerArtifact, ver, pomXml)
    runtimeGavs().forEach { g -> installJar(storeRoot, g.group, g.artifact, g.version, g.file) }
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
        dest.deleteRecursively()
        stageWorkerMavenRepo(dest, jarProvider.get().asFile, workerPomXml())
    }
}

// Same destination as `jk install`: store/repos/local/cc/jumpkick/<jk-artifact>/<ver>/.
tasks.register("installLocal") {
    description = "Install $workerArtifact jar+pom into the local Maven store (repos/local)"
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
            println("Already installed $artifact $ver (sha256 match)")
            println("  path:   $target")
            return@doLast
        }
        val staged = workerRepoDir.get().asFile
        if (staged.isDirectory) {
            staged.walkTopDown().filter { it.isFile }.forEach { src ->
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
