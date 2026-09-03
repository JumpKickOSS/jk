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
