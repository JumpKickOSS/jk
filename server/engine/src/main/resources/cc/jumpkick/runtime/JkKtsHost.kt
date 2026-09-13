// SPDX-License-Identifier: Apache-2.0
//
// jk's build-logic .kts host: one JVM for a whole build, each script compiled once.
//
// Not compiled by jk's own build -- jk is a Java project. KtsHostJar compiles this with the
// provisioned Kotlin compiler into <store>/tools/kts-host/<hash>/, once, and KtsSession runs it
// as a child process. It is a resource rather than a Java text block so it stays readable Kotlin.
//
// Protocol, one request per line on stdin, one reply per line on stdout:
//
//     READY                                        (emitted once, at startup)
//     RUN <script>\t<projectDir>\t<outDir>   ->    OK <base64 output>
//                                                  FAIL <base64 output + error>
//                                                  CANCELLED
//     CANCEL                                 ->    (interrupts the script in flight; its RUN
//                                                  answers CANCELLED once the script has stopped)
//     EXIT                                   ->    (exit 0)
//
// Script stdout/stderr is captured per run and returned in the reply rather than streamed, so
// that control lines cannot be corrupted by a script that prints. jk surfaces it only on
// failure, which is what the forked kotlinc host did.
//
// The script runs on a worker thread while the main thread keeps reading stdin, which is what
// lets a CANCEL arrive mid-script. A script that ignores the interrupt never answers; the engine
// then kills this host after its cancel grace and the next build starts a fresh one.
package cc.jumpkick.kts

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.KotlinType
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.providedProperties
import kotlin.script.experimental.api.refineConfiguration
import kotlin.script.experimental.dependencies.DependsOn
import kotlin.script.experimental.dependencies.Repository
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.baseClassLoader
import kotlin.script.experimental.jvm.compilationCache
import kotlin.script.experimental.jvm.defaultJvmScriptingHostConfiguration
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.CompiledScriptJarsCache
import org.jetbrains.kotlin.mainKts.CompilerOptions
import org.jetbrains.kotlin.mainKts.Import
import org.jetbrains.kotlin.mainKts.MainKtsConfigurator

/**
 * The build-logic script definition.
 *
 * `projectDir` and `outDir` are declared here by name and type, and their values are supplied at evaluation time. That
 * is what lets one compiled jar serve every module: the script's own text never mentions a path, so its content hash
 * does not vary by project. The forked-kotlinc host it replaces injected `val projectDir = Path.of("/abs/path")` as
 * text, which gave every module a different script and its own compilation.
 *
 * `MainKtsConfigurator` is reused rather than reimplemented, so `@file:DependsOn`, `@file:Repository`, `@file:Import`
 * and `@file:CompilerOptions` behave exactly as they do in Kotlin's own `main.kts`.
 */
@KotlinScript(fileExtension = "kts", compilationConfiguration = JkScriptConfig::class) abstract class JkScript

object JkScriptConfig :
    ScriptCompilationConfiguration({
        defaultImports(DependsOn::class, Repository::class, Import::class, CompilerOptions::class)
        defaultImports("java.nio.file.Path", "java.nio.file.Files")
        // The whole child classpath: the compiled script extends JkScript, which lives in this jar, so
        // a narrower selection would have to name it and the stdlib by hand and drift when either moves.
        jvm { dependenciesFromCurrentContext(wholeClasspath = true) }
        providedProperties("projectDir" to KotlinType(Path::class), "outDir" to KotlinType(Path::class))
        refineConfiguration {
            onAnnotations(
                DependsOn::class,
                Repository::class,
                Import::class,
                CompilerOptions::class,
                handler = MainKtsConfigurator(),
            )
        }
    })

/**
 * Bumped when a change here would make an already-cached jar wrong -- a new binding, a changed default import, a
 * different script base class. The script's own bytes are the rest of the key, so an edit to a script invalidates only
 * that script.
 */
private const val CACHE_VERSION = 1

/** Content address of a compiled script: its text, this definition, and [CACHE_VERSION]. */
private fun cacheKey(source: SourceCode, @Suppress("UNUSED_PARAMETER") cfg: ScriptCompilationConfiguration): String {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(source.text.toByteArray())
    md.update(JkScriptConfig::class.java.name.toByteArray())
    md.update(CACHE_VERSION.toString().toByteArray())
    return md.digest().joinToString("") { "%02x".format(it) }
}

private fun encode(s: String): String = Base64.getEncoder().encodeToString(s.toByteArray())

/** What the main loop reacts to: a control line, the end of stdin, or a finished script. */
private sealed interface Event

private data class Command(val line: String) : Event

private data class Done(val reply: String) : Event

private object Eof : Event

fun main() {
    // Captured before anything can redirect it: control lines must never mix with script output.
    val control = System.out
    val realErr = System.err

    val cacheDir =
        File(System.getenv("JK_KTS_CACHE") ?: (System.getProperty("java.io.tmpdir") + File.separator + "jk-kts-cache"))
    cacheDir.mkdirs()

    val hostConfig =
        ScriptingHostConfiguration(defaultJvmScriptingHostConfiguration) {
            jvm {
                baseClassLoader(JkScript::class.java.classLoader)
                compilationCache(
                    CompiledScriptJarsCache { source, cfg -> File(cacheDir, cacheKey(source, cfg) + ".jar") }
                )
            }
        }
    val host = BasicJvmScriptingHost(hostConfig)

    val events = LinkedBlockingQueue<Event>()
    thread(isDaemon = true, name = "jk-kts-stdin") {
        while (true) {
            val line = readlnOrNull()
            if (line == null) {
                events.put(Eof)
                return@thread
            }
            events.put(Command(line))
        }
    }
    val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "jk-kts-script").apply { isDaemon = true } }

    control.println("READY")
    control.flush()

    while (true) {
        val line =
            when (val ev = events.take()) {
                Eof -> return
                is Done -> continue // a script that stopped after its run was already answered
                is Command -> ev.line
            }
        if (line.isBlank()) continue
        if (line == "EXIT") return
        if (line == "CANCEL") continue // nothing in flight
        if (!line.startsWith("RUN\t")) {
            control.println("FAIL " + encode("jk kts host: unrecognised request: $line"))
            control.flush()
            continue
        }
        val parts = line.removePrefix("RUN\t").split("\t")
        if (parts.size != 3) {
            control.println("FAIL " + encode("jk kts host: RUN needs script, projectDir, outDir"))
            control.flush()
            continue
        }
        val inFlight: Future<*> = worker.submit { events.put(Done(runOne(host, parts, control, realErr))) }
        // The run answers when the script stops. A CANCEL meanwhile interrupts it and turns the
        // answer into CANCELLED; a script that swallows the interrupt keeps the host here until the
        // engine gives up on it.
        var cancelled = false
        var reply: String? = null
        while (reply == null) {
            when (val next = events.take()) {
                is Done -> reply = if (cancelled) "CANCELLED" else next.reply
                Eof -> return
                is Command ->
                    when (next.line) {
                        "CANCEL" -> {
                            cancelled = true
                            inFlight.cancel(true)
                        }
                        "EXIT" -> return
                        else -> {} // requests are serialised by the engine; nothing else arrives mid-run
                    }
            }
        }
        control.println(reply)
        control.flush()
    }
}

/** One RUN: evaluates the script with its output captured, and renders the reply line. */
private fun runOne(
    host: BasicJvmScriptingHost,
    parts: List<String>,
    control: PrintStream,
    realErr: PrintStream,
): String {
    val captured = ByteArrayOutputStream()
    val sink = PrintStream(captured, true, Charsets.UTF_8)
    val failure =
        try {
            System.setOut(sink)
            System.setErr(sink)
            evaluate(host, File(parts[0]), Path.of(parts[1]), Path.of(parts[2]))
        } catch (e: Throwable) {
            describe(e)
        } finally {
            System.setOut(control)
            System.setErr(realErr)
            sink.flush()
        }
    val output = captured.toString(Charsets.UTF_8)
    return if (failure == null) "OK " + encode(output)
    else "FAIL " + encode(if (output.isEmpty()) failure else output + "\n" + failure)
}

/** Runs one script. Returns null on success, or the failure text. */
private fun evaluate(host: BasicJvmScriptingHost, script: File, projectDir: Path, outDir: Path): String? {
    if (!script.isFile) return "build-logic script not found: $script"
    val evalConfig = ScriptEvaluationConfiguration {
        providedProperties("projectDir" to projectDir, "outDir" to outDir)
    }
    return when (val res = host.eval(script.toScriptSource(), JkScriptConfig, evalConfig)) {
        is ResultWithDiagnostics.Success -> {
            val returned = res.value.returnValue
            if (returned is ResultValue.Error) describe(returned.error) else null
        }
        is ResultWithDiagnostics.Failure ->
            res.reports
                .filter { it.severity >= ScriptDiagnostic.Severity.ERROR }
                .joinToString("\n") { r ->
                    r.location?.start?.let { "${script.name}:${it.line}: ${r.message}" } ?: r.message
                }
                .ifEmpty { "compilation of ${script.name} failed" }
    }
}

/** A thrown failure, root cause first -- the script's own message is what a reader needs. */
private fun describe(e: Throwable): String {
    var root: Throwable = e
    while (root.cause != null && root.cause !== root) root = root.cause!!
    return root.message ?: root.javaClass.simpleName
}
