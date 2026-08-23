// SPDX-License-Identifier: Apache-2.0

import java.io.File

/**
 * Gradle-side mirrors of [cc.jumpkick.util.JkDirs] platform defaults (buildSrc cannot depend on :core). Keep in sync
 * when layout resolution changes.
 *
 * Resolution order for product dirs: role env → JK_HOME umbrella → XDG / Known Folders.
 */
object JkLayoutPaths {

    fun storeRoot(): File {
        nonBlank(System.getenv("JK_STORE_DIR"))?.let {
            return File(it)
        }
        nonBlank(System.getenv("JK_HOME"))?.let {
            return File(it).resolve("store")
        }
        nonBlank(System.getenv("JK_DATA_DIR"))?.let {
            return File(it).resolve("store")
        }
        return dataRoot().resolve("store")
    }

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
     * Preferred client binaries for dogfood tasks (installLocal materialize). First existing runnable wins; callers may
     * still fall back to bare {@code "jk"} on PATH.
     *
     * Order: native `build/dist` (when present) → `:cli:installDist` → platform bin dir. On Windows, the installDist
     * extensionless `jk` file is a Unix shell script and must not be chosen (CreateProcess error 193).
     */
    fun clientCandidates(rootProjectDir: File, cliInstallDistJk: File?): List<File> {
        val list = mutableListOf<File>()
        // Prefer ship-layout native binary when `./gradlew dist` already produced it.
        list.add(File(rootProjectDir, "build/dist/jk.exe"))
        list.add(File(rootProjectDir, "build/dist/jk"))

        if (cliInstallDistJk != null) {
            val bin = if (cliInstallDistJk.isDirectory) cliInstallDistJk else cliInstallDistJk.parentFile
            if (bin != null) {
                if (isWindows()) {
                    list.add(File(bin, "jk.bat"))
                    list.add(File(bin, "jk.cmd"))
                } else {
                    list.add(File(bin, "jk"))
                }
            }
        }

        if (isWindows()) {
            list.add(File(binDir(), "jk.exe"))
            list.add(File(binDir(), "jk.bat"))
            list.add(File(binDir(), "jk.cmd"))
        } else {
            list.add(File(binDir(), "jk"))
        }
        return list.distinct()
    }

    fun resolveClient(rootProjectDir: File, cliInstallDistJk: File?): String? {
        for (c in clientCandidates(rootProjectDir, cliInstallDistJk)) {
            if (isRunnableClient(c)) return c.absolutePath
        }
        // PATH fallback — ProcessBuilder("jk") when available
        return null
    }

    /** True when [file] can be started with {@link ProcessBuilder} on this OS. */
    fun isRunnableClient(file: File): Boolean {
        if (!file.isFile) return false
        val name = file.name.lowercase()
        if (name.endsWith(".exe") || name.endsWith(".bat") || name.endsWith(".cmd")) return true
        // Windows: extensionless Gradle Application `jk` is a #!/bin/sh script — not a Win32 app.
        if (isWindows()) return false
        return file.canExecute()
    }

    private fun userHome(): String = System.getProperty("user.home")

    private fun isWindows(): Boolean {
        val os = System.getProperty("os.name", "").lowercase()
        return os.contains("win")
    }

    private fun nonBlank(s: String?): String? = if (s.isNullOrBlank()) null else s
}
