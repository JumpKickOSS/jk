// SPDX-License-Identifier: Apache-2.0

import java.io.File

/**
 * Gradle-side mirrors of [cc.jumpkick.util.JkDirs] platform defaults (buildSrc cannot depend on :core). Keep in sync
 * when layout resolution changes.
 *
 * Resolution order for the roots: role env → JK_HOME umbrella → XDG / Known Folders. Everything else derives from a
 * root, identically in every mode — JK_HOME mirrors the XDG shape rather than flattening it.
 */
object JkLayoutPaths {

    /** Always {@code <data>/store} — {@code $JK_HOME/data/store} under the umbrella. */
    fun storeRoot(): File {
        nonBlank(System.getenv("JK_STORE_DIR"))?.let {
            return File(it)
        }
        return dataRoot().resolve("store")
    }

    /** The live engine jar / installed app jars: {@code <data>/lib}. */
    fun productLibRoot(): File = dataRoot().resolve("lib")

    fun dataRoot(): File {
        nonBlank(System.getenv("JK_DATA_DIR"))?.let {
            return File(it)
        }
        nonBlank(System.getenv("JK_HOME"))?.let {
            return File(it).resolve("data")
        }
        if (isWindows()) {
            val local = nonBlank(System.getenv("LOCALAPPDATA")) ?: File(userHome(), "AppData/Local").path
            return File(local, "jk/data")
        }
        val xdg = nonBlank(System.getenv("XDG_DATA_HOME"))
        if (xdg != null) return File(xdg, "jk")
        return File(userHome(), ".local/share/jk")
    }

    fun binDir(): File {
        nonBlank(System.getenv("JK_BIN_DIR"))?.let {
            return File(it)
        }
        nonBlank(System.getenv("JK_INSTALL_DIR"))?.let {
            return File(it)
        }
        nonBlank(System.getenv("JK_HOME"))?.let {
            return File(it).resolve("bin")
        }
        if (isWindows()) {
            return File(userHome(), ".local/bin")
        }
        nonBlank(System.getenv("XDG_BIN_HOME"))?.let {
            return File(it)
        }
        nonBlank(System.getenv("XDG_DATA_HOME"))?.let { xdg ->
            File(xdg).parentFile?.let {
                return File(it, "bin")
            }
        }
        return File(userHome(), ".local/bin")
    }

    /**
     * Native client for dogfood tasks (`installLocal` materialize). Always the Graal image from `./gradlew dist` —
     * never the thin JVM `:cli:installDist` scripts (`jk.bat` / `jk`).
     *
     * Order: ship-layout `build/dist/jk[.exe]`, then `:cli:nativeCompile` output. Windows only accepts `jk.exe`
     * (CreateProcess cannot launch the Unix `jk` script or a `.bat` wrapper).
     */
    fun clientCandidates(rootProjectDir: File): List<File> {
        val list = mutableListOf<File>()
        list.add(File(rootProjectDir, "build/dist/jk.exe"))
        list.add(File(rootProjectDir, "build/dist/jk"))
        list.add(File(rootProjectDir, "clients/cli/build/native/nativeCompile/jk.exe"))
        list.add(File(rootProjectDir, "clients/cli/build/native/nativeCompile/jk"))
        return list.distinct()
    }

    fun resolveClient(rootProjectDir: File): String? {
        for (c in clientCandidates(rootProjectDir)) {
            if (isRunnableClient(c)) return c.absolutePath
        }
        return null
    }

    /** True when [file] can be started with {@link ProcessBuilder} on this OS. */
    fun isRunnableClient(file: File): Boolean {
        if (!file.isFile) return false
        val name = file.name.lowercase()
        if (name.endsWith(".exe")) return true
        // Windows: neither `.bat` / `.cmd` nor the extensionless Unix `jk` script is a Win32 image.
        if (isWindows() || name.endsWith(".bat") || name.endsWith(".cmd")) return false
        return file.canExecute()
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
