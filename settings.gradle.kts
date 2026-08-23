// SPDX-License-Identifier: Apache-2.0

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google() // the android plugin's worker bundles apksig (Google Maven)
    }
}

rootProject.name = "jk"

// ---------------------------------------------------------------------------
// Cross-daemon build serialization.
//
// Gradle happily lets several daemons execute builds in this checkout at the
// same time — nothing locks task *output* directories across processes. That
// happens routinely here (multiple terminal/agent sessions building the same
// tree), and the usual casualty is a long test task (e.g. :cli:test): a
// second build's test task wipes that module's build/test-results/test/binary/
// while the first is still writing it, and the first build dies at
// result-collection time with an opaque `java.io.EOFException` (or
// NoSuchFileException on in-progress-results-generic.bin) and zero failing
// tests. Diagnosed 2026-07-09 from overlapping daemon logs (six daemons
// spawned 09:23–09:34, each new one because the previous was busy).
//
// Fix: hold an exclusive OS file lock for the entire build. Acquired eagerly
// during settings evaluation; released when Gradle closes the build service
// at build completion (Gradle 9 removed Gradle.buildFinished). A concurrent
// invocation blocks here — with a message — until the running build finishes.
//
// NOTE: if the configuration cache is ever enabled, settings scripts stop
// running on cache hits, so this eager .get() would too — move the service
// reference onto every task (Task.usesService) at that point.
// ---------------------------------------------------------------------------
abstract class CrossDaemonBuildLock : BuildService<CrossDaemonBuildLock.Params>, AutoCloseable {
    interface Params : BuildServiceParameters {
        val lockFile: Property<String>
    }

    private val channel: FileChannel
    private val lock: FileLock?

    init {
        val path = Path.of(parameters.lockFile.get())
        Files.createDirectories(path.parent)
        channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        lock = try {
            channel.tryLock() ?: run {
                println("Another Gradle build is running in this checkout — waiting for it to finish…")
                channel.lock()
            }
        } catch (e: OverlappingFileLockException) {
            // This daemon already holds the lock (nested build in the same JVM):
            // it is serialized by definition — don't double-lock, don't fail.
            null
        }
    }

    override fun close() {
        lock?.release()
        channel.close()
    }
}

gradle.sharedServices
    .registerIfAbsent("crossDaemonBuildLock", CrossDaemonBuildLock::class) {
        parameters.lockFile.set(rootDir.resolve(".gradle/cross-daemon-build.lock").absolutePath)
    }
    .get() // eager: take the lock now, before any task can touch shared outputs

include(
    // shared/ — client-safe contracts + code (everything the native CLI can link)
    ":jsonl",           // Jsonl / MiniJson / BoundedLineReader (JDK-17; S1 + S7 share only this)
    ":jk-api",          // the domain + scheduler/command SPI contract (zero-dep, IO-free leaf)
    ":plugin-sdk",      // the plugin SPI (JDK-17; published as jk-plugin-sdk)
    ":core",            // config/lock/layout/catalog/deny + filesystem/hashing/XML util
    ":client-io",       // client I/O slice: http, forge auth, credential files, CAS read/link
    ":toolchain-jdk",   // client JDK/tool flow: catalog/installer/registry, launchers, exporters
    ":wire",            // the client<->engine wire contract (was :engine-api)
    ":dynamic-surface", // reflection/proxy/resource surface → R8 keeps + Graal reachability
    // server/ — engine-only (never on the native CLI classpath)
    ":io",              // repo fetch/publish machinery: transports (http/file/s3), POM, metadata
    ":resolver",        // PubGrub solver + conflict diagnostics
    ":toolchain",       // resolver-backed tool installs, Gradle/Maven import machinery
    ":engine",          // the BuildPlan/Task scheduler + build runtime
    // clients/
    ":cli",             // the slim native GraalVM client
    ":cli-terminal",    // controlling TTY / VT style / keys (FFM POSIX+Windows, no JLine)
    ":web",             // the web dashboard SPA (resources-only; bundled into the engine jar)
    // plugins/ — first-party, shipped with jk (one module per plugin)
    ":test-runner",
    ":kotlin-compiler",
    ":groovy-compiler",
    ":java-compiler",
    ":auditor",
    ":publisher",
    ":image-builder",
    ":compat-bridge",
    ":formatter",
    ":spring-boot",
    ":grails",
    ":quarkus",
    ":micronaut",
    ":android",
    ":protobuf",
    ":minified",
)

// shared/ — client-safe contracts + code
project(":jsonl").projectDir         = file("shared/jsonl")
project(":jk-api").projectDir        = file("shared/jk-api")
project(":plugin-sdk").projectDir    = file("shared/plugin-sdk")
project(":core").projectDir          = file("shared/core")
project(":client-io").projectDir     = file("shared/client-io")
project(":toolchain-jdk").projectDir = file("shared/toolchain-jdk")
project(":wire").projectDir          = file("shared/wire")
project(":dynamic-surface").projectDir = file("shared/dynamic-surface")

// server/ — engine-only
project(":io").projectDir        = file("server/io")
project(":resolver").projectDir  = file("server/resolver")
project(":toolchain").projectDir = file("server/toolchain")
project(":engine").projectDir    = file("server/engine")

// clients/
project(":cli").projectDir        = file("clients/cli")
project(":cli-terminal").projectDir = file("clients/cli-terminal")
project(":web").projectDir        = file("clients/web")

// Plugin modules live under plugins/
project(":test-runner").projectDir    = file("plugins/test-runner")
project(":kotlin-compiler").projectDir = file("plugins/kotlin-compiler")
project(":groovy-compiler").projectDir = file("plugins/groovy-compiler")
project(":java-compiler").projectDir  = file("plugins/java-compiler")
project(":auditor").projectDir        = file("plugins/auditor")
project(":publisher").projectDir      = file("plugins/publisher")
project(":image-builder").projectDir  = file("plugins/image-builder")
project(":compat-bridge").projectDir  = file("plugins/compat-bridge")
project(":formatter").projectDir      = file("plugins/formatter")
project(":spring-boot").projectDir    = file("plugins/spring-boot")
project(":grails").projectDir         = file("plugins/grails")
project(":quarkus").projectDir        = file("plugins/quarkus")
project(":micronaut").projectDir      = file("plugins/micronaut")
project(":android").projectDir        = file("plugins/android")
project(":protobuf").projectDir       = file("plugins/protobuf")
project(":minified").projectDir       = file("plugins/minified")
