// SPDX-License-Identifier: Apache-2.0

import net.ltgt.gradle.errorprone.errorprone
import net.ltgt.gradle.nullaway.nullaway

plugins {
    id("jk.java-conventions")
    id("net.ltgt.errorprone")
    id("net.ltgt.nullaway")
}

nullaway {
    onlyNullMarked = true
    jspecifyMode = true
}

dependencies {
    errorprone("com.google.errorprone:error_prone_core:2.50.0")
    errorprone("com.uber.nullaway:nullaway:0.14.1")
}

tasks.withType<JavaCompile>().configureEach {
    val excuse = NullMarking.unmarkedCompileTasks["${project.name}:$name"]
    // Error Prone's own switch, set outside its lambda: inside it, `isEnabled` resolves to the
    // compile task and silently disables the whole compilation instead of the checker.
    options.errorprone.enabled.set(excuse == null)
    options.errorprone {
        disableAllChecks = true
        error("RequireExplicitNullMarking")
        nullaway {
            error()
        }
    }
}

tasks.named("check") {
    dependsOn(rootProject.tasks.named("checkNullMarkedApiPackages"))
}
