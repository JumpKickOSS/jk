// SPDX-License-Identifier: Apache-2.0

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Gradle-side mirror of [cc.jumpkick.util.JkDirs] (buildSrc cannot depend on :core). Keep in sync when layout
 * resolution changes — which is now cheap, because there is one shape: everything jk owns lives under `$HOME/.jk`,
 * relocated wholesale by `JK_HOME`, with per-root overrides only for the large roots.
 */
object JkLayoutPaths {

    /** The one root: `$JK_HOME`, else `$HOME/.jk`. */
    fun homeRoot(): File {
        nonBlank(System.getenv("JK_HOME"))?.let {
            // The same refusal as JkDirs: a relative JK_HOME is not a home jk itself would read.
            require(File(it).isAbsolute) { "JK_HOME must be an absolute path: $it" }
            return File(it)
        }
        return File(userHome(), ".jk")
    }

    /** Fetched artifacts: `<home>/store`, or `JK_STORE_DIR`. */
    fun storeRoot(): File {
        nonBlank(System.getenv("JK_STORE_DIR"))?.let {
            return File(it)
        }
        return homeRoot().resolve("store")
    }

    /** The live engine jar / installed app jars: `<home>/lib`. */
    fun productLibRoot(): File = homeRoot().resolve("lib")

    /** PATH launchers: `<home>/bin`. */
    fun binDir(): File = homeRoot().resolve("bin")

    /**
     * Clients for dogfood tasks (`installLocal` materialize), best first. `:cli:nativeCompile` output leads: it is the
     * binary this build produced, and `build/dist` is only ever a `Sync` copy of it, so the two are identical when both
     * are current and `build/dist` is stale residue when they are not. The thin JVM `:cli:installDist` launcher
     * (`jk.bat` / `jk`) is a supported Windows path (Smart App Control blocks unsigned `jk.exe`); the ship-layout
     * `build/dist/jk[.exe]` trails as a last resort for a tree that has one but no `nativeCompile` output.
     *
     * None of these is trusted on position alone — [probeClients] asks each one its version, because a `build/dist` (or
     * `nativeCompile`) left over from an older day is a *runnable* client of the wrong version, and handing it today's
     * engine jar is exactly the silent mis-install this ordering cannot prevent on its own.
     *
     * Callers that may run alongside `dist` / `nativeCompile` must order after those tasks so the preferred path is not
     * still open for writing (Linux ETXTBSY).
     */
    fun clientCandidates(rootProjectDir: File): List<File> {
        val list = mutableListOf<File>()
        list.add(File(rootProjectDir, "clients/cli/build/native/nativeCompile/jk.exe"))
        list.add(File(rootProjectDir, "clients/cli/build/native/nativeCompile/jk"))
        list.add(File(rootProjectDir, "clients/cli/build/install/jk/bin/jk.bat"))
        list.add(File(rootProjectDir, "clients/cli/build/install/jk/bin/jk"))
        list.add(File(rootProjectDir, "build/dist/jk.exe"))
        list.add(File(rootProjectDir, "build/dist/jk"))
        return list.distinct()
    }

    /** A runnable client and the version it reported, or `null` when it would not say. */
    data class ClientProbe(val path: File, val version: String?)

    /** `jk --version` prints exactly `jk <version>` (cc.jumpkick.cli.Jk). */
    private val VERSION_LINE = Regex("^jk (\\S+)\\s*$", RegexOption.MULTILINE)

    /** Every runnable candidate in [clientCandidates] order, each asked its version. */
    fun probeClients(rootProjectDir: File): List<ClientProbe> =
        clientCandidates(rootProjectDir).filter { isRunnableClient(it) }.map { ClientProbe(it, clientVersion(it)) }

    /** The first probe reporting [version] — the only client allowed to install that version's engine jar. */
    fun pickClient(probes: List<ClientProbe>, version: String): File? =
        probes.firstOrNull { it.version == version }?.path

    /** One line per probe for a task's failure message: what was found and what each one claims to be. */
    fun describeProbes(probes: List<ClientProbe>): String =
        if (probes.isEmpty()) {
            "  (no runnable client found)"
        } else {
            probes.joinToString("\n") { "  ${it.path} -> ${it.version ?: "no version"}" }
        }

    /**
     * What [client] answers to `--version`, or `null` when it cannot be started, exits non-zero, or prints something
     * that is not a version line. A candidate that will not identify itself is never picked.
     */
    fun clientVersion(client: File): String? {
        if (!isRunnableClient(client)) return null
        val log = File.createTempFile("jk-client-version", ".txt")
        try {
            val proc =
                ProcessBuilder(launchCommand(client.absolutePath, "--version"))
                    .redirectErrorStream(true)
                    .redirectOutput(log)
                    .start()
            if (!proc.waitFor(60, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                return null
            }
            if (proc.exitValue() != 0) return null
            return VERSION_LINE.find(log.readText())?.groupValues?.get(1)
        } catch (_: Exception) {
            return null
        } finally {
            log.delete()
        }
    }

    /**
     * The version in an engine jar's file name, `jk-engine-<version>.jar`. Gradle-side mirror of
     * `cc.jumpkick.cache.EngineInstall.versionFromJarName` minus the `.<epoch>` suffix, which only the installed copy
     * ever carries — `shadowJar` always writes the canonical name.
     */
    fun engineJarVersion(engineJar: File): String? {
        val name = engineJar.name
        if (!name.startsWith("jk-engine-") || !name.endsWith(".jar")) return null
        return nonBlank(name.substring("jk-engine-".length, name.length - ".jar".length))
    }

    /**
     * True when [file] can be started on this OS. Windows accepts a PE (`.exe`) or a cmd launcher (`.bat` / `.cmd`);
     * the extensionless Unix `jk` script is not a Win32 image. Launch `.bat` through `cmd.exe /c` (see
     * `:engine:installLocal`).
     */
    fun isRunnableClient(file: File): Boolean {
        if (!file.isFile) return false
        val name = file.name.lowercase()
        if (name.endsWith(".exe")) return true
        if (name.endsWith(".bat") || name.endsWith(".cmd")) return isWindows()
        if (isWindows()) return false
        return file.canExecute()
    }

    /** ProcessBuilder argv that starts [client] with [args] on this OS. */
    fun launchCommand(client: String, vararg args: String): List<String> {
        val name = File(client).name.lowercase()
        val bat = name.endsWith(".bat") || name.endsWith(".cmd")
        return if (isWindows() && bat) listOf("cmd.exe", "/c", client) + args else listOf(client) + args.toList()
    }

    private fun userHome(): String = System.getProperty("user.home")

    /**
     * The one copy of `cc.jumpkick.host.Os.isWindows` that cannot call it: buildSrc compiles before the project, so
     * `:host` is out of reach by construction and guard G20 does not scan here. It tests `"windows"` rather than
     * `"win"` for the same reason the owner does — `"win"` is a substring of `Darwin`, and the short test sent this
     * class looking for `jk.exe` on a Mac.
     */
    private fun isWindows(): Boolean {
        val os = System.getProperty("os.name", "").lowercase()
        return os.contains("windows")
    }

    private fun nonBlank(s: String?): String? = if (s.isNullOrBlank()) null else s
}
