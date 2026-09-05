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
version = "0.13.0"

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

// ---------------------------------------------------------------------------
// Guard G25: `plugins/` holds TWO architectures, and a module's family is declared once.
//
// The families, verified over all 15 modules at cf478a40:
//
//   SPI plugin (7)      ships a jk-plugin.toml. The engine discovers it by descriptor and takes its
//                       wire prefix from [code].protocol-prefix. NO engine source names the prefix.
//   forked worker (8)   ships no descriptor. The engine hardcodes its argv, so an engine source
//                       MUST name the prefix — that is where the fork is spelled.
//
// Defect it prevents: comparing siblings across the boundary and "fixing" the difference. Round 3's
// audits reported "16 different plugin styles" and the divergence list (2 entry-class conventions,
// 2 config-key cases, 4 `run()` shapes) was mostly one family being read as a style violation of
// the other. A uniformity rule has to be written per family, so the family has to be checkable.
//
// Why this and not a `plugins/` vs `workers/` directory split (the original proposal,
// withdrawn — see docs/contributors/code-as-art.md, "Layers"): the descriptor's presence already
// declares the family. A second directory declaring it again is a copy that has to be kept in
// sync, and a guard reading the directory would still have to consult the descriptor to know
// whether the directory was right. Gradle project names here are family-neutral (`:android`,
// `:auditor`) and every `project(":x")` reference — including the worker-jar lists in
// clients/cli/build.gradle.kts and server/engine/build.gradle.kts — is invariant under a rename,
// so the directory carries no build-graph meaning at all. What it would buy is legibility; what
// this buys is a fact the build checks, including the case a directory name cannot see: a module
// whose family and whose actual engine wiring disagree.
//
// Four arms, each self-failing on an empty scan:
//   1  this module declares exactly one `##JK*:` prefix in its own src/main/java
//   2  SPI: [code].protocol-prefix equals it, and no engine source names it
//   3  worker: at least one engine source names it
//   4  SPI: every config key this module (or its descriptor) reads is declared in [schema] /
//      [sub-schema.*], or is one core injects — the arm that would have caught `deploy-verb`
// ---------------------------------------------------------------------------

/** [src] with comments blanked to spaces (newlines and offsets kept), string literals verbatim. */
fun familyGuardCode(src: String): String {
    val out = StringBuilder(src.length)
    var i = 0
    while (i < src.length) {
        when {
            src.startsWith("//", i) -> while (i < src.length && src[i] != '\n') { out.append(' '); i++ }
            src.startsWith("/*", i) -> {
                val end = src.indexOf("*/", i + 2)
                val stop = if (end < 0) src.length else end + 2
                while (i < stop) { out.append(if (src[i] == '\n') '\n' else ' '); i++ }
            }
            src[i] == '"' || src[i] == '\'' -> {
                val quote = src[i]
                out.append(src[i]); i++
                while (i < src.length && src[i] != quote) {
                    if (src[i] == '\\' && i + 1 < src.length) { out.append(src[i]).append(src[i + 1]); i += 2 }
                    else { out.append(src[i]); i++ }
                }
                if (i < src.length) { out.append(src[i]); i++ }
            }
            else -> { out.append(src[i]); i++ }
        }
    }
    return out.toString()
}

/** Uncommented lines of a TOML descriptor — `#` starts a comment at the beginning of a line. */
fun descriptorCode(raw: String): String =
        raw.lineSequence().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")

val familyModuleSources = fileTree(project.layout.projectDirectory) { include("src/main/java/**/*.java") }
val familyEngineSources = fileTree(rootProject.layout.projectDirectory) {
    include("server/*/src/main/java/**/*.java")
}
val variantsOwner =
        rootProject.layout.projectDirectory.file("shared/jk-api/src/main/java/cc/jumpkick/model/Variants.java")
val variantApplyOwner = rootProject.layout.projectDirectory.file(
        "shared/core/src/main/java/cc/jumpkick/plugin/manifest/VariantApply.java")

val checkPluginFamily = registerGuard("checkPluginFamily") {
    group = "verification"
    description = "Fail the build when this module's family (SPI plugin vs forked worker) disagrees with its wiring"
    val moduleName = project.name
    val treeRoot = rootProject.layout.projectDirectory.asFile
    val descriptor = ownManifest
    inputs.files(familyModuleSources).withPropertyName("moduleSources")
    inputs.files(familyEngineSources).withPropertyName("engineSources")
    inputs.file(variantsOwner).withPropertyName("variantsOwner")
    inputs.file(variantApplyOwner).withPropertyName("variantApplyOwner")
    if (descriptor.isFile) inputs.file(descriptor).withPropertyName("descriptor")
    val stamp = layout.buildDirectory.file("guards/plugin-family.ok")
    outputs.file(stamp)
    doLast {
        val quotedPrefix = Regex(""""(##JK[A-Z]+:)"""")

        // --- arm 1: one prefix, declared in this module -----------------------------------------
        val moduleFiles = familyModuleSources.files.sorted()
        if (moduleFiles.isEmpty()) {
            throw GradleException("$moduleName: the plugin-family guard found no Java source under"
                    + " src/main/java, so it verified nothing. Fix the include pattern before"
                    + " trusting a green run.")
        }
        val declared = linkedMapOf<String, MutableList<String>>()
        moduleFiles.forEach { f ->
            val rel = f.relativeTo(treeRoot).invariantSeparatorsPath
            quotedPrefix.findAll(familyGuardCode(f.readText())).forEach { m ->
                declared.getOrPut(m.groupValues[1]) { mutableListOf() }.add(rel)
            }
        }
        if (declared.size != 1) {
            throw GradleException("$moduleName must declare exactly one `##JK*:` wire prefix in its own"
                    + " src/main/java — it is one worker speaking one protocol. Found ${declared.size}:"
                    + declared.entries.joinToString("") { (p, at) -> "\n  $p at ${at.distinct()}" }
                    + "\n  Zero means the worker has no protocol and nothing can talk to it; two means"
                    + " two workers sharing a module, and a reader cannot tell whose line is whose.")
        }
        val prefix = declared.keys.first()

        // --- the engine's half of the pair ------------------------------------------------------
        val engineFiles = familyEngineSources.files.sorted()
        if (engineFiles.size < 350) {
            throw GradleException("$moduleName: the plugin-family guard scanned ${engineFiles.size} engine"
                    + " Java files; it was measured against 424. The include pattern has stopped"
                    + " seeing server/*/src/main/java — fix it before trusting a green run.")
        }
        val engineSites = engineFiles.filter { f ->
            f.readText().contains("##JK") && quotedPrefix.findAll(familyGuardCode(f.readText()))
                    .any { it.groupValues[1] == prefix }
        }.map { it.relativeTo(treeRoot).invariantSeparatorsPath }

        if (descriptor.isFile) {
            // --- arm 2: SPI plugin ---------------------------------------------------------------
            val raw = descriptor.readText()
            val code = descriptorCode(raw)
            val stated = Regex("""protocol-prefix\s*=\s*"([^"]+)"""").find(code)?.groupValues?.get(1)
            if (stated != prefix) {
                throw GradleException("$moduleName ships a jk-plugin.toml, so the engine loads it by"
                        + " descriptor and takes its wire prefix from [code].protocol-prefix. The"
                        + " descriptor says ${stated ?: "nothing"} and the code says $prefix, so the"
                        + " engine would tag one end of the conversation and the plugin the other.")
            }
            if (engineSites.isNotEmpty()) {
                throw GradleException("$moduleName is an SPI plugin (it ships a jk-plugin.toml), so its"
                        + " prefix belongs to the descriptor and the plugin — the engine discovers it"
                        + " and never spells it. These engine sources name $prefix:\n"
                        + engineSites.joinToString("\n") { "  $it" }
                        + "\n  That is a second, hardcoded discovery path for a module that already"
                        + " declares itself. Either delete the descriptor (making it a forked worker,"
                        + " which the engine does spell) or delete the hardcoded fork.")
            }

            // --- arm 4: the descriptor's schema is total ----------------------------------------
            val schemaKeys = mutableSetOf<String>()
            var inSchema = false
            code.lineSequence().forEach { line ->
                val t = line.trim()
                if (t.startsWith("[")) {
                    inSchema = t == "[schema]"
                    Regex("""^\[sub-schema\.([^\]]+)\]$""").find(t)?.let { schemaKeys.add(it.groupValues[1]) }
                } else if (inSchema) {
                    Regex("""^([A-Za-z0-9._-]+)\s*=""").find(t)?.let { schemaKeys.add(it.groupValues[1]) }
                }
            }
            if (schemaKeys.isEmpty()) {
                throw GradleException("$moduleName's jk-plugin.toml declares no [schema] keys, so the"
                        + " totality arm below verified nothing. A plugin with config has a schema.")
            }
            // Keys core injects into every plugin's effective config, read from their owners.
            val buildType = Regex("""String\s+BUILD_TYPE\s*=\s*"([^"]+)"""")
                    .find(variantsOwner.asFile.readText())?.groupValues?.get(1)
                    ?: throw GradleException("cc.jumpkick.model.Variants no longer declares"
                            + " String BUILD_TYPE, so this guard has lost the owner of the injected"
                            + " config keys. Restore it or retire the arm deliberately.")
            val variantPrefix = Regex(""""(variant\.)"\s*\+""")
                    .find(familyGuardCode(variantApplyOwner.asFile.readText()))?.groupValues?.get(1)
                    ?: throw GradleException("VariantApply no longer builds a \"variant.\" + dimension"
                            + " config key, so this guard has lost the second injected shape."
                            + " Restore it or retire the arm deliberately.")

            val reads = linkedMapOf<String, MutableList<String>>()
            val accessor = Regex("""\.(?:string|stringOpt|bool|stringList|group|intValue)\(\s*"([^"]+)"""")
            moduleFiles.forEach { f ->
                val rel = f.relativeTo(treeRoot).invariantSeparatorsPath
                accessor.findAll(familyGuardCode(f.readText())).forEach { m ->
                    reads.getOrPut(m.groupValues[1]) { mutableListOf() }.add(rel)
                }
            }
            // The descriptor reads its own keys too: `when = { config = "k" }` conditions and
            // `${config.k}` interpolations in contributed coordinates.
            val descRel = descriptor.relativeTo(treeRoot).invariantSeparatorsPath
            Regex("""config\s*=\s*"([^"]+)"""").findAll(code).forEach {
                reads.getOrPut(it.groupValues[1]) { mutableListOf() }.add(descRel)
            }
            Regex("""\$\{config\.([^}]+)\}""").findAll(code).forEach {
                reads.getOrPut(it.groupValues[1]) { mutableListOf() }.add(descRel)
            }
            val undeclared = reads.filterKeys { key ->
                val top = key.substringBefore('.')
                top != buildType && !key.startsWith(variantPrefix) && top !in schemaKeys
            }
            if (undeclared.isNotEmpty()) {
                throw GradleException("$moduleName reads config keys its jk-plugin.toml [schema] does not"
                        + " declare. An undeclared key has no type, no default and no hint, so a user"
                        + " typo is silently the default and two spellings of one knob can both be"
                        + " live — that is how `deploy-verb` survived:\n"
                        + undeclared.entries.joinToString("\n") { (k, at) -> "  $k read at ${at.distinct()}" }
                        + "\n  Declared: ${schemaKeys.sorted()}"
                        + "\n  Injected by core: $buildType, ${variantPrefix}<dimension>")
            }
        } else {
            // --- arm 3: forked worker -----------------------------------------------------------
            if (engineSites.isEmpty()) {
                throw GradleException("$moduleName ships no jk-plugin.toml, so it is a forked worker: the"
                        + " engine hardcodes its argv and therefore has to spell $prefix to read its"
                        + " lines. No source under server/*/src/main/java does, which means either the"
                        + " worker is unreachable, or it has grown a descriptor and is now an SPI"
                        + " plugin — in which case the engine's hardcoded fork is the thing to delete."
                        + "\n  (Its two ends are also G5's business; this arm is about which family"
                        + " owns the pair.)")
            }
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}

// ---------------------------------------------------------------------------
// Guard G26: a plugin does not roll its own fork.
//
// `TaskExec.ToolRun.start()` is the one `ProcessBuilder` construction the plugin family needs: it
// resolves the executable head (`JdkFingerprint.tool`, which owns the Windows `.exe` shape), merges
// stderr, applies the cwd and the child environment, and `run()` / `stream()` are drains over it.
// Every exec surface hands one out — TaskExec.tool for a step, PackageIo.tool for a packager and,
// PluginCommandExec.tool for a command body.
//
// Defect it prevents: ten hand-rolled launchers, and what they got wrong. `PluginCommandExec` had
// no `tool()` and no `javaHome()`, so android's command bodies read
// `System.getProperty("java.home")` — which inside a worker is the ENGINE's floor JDK, not the
// project's pin — and `DebugKeystore` hand-built `<javaHome>/bin/keytool` with its own `.exe`
// ternary, the shape G1 bans for `bin/java`. Two of the five also re-implemented line draining.
//
// Scope is this module's src/main/java. Tests are exempt: a test that forks a real tool to prove an
// argv works is a fixture, not a plugin (SigningTest, ZincJavaCompilerEncodingTest).
//
// Self-failing: a module whose scan finds no Java at all fails rather than passing vacuously.
// ---------------------------------------------------------------------------

/**
 * Plugin sources that legitimately construct their own `ProcessBuilder`, by module-relative path.
 * Every entry is a fork `ToolRun` cannot express, and says which.
 */
val pluginForkExemptions = mapOf(
        // Three container-runtime forks (`docker`/`podman` `info`, `run`, `stop`). The runtime is a
        // PATH *name* the user may configure, not a resolved path, and `ToolRun` absolutises its
        // executable head — so expressing these needs a PATH-search owner first.
        "src/main/java/cc/jumpkick/plugin/image/AotCacheTrainer.java" to 3)

val checkPluginForkOwner = registerGuard("checkPluginForkOwner") {
    group = "verification"
    description = "Fail the build when a plugin hand-rolls a process fork instead of TaskExec.ToolRun"
    val moduleName = project.name
    val owner = rootProject.layout.projectDirectory.file(
            "shared/plugin-sdk/src/main/java/cc/jumpkick/plugin/build/TaskExec.java")
    val exemptions = pluginForkExemptions
    inputs.files(familyModuleSources).withPropertyName("moduleSources")
    inputs.file(owner).withPropertyName("toolRunOwner")
    val stamp = layout.buildDirectory.file("guards/plugin-fork-owner.ok")
    outputs.file(stamp)
    doLast {
        // Owner-read tripwire: the route this guard points plugins at must still exist.
        if (!Regex("""Process\s+start\s*\(\s*\)""").containsMatchIn(owner.asFile.readText())) {
            throw GradleException("TaskExec.ToolRun no longer declares `Process start()`, so this guard"
                    + " has lost the fork owner it points plugins at. Restore it or retire the guard"
                    + " deliberately.")
        }
        val files = familyModuleSources.files.sorted()
        if (files.isEmpty()) {
            throw GradleException("$moduleName: the plugin-fork guard found no Java source under"
                    + " src/main/java, so it verified nothing. Fix the include pattern before"
                    + " trusting a green run.")
        }
        val moduleDir = project.layout.projectDirectory.asFile
        val hits = mutableListOf<String>()
        files.forEach { f ->
            val rel = f.relativeTo(moduleDir).invariantSeparatorsPath
            val text = familyGuardCode(f.readText())
            val found = Regex("""new\s+ProcessBuilder\s*\(""").findAll(text).toList()
            val allowed = exemptions[rel] ?: 0
            if (found.size > allowed) {
                found.drop(allowed).forEach { m ->
                    val line = text.substring(0, m.range.first).count { it == '\n' } + 1
                    hits.add("  $rel:$line")
                }
                if (allowed > 0) hits.add("    ($rel is exempt for $allowed fork(s); it now has ${found.size})")
            }
        }
        if (hits.isNotEmpty()) {
            throw GradleException("$moduleName forks a process without going through the SDK's one fork"
                    + " owner. Take a TaskExec.ToolRun from your exec surface — exec.tool(name) for a"
                    + " JDK tool off the build's javaHome, exec.tool(path) for a provisioned binary —"
                    + " then run() / stream(sink), or start() when you need your own drain:\n"
                    + hits.joinToString("\n")
                    + "\n  A hand-rolled fork is where the wrong JDK and the missing .exe get in.")
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}
