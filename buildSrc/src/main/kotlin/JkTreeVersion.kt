// SPDX-License-Identifier: Apache-2.0

import java.io.File

/**
 * The tree's one version, read from the root `jk.toml`. `cc.jumpkick.model.JkVersion.VERSION` states the same literal
 * and the `gradle-bootstrap-parity` guard holds the two equal, so every Gradle coordinate that must match what the
 * client bakes in — the engine jar the client spawns, the worker and rule-pack jars `installLocal` stages, `jk-host`
 * and `jk-guards-junit` — takes its `version` from here and cannot drift when a release bumps the tree.
 */
object JkTreeVersion {

    private val VERSION_LINE = Regex("""^version\s*=\s*"(\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?)"\s*(?:#.*)?$""")

    /** The `version` of the root table of `<rootDir>/jk.toml`. */
    fun of(rootDir: File): String = parse(rootDir.resolve("jk.toml"))

    /** The `version` of the manifest's root table: the first `version = "…"` before any `[table]` header. */
    fun parse(manifest: File): String {
        require(manifest.isFile) { "no root manifest at $manifest; the tree's version lives there" }
        for (line in manifest.readLines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[")) break
            VERSION_LINE.find(trimmed)?.let {
                return it.groupValues[1]
            }
        }
        throw IllegalStateException("$manifest declares no `version = \"x.y.z\"` in its root table")
    }
}
