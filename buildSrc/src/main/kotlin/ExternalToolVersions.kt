// SPDX-License-Identifier: Apache-2.0

import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * The identity of an external tool a test task execs but this build does not produce.
 *
 * Gradle's up-to-date check sees the task's declared inputs and nothing else. A test that shells out to `node`, `git`,
 * `protoc` or `bundletool` therefore has a hole in it: the interpreter that actually decides the outcome is invisible,
 * so upgrading it replays a cached green produced by a *different* program. Same failure shape as worker jars and with
 * source files; this is the runtime itself.
 *
 * Declaring the version string — not the binary's bytes — is deliberate. A version is stable across reinstalls of the
 * same release, so a `dnf reinstall` does not invalidate a 15-minute suite, while a real upgrade does. It also covers
 * **absence**: a tool that disappears from `PATH` produces a different identity, which matters here because jk's own
 * probes assume-skip when the tool is missing (`GitCliExtension.detect()`), and a silently halved parity matrix is the
 * fake-green this epic exists to remove.
 *
 * ## The config-time fork, and why it is nearly free
 *
 * The probe has to run at configuration time, because inputs are declared before execution. A fork per tool per build
 * is a real cost, so the answer is cached under the root build directory keyed on the resolved binary's **absolute
 * path, size and mtime**: steady state is one `File.length()` and one `File.lastModified()` per tool, and the fork
 * happens only on the build after the tool actually changes. `clean` costs one fork per tool, once.
 */
object ExternalToolVersions {

    /** A hung `--version` must not hang the build; nothing legitimate takes this long. */
    val PROBE_TIMEOUT: Duration = Duration.ofSeconds(20)

    /**
     * The identity of [tool] as this build's tests will find it: its version line, or an explicit "absent" marker.
     * Suitable as an `inputs.property` value — it is a plain, stable String.
     *
     * @param cacheDir where the path/size/mtime-keyed probe answer is memoised
     * @param tool the executable name, as a test would pass it to `ProcessBuilder`
     * @param searchPath the invoking shell's `PATH`. Passed in rather than read from `System.getenv`, which inside a
     *   long-lived Gradle daemon is the environment the daemon *started* with — the same trap
     *   `clients/web/build.gradle.kts` documents for `JK_WEB_JS_SKIP`. Callers read it through
     *   `providers.environmentVariable`, so putting a different tool on `PATH` and re-running invalidates the task on
     *   that run, not the next one.
     * @param override the value of the product's own binary-override variable (`JK_GIT` for git), likewise read from
     *   the invoking environment, so the build probes what the test will run
     * @param versionArgs how to ask it (`--version` for every tool jk execs today)
     * @param timeout how long the probe may run before it is killed and reported as timed out
     */
    fun identity(
        cacheDir: File,
        tool: String,
        searchPath: String,
        override: String? = null,
        versionArgs: List<String> = listOf("--version"),
        timeout: Duration = PROBE_TIMEOUT,
    ): String {
        val exe = resolve(tool, searchPath, override) ?: return "$tool: absent"
        val key = exe.absolutePath + "|" + exe.length() + "|" + exe.lastModified()
        val memo = File(cacheDir, "$tool.probe")
        if (memo.isFile) {
            val lines = memo.readLines()
            if (lines.size >= 2 && lines[0] == key) return lines[1]
        }
        val version = probe(exe, versionArgs, timeout)
        cacheDir.mkdirs()
        memo.writeText(key + "\n" + version + "\n")
        return version
    }

    /**
     * The binary an exec of [tool] would reach: [override] if it names one, otherwise the first executable match on
     * [searchPath]. `null` when nothing matches.
     */
    private fun resolve(tool: String, searchPath: String, override: String?): File? {
        val named = override?.trim()
        if (!named.isNullOrEmpty()) return File(named).takeIf { it.isFile && it.canExecute() }
        // Windows resolves `git` to `git.exe` inside CreateProcess; PATH holds the bare directory.
        val names = if (File.separatorChar == '\\') listOf("$tool.exe", "$tool.cmd", tool) else listOf(tool)
        return searchPath
            .split(File.pathSeparator)
            .asSequence()
            .filter { it.isNotBlank() }
            .flatMap { dir -> names.asSequence().map { File(dir, it) } }
            .firstOrNull { it.isFile && it.canExecute() }
    }

    /**
     * `<exe> --version`, first non-blank line, prefixed with the resolved path. The child's output goes to a file, so
     * the wait is bounded by [timeout] alone: a pipe would be read to EOF first, and a tool that never closes its
     * stdout (a client that talks to an engine) would hold the build's configuration for as long as it lived.
     */
    private fun probe(exe: File, versionArgs: List<String>, timeout: Duration): String {
        val log = File.createTempFile("jk-tool-version", ".txt")
        try {
            val pb =
                ProcessBuilder(listOf(exe.absolutePath) + versionArgs).redirectErrorStream(true).redirectOutput(log)
            pb.environment()["LC_ALL"] = "C" // a localised version line is still a version change
            val p = pb.start()
            p.outputStream.close()
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly()
                return "${exe.absolutePath}: version probe timed out"
            }
            val line = log.readText().lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            return if (p.exitValue() != 0 || line.isNullOrEmpty()) {
                "${exe.absolutePath}: version probe exited ${p.exitValue()}"
            } else {
                "${exe.absolutePath}: $line"
            }
        } catch (e: Exception) {
            return "${exe.absolutePath}: version probe failed (${e.javaClass.simpleName})"
        } finally {
            log.delete()
        }
    }
}
