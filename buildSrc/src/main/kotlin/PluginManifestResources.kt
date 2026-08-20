// SPDX-License-Identifier: Apache-2.0

import java.io.File
import java.util.zip.ZipFile
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.CopySpec

/**
 * Test-classpath fixtures: first-party `jk-plugin.toml` + scaffolds under `cc/jumpkick/plugin/manifest/`. Production
 * jars carry their own manifest at the zip root instead. Never `:core` main / the native CLI.
 */
fun CopySpec.pluginManifestResources(root: Project) {
    from(root.file("plugins/spring-boot/jk-plugin.toml")) {
        into("cc/jumpkick/plugin/manifest")
        rename { "spring-boot.jk-plugin.toml" }
    }
    from(root.file("plugins/spring-boot/scaffold")) { into("cc/jumpkick/plugin/manifest/spring-boot/scaffold") }
    from(root.file("plugins/grails/jk-plugin.toml")) {
        into("cc/jumpkick/plugin/manifest")
        rename { "grails.jk-plugin.toml" }
    }
    from(root.file("plugins/grails/scaffold")) { into("cc/jumpkick/plugin/manifest/grails/scaffold") }
    from(root.file("plugins/quarkus/jk-plugin.toml")) {
        into("cc/jumpkick/plugin/manifest")
        rename { "quarkus.jk-plugin.toml" }
    }
    from(root.file("plugins/quarkus/scaffold")) { into("cc/jumpkick/plugin/manifest/quarkus/scaffold") }
    from(root.file("plugins/micronaut/jk-plugin.toml")) {
        into("cc/jumpkick/plugin/manifest")
        rename { "micronaut.jk-plugin.toml" }
    }
    from(root.file("plugins/micronaut/scaffold")) { into("cc/jumpkick/plugin/manifest/micronaut/scaffold") }
    from(root.file("plugins/android/jk-plugin.toml")) {
        into("cc/jumpkick/plugin/manifest")
        rename { "android.jk-plugin.toml" }
    }
    from(root.file("plugins/protobuf/jk-plugin.toml")) {
        into("cc/jumpkick/plugin/manifest")
        rename { "protobuf.jk-plugin.toml" }
    }
    from(root.file("plugins/minified/jk-plugin.toml")) {
        into("cc/jumpkick/plugin/manifest")
        rename { "minified.jk-plugin.toml" }
    }
}

fun zipEntryNames(jar: File): List<String> {
    if (!jar.isFile) return emptyList()
    ZipFile(jar).use { zip ->
        return zip.entries().asSequence().map { it.name }.toList()
    }
}

fun assertJarHasRootPluginManifest(jar: File) {
    if ("jk-plugin.toml" !in zipEntryNames(jar)) {
        throw GradleException("${jar.name} must contain jk-plugin.toml at the jar root")
    }
}

fun assertJarHasNoFlattenedPluginCatalog(jar: File) {
    val catalog =
        zipEntryNames(jar).filter { it.startsWith("cc/jumpkick/plugin/manifest/") && it.endsWith(".jk-plugin.toml") }
    if (catalog.isNotEmpty()) {
        throw GradleException("${jar.name} must not bake a flattened plugin catalog: $catalog")
    }
}
