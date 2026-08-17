// SPDX-License-Identifier: Apache-2.0

import org.gradle.api.Project
import org.gradle.api.file.CopySpec

/**
 * Bake first-party `jk-plugin.toml` + scaffold templates under `cc/jumpkick/plugin/manifest/`. Used by `:engine`
 * (runtime) and `:core` tests — never by `:core` main / the native CLI.
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
